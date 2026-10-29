package com.hmdp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.config.RabbitMqConfig;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static com.hmdp.service.impl.SeckillOrderPublisher.PublishResult.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeckillOrderPublisherTest {
    @Mock private RabbitTemplate rabbit;
    @Mock private StringRedisTemplate redis;
    @Mock private HashOperations<String, Object, Object> records;
    private SeckillOrderPublisher publisher;
    private VoucherOrder order;

    @BeforeEach
    void setUp() {
        publisher = new SeckillOrderPublisher();
        ReflectionTestUtils.setField(publisher, "rabbitTemplate", rabbit);
        ReflectionTestUtils.setField(publisher, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(publisher, "confirmTimeoutMillis", 20L);
        when(redis.opsForHash()).thenReturn(records);
        order = new VoucherOrder().setId(9007199254740993L).setUserId(20L).setVoucherId(10L);
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private void completePublish(boolean ack, boolean returned) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            if (returned) correlation.setReturnedMessage(new Message(new byte[0], new MessageProperties()));
            correlation.getFuture().set(new CorrelationData.Confirm(ack, ack ? null : "模拟 NACK"));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void confirmedPublishPreservesIdsAndSetsPersistentMessageBeforeRemovingPendingRecord() {
        completePublish(true, false);

        assertEquals(CONFIRMED, publisher.publish(order));

        ArgumentCaptor<SeckillOrderMessage> body = ArgumentCaptor.forClass(SeckillOrderMessage.class);
        ArgumentCaptor<MessagePostProcessor> processor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        InOrder sequence = inOrder(records, rabbit);
        sequence.verify(records).put(eq(RedisConstants.SECKILL_PENDING_PUBLISH_KEY), eq(order.getId().toString()), any());
        sequence.verify(rabbit).convertAndSend(eq(RabbitMqConfig.ORDER_EXCHANGE), eq(RabbitMqConfig.ORDER_ROUTING_KEY),
                body.capture(), processor.capture(), any(CorrelationData.class));
        sequence.verify(records).delete(RedisConstants.SECKILL_PENDING_PUBLISH_KEY, order.getId().toString());
        assertEquals(order.getId().longValue(), body.getValue().getOrderId());
        assertEquals(20L, body.getValue().getUserId());
        assertEquals(10L, body.getValue().getSeckillId());
        Message amqp = processor.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertEquals(MessageDeliveryMode.PERSISTENT, amqp.getMessageProperties().getDeliveryMode());
        assertEquals(order.getId().toString(), amqp.getMessageProperties().getMessageId());
    }

    @Test
    void returnedMessageIsNotAcceptedEvenWhenBrokerAcknowledgesIt() {
        completePublish(true, true);

        assertEquals(NOT_ROUTED, publisher.publish(order));

        assertLastRecord("PUBLISH_RETURNED");
        verify(records, never()).delete(anyString(), any());
    }

    @Test
    void nackKeepsReservationPendingInsteadOfClaimingDefiniteRejection() {
        completePublish(false, false);
        assertEquals(UNKNOWN, publisher.publish(order));
        assertLastRecord("PUBLISH_UNKNOWN");
    }

    @Test
    void missingConfirmationTimesOutAndRetainsOriginalOrderForReconciliation() {
        assertEquals(UNKNOWN, publisher.publish(order));
        assertLastRecord("PUBLISH_UNKNOWN");
        verify(records, never()).delete(anyString(), any());
    }

    @Test
    void sendExceptionIsAmbiguousAndRetained() {
        doThrow(new AmqpException("模拟连接中断")).when(rabbit).convertAndSend(anyString(), anyString(),
                any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));

        assertEquals(UNKNOWN, publisher.publish(order));

        assertLastRecord("PUBLISH_UNKNOWN");
    }

    @Test
    void failedPendingRecordPreventsSendingAnything() {
        doThrow(new IllegalStateException("模拟 Redis 写入失败")).when(records).put(anyString(), any(), any());

        assertEquals(NOT_SENT, publisher.publish(order));

        verifyNoInteractions(rabbit);
    }

    @Test
    void cleanupFailureDoesNotTurnConfirmedPublishIntoFailure() {
        completePublish(true, false);
        when(records.delete(anyString(), any())).thenThrow(new IllegalStateException("模拟清理失败"));

        assertEquals(CONFIRMED, publisher.publish(order));
    }

    @Test
    void statusUpdateFailureLeavesTheInitialPendingRecordAndUnknownResult() {
        doNothing().doThrow(new IllegalStateException("模拟状态更新失败"))
                .when(records).put(anyString(), any(), any());

        assertEquals(UNKNOWN, publisher.publish(order));

        verify(records, times(2)).put(anyString(), any(), any());
        verify(records, never()).delete(anyString(), any());
    }

    @Test
    void interruptedWaitRestoresThreadInterruptAndKeepsUnknownResult() {
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        assertEquals(UNKNOWN, publisher.publish(order));
        assertTrue(Thread.currentThread().isInterrupted());
        assertLastRecord("PUBLISH_UNKNOWN");
    }

    @Test
    void repeatedPublishUsesUniqueCorrelationIdsButKeepsTheBusinessOrderId() {
        completePublish(true, false);
        assertEquals(CONFIRMED, publisher.publish(order));
        assertEquals(CONFIRMED, publisher.publish(order));
        ArgumentCaptor<CorrelationData> correlations = ArgumentCaptor.forClass(CorrelationData.class);
        ArgumentCaptor<SeckillOrderMessage> messages = ArgumentCaptor.forClass(SeckillOrderMessage.class);
        verify(rabbit, times(2)).convertAndSend(anyString(), anyString(), messages.capture(),
                any(MessagePostProcessor.class), correlations.capture());
        assertNotEquals(correlations.getAllValues().get(0).getId(), correlations.getAllValues().get(1).getId());
        assertEquals(messages.getAllValues().get(0).getOrderId(), messages.getAllValues().get(1).getOrderId());
    }

    private void assertLastRecord(String status) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(records, times(2)).put(eq(RedisConstants.SECKILL_PENDING_PUBLISH_KEY),
                eq(order.getId().toString()), payload.capture());
        List<Object> values = payload.getAllValues();
        JSONObject initial = JSONUtil.parseObj(values.get(0).toString());
        JSONObject saved = JSONUtil.parseObj(values.get(1).toString());
        assertEquals("PUBLISH_PENDING", initial.getStr("status"));
        assertEquals(status, saved.getStr("status"));
        assertEquals(order.getId(), saved.getLong("orderId"));
        assertEquals(order.getUserId(), saved.getLong("userId"));
        assertEquals(order.getVoucherId(), saved.getLong("voucherId"));
    }
}
