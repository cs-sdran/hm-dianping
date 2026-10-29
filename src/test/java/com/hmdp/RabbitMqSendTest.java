package com.hmdp;

import com.hmdp.config.RabbitMqConfig;
import com.hmdp.consumer.SeckillOrderConsumer;
import com.hmdp.dto.SeckillOrderMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.annotation.Resource;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手动收发练习：先启动黑马点评，再使用 -Dhmdp.rabbitmq.send-test=true 运行。
 * 普通全量测试会跳过本类，避免向真实队列发送练习消息。
 */
@EnabledIfSystemProperty(named = "hmdp.rabbitmq.send-test", matches = "true")
@SpringBootTest(classes = RabbitMqSendTest.SenderConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.rabbitmq.publisher-confirm-type=simple"
        })
class RabbitMqSendTest {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private RabbitAdmin rabbitAdmin;

    @Test
    void testSendMessage() {
        // 接收方是已经运行的黑马点评应用，发送测试本身不启动消费者。
        Properties queue = rabbitAdmin.getQueueProperties("seckill.order.queue");
        assertNotNull(queue, "订单队列不存在，请先声明交换机、队列和绑定");
        Number consumers = (Number) queue.get(RabbitAdmin.QUEUE_CONSUMER_COUNT);
        assertTrue(consumers != null && consumers.intValue() > 0,
                "请先启动黑马点评，使 SeckillOrderConsumer 开始监听队列");

        String message = "Java发送的秒杀测试消息";
        SeckillOrderMessage seckillOrderMessage = new SeckillOrderMessage();
        seckillOrderMessage.setOrderId(10001L);
        seckillOrderMessage.setSeckillId(5L);
        seckillOrderMessage.setUserId(10L);

        rabbitTemplate.invoke(operations -> {
            // 三个参数依次为：交换机、路由键、消息内容。
            operations.convertAndSend("seckill.order.exchange", "seckill.order", seckillOrderMessage);
            // 确认 RabbitMQ 已接收消息；这不代表数据库已创建订单。
            operations.waitForConfirmsOrDie(5000);
            return null;
        });

        System.out.println("发送成功，RabbitMQ 已确认接收：" + message);
    }

    // 只加载 RabbitMQ 自动配置和你写的三个 Bean，不加载数据库、Redis及后台下单线程。
    @Configuration
    @Import({RabbitAutoConfiguration.class, RabbitMqConfig.class})
    static class SenderConfiguration {
    }
}
