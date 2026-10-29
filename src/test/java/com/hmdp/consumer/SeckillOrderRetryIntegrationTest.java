package com.hmdp.consumer;

import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.impl.SeckillOrderPublisher;
import com.hmdp.service.impl.SeckillQualificationService;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.service.impl.VoucherOrderTxService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.LocalDateTime;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 真实 RabbitMQ 联调，事务服务使用 Mockito，不连接数据库、Redis。
 * 使用随机命名的独立队列，结束后删除；不会消费业务队列。
 * 显式启用：-Dhmdp.rabbitmq.retry-integration-test=true
 */
@EnabledIfSystemProperty(named = "hmdp.rabbitmq.retry-integration-test", matches = "true")
@SpringBootTest(classes = SeckillOrderRetryIntegrationTest.TestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeckillOrderRetryIntegrationTest {
    @Resource private RabbitTemplate rabbitTemplate;
    @Resource private RabbitAdmin rabbitAdmin;
    @Resource private RabbitProperties rabbitProperties;
    @Resource private SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory;
    @Resource private MessageConverter messageConverter;

    private VoucherOrderTxService transactions;
    private SimpleMessageListenerContainer container;
    private String exchange;
    private String queue;
    private String deadExchange;
    private String deadQueue;
    private SeckillOrderMessage order;

    @BeforeEach
    void setUp() {
        // 不在测试里覆盖重试参数，直接验证项目 application.yaml 的实际配置。
        RabbitProperties.SimpleContainer settings = rabbitProperties.getListener().getSimple();
        assertEquals(AcknowledgeMode.AUTO, settings.getAcknowledgeMode());
        assertEquals(Boolean.FALSE, settings.getDefaultRequeueRejected());
        assertTrue(settings.getRetry().isEnabled());
        assertEquals(3, settings.getRetry().getMaxAttempts());
        assertEquals(1000L, settings.getRetry().getInitialInterval().toMillis());
        assertEquals(2.0, settings.getRetry().getMultiplier());
        assertEquals(10000L, settings.getRetry().getMaxInterval().toMillis());
        assertEquals("CORRELATED", rabbitProperties.getPublisherConfirmType().name());
        assertTrue(rabbitProperties.isPublisherReturns());
        assertEquals(Boolean.TRUE, rabbitProperties.getTemplate().getMandatory());

        String prefix = "hmdp.retry.it." + UUID.randomUUID();
        exchange = prefix + ".exchange";
        queue = prefix + ".queue";
        deadExchange = prefix + ".dlx";
        deadQueue = prefix + ".dlq";
        // 新版 RabbitMQ 默认不允许非持久化、非独占队列；使用持久化资源并显式清理。
        DirectExchange normal = new DirectExchange(exchange, true, false);
        DirectExchange dead = new DirectExchange(deadExchange, true, false);
        Queue failed = new Queue(deadQueue, true, false, false);
        Queue pending = QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", deadExchange)
                .withArgument("x-dead-letter-routing-key", "order.failed").build();
        rabbitAdmin.declareExchange(normal);
        rabbitAdmin.declareExchange(dead);
        rabbitAdmin.declareQueue(failed);
        rabbitAdmin.declareQueue(pending);
        rabbitAdmin.declareBinding(BindingBuilder.bind(pending).to(normal).with("order"));
        rabbitAdmin.declareBinding(BindingBuilder.bind(failed).to(dead).with("order.failed"));

        transactions = mock(VoucherOrderTxService.class);
        SeckillOrderConsumer consumer = new SeckillOrderConsumer();
        ReflectionTestUtils.setField(consumer, "voucherOrderTxService", transactions);
        // 使用真实消费者方法，但只在测试队列上注册监听，不扫描其业务队列注解。
        MessageListenerAdapter adapter = new MessageListenerAdapter(consumer, "receive");
        adapter.setMessageConverter(messageConverter);
        SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId(prefix);
        endpoint.setQueueNames(queue);
        endpoint.setMessageListener(adapter);
        container = rabbitListenerContainerFactory.createListenerContainer(endpoint);

        order = new SeckillOrderMessage();
        order.setOrderId(10001L);
        order.setUserId(10L);
        order.setSeckillId(5L);
    }

    @Test
    void databaseExceptionIsTriedThreeTimesThenDeadLettered() {
        when(transactions.createVoucherOrder(5L, 10L, 10001L))
                .thenThrow(new IllegalStateException("模拟数据库暂时不可用"));

        sendOrder();
        assertDeadLetter();
    }

    @Test
    void unsuccessfulResultIsAlsoTriedThreeTimesThenDeadLettered() {
        when(transactions.createVoucherOrder(5L, 10L, 10001L))
                .thenReturn(Result.fail("模拟下单失败"));

        sendOrder();
        assertDeadLetter();
    }

    @Test
    void recoveryOnTheThirdAttemptAcknowledgesWithoutDeadLettering() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch succeeded = new CountDownLatch(1);
        when(transactions.createVoucherOrder(5L, 10L, 10001L)).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("模拟前两次数据库不可用");
            }
            succeeded.countDown();
            return Result.ok("10001");
        });

        sendOrder();
        assertTrue(succeeded.await(15, TimeUnit.SECONDS), "第三次处理应恢复成功");
        // stop 等待当前处理完成及 AUTO ACK，随后检查队列不会遇到正在确认的竞争。
        container.stop();
        verifyThreeAttempts();
        assertQueueEmpty(queue);
        assertQueueEmpty(deadQueue);
    }

    @Test
    void entryPublishesToRabbitAndConsumerCreatesTheSameOrderLater() {
        EntryFixture fixture = new EntryFixture();
        when(transactions.createVoucherOrder(5L, 10L, 10001L)).thenReturn(Result.ok("10001"));

        // 此时监听器尚未启动：接口受理只等待 Broker，不等待数据库下单。
        Result result = fixture.entry.seckillVoucher(5L);

        assertTrue(result.getSuccess());
        assertEquals("10001", result.getData());
        verifyNoInteractions(transactions);
        Properties pending = rabbitAdmin.getQueueProperties(queue);
        assertNotNull(pending);
        assertEquals(1, ((Number) pending.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue());
        verify(fixture.qualifications, never()).releaseReservation(anyLong(), anyLong());
        verify(fixture.records).delete(RedisConstants.SECKILL_PENDING_PUBLISH_KEY, "10001");

        container.start();
        verify(transactions, timeout(10000)).createVoucherOrder(5L, 10L, 10001L);
        container.stop();
        verifyNoMoreInteractions(transactions);
        assertQueueEmpty(queue);
        assertQueueEmpty(deadQueue);
        verify(fixture.lock).unlock();
    }

    @Test
    void brokerReturnMakesEntryRestoreReservationWithoutCreatingAnOrder() {
        EntryFixture fixture = new EntryFixture();
        ReflectionTestUtils.setField(fixture.publisher, "routingKey", "unbound." + UUID.randomUUID());
        when(fixture.qualifications.releaseReservation(5L, 10L)).thenReturn(0L);

        Result result = fixture.entry.seckillVoucher(5L);

        assertFalse(result.getSuccess());
        assertEquals("下单消息未提交，请稍后重试", result.getErrorMsg());
        assertNull(result.getData());
        verify(fixture.qualifications).releaseReservation(5L, 10L);
        verify(fixture.records).delete(RedisConstants.SECKILL_PENDING_PUBLISH_KEY, "10001");
        verifyNoInteractions(transactions);
        verify(fixture.lock).unlock();
        assertQueueEmpty(queue);
        assertQueueEmpty(deadQueue);
    }

    /** 入口保留真实业务逻辑；只有 Redis 资格服务、锁、ID 和数据库服务使用替身。 */
    private class EntryFixture {
        final VoucherOrderServiceImpl entry = new VoucherOrderServiceImpl();
        final SeckillOrderPublisher publisher = new SeckillOrderPublisher();
        final SeckillQualificationService qualifications = mock(SeckillQualificationService.class);
        final RLock lock = mock(RLock.class);
        final HashOperations<String, Object, Object> records = mock(HashOperations.class);

        EntryFixture() {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            when(redis.opsForHash()).thenReturn(records);
            ReflectionTestUtils.setField(publisher, "rabbitTemplate", rabbitTemplate);
            ReflectionTestUtils.setField(publisher, "stringRedisTemplate", redis);
            ReflectionTestUtils.setField(publisher, "exchange", exchange);
            ReflectionTestUtils.setField(publisher, "routingKey", "order");
            ISeckillVoucherService vouchers = mock(ISeckillVoucherService.class);
            LocalDateTime now = LocalDateTime.now();
            when(vouchers.getById(5L)).thenReturn(new SeckillVoucher().setVoucherId(5L).setStock(1)
                    .setBeginTime(now.minusMinutes(1)).setEndTime(now.plusMinutes(5)));
            RedissonClient redisson = mock(RedissonClient.class);
            when(redisson.getLock(RedisConstants.LOCK_ORDER_KEY + "10:5")).thenReturn(lock);
            when(lock.tryLock()).thenReturn(true);
            when(qualifications.checkAndReserve(5L, 10L)).thenReturn(0L);
            RedisIdWorker ids = mock(RedisIdWorker.class);
            when(ids.nextId(RedisConstants.SECKILL_ORDER)).thenReturn(10001L);
            ReflectionTestUtils.setField(entry, "seckillVoucherService", vouchers);
            ReflectionTestUtils.setField(entry, "redissonClient", redisson);
            ReflectionTestUtils.setField(entry, "seckillQualificationService", qualifications);
            ReflectionTestUtils.setField(entry, "redisIdWorker", ids);
            ReflectionTestUtils.setField(entry, "seckillOrderPublisher", publisher);
            UserDTO user = new UserDTO();
            user.setId(10L);
            UserHolder.saveUser(user);
        }
    }

    private void sendOrder() {
        container.start();
        rabbitTemplate.invoke(operations -> {
            operations.convertAndSend(exchange, "order", order);
            operations.waitForConfirmsOrDie(5000);
            return null;
        });
    }

    private void assertDeadLetter() {
        Message failed = rabbitTemplate.receive(deadQueue, 15000);
        assertNotNull(failed, "重试耗尽后应在 15 秒内收到死信");
        container.stop();
        verifyThreeAttempts();

        Object decoded = messageConverter.fromMessage(failed);
        assertTrue(decoded instanceof SeckillOrderMessage, "死信应仍为订单消息");
        SeckillOrderMessage actual = (SeckillOrderMessage) decoded;
        assertEquals(order.getOrderId(), actual.getOrderId());
        assertEquals(order.getUserId(), actual.getUserId());
        assertEquals(order.getSeckillId(), actual.getSeckillId());

        Object deaths = failed.getMessageProperties().getHeaders().get("x-death");
        assertTrue(deaths instanceof List, "RabbitMQ 应写入死信来源信息");
        List<?> entries = (List<?>) deaths;
        assertEquals(1, entries.size());
        Map<?, ?> death = (Map<?, ?>) entries.get(0);
        assertEquals("rejected", String.valueOf(death.get("reason")));
        assertEquals(queue, String.valueOf(death.get("queue")));
        // 3 次是在 Spring 内部重试；RabbitMQ 最终只将该消息转成 1 次死信。
        assertEquals(1L, ((Number) death.get("count")).longValue());
        assertQueueEmpty(queue);
        assertQueueEmpty(deadQueue); // 唯一一条死信已被本测试读取，不应还有重复消息。
    }

    private void verifyThreeAttempts() {
        verify(transactions, times(3)).createVoucherOrder(5L, 10L, 10001L);
        verifyNoMoreInteractions(transactions);
    }

    private void assertQueueEmpty(String name) {
        Properties properties = rabbitAdmin.getQueueProperties(name);
        assertNotNull(properties, "测试队列应存在");
        assertEquals(0, ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue());
        assertEquals(0, ((Number) properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT)).intValue());
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        if (container != null) {
            container.stop();
            container.destroy();
        }
        // 仅删除本次随机生成的资源；业务队列、交换机、策略均不修改。
        if (queue != null) rabbitAdmin.deleteQueue(queue);
        if (deadQueue != null) rabbitAdmin.deleteQueue(deadQueue);
        if (exchange != null) rabbitAdmin.deleteExchange(exchange);
        if (deadExchange != null) rabbitAdmin.deleteExchange(deadExchange);
    }

    @Configuration
    @Import(RabbitAutoConfiguration.class)
    static class TestConfiguration {
        @Bean
        MessageConverter rabbitMessageConverter() {
            return new Jackson2JsonMessageConverter();
        }
    }
}
