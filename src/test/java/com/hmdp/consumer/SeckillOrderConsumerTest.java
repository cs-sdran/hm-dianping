package com.hmdp.consumer;

import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.service.impl.VoucherOrderTxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅验证消费者对结果的处理，不连接 RabbitMQ，也不访问数据库。 */
@ExtendWith(MockitoExtension.class)
class SeckillOrderConsumerTest {
    @Mock
    private VoucherOrderTxService transactions;

    private SeckillOrderConsumer consumer;
    private SeckillOrderMessage message;

    @BeforeEach
    void setUp() {
        consumer = new SeckillOrderConsumer();
        ReflectionTestUtils.setField(consumer, "voucherOrderTxService", transactions);
        message = new SeckillOrderMessage();
        message.setSeckillId(5L);
        message.setUserId(10L);
        message.setOrderId(10001L);
    }

    @Test
    void successReturnsNormallyAndUsesTheOriginalMessageIds() {
        when(transactions.createVoucherOrder(5L, 10L, 10001L)).thenReturn(Result.ok("10001"));

        assertDoesNotThrow(() -> consumer.receive(message));

        verify(transactions).createVoucherOrder(5L, 10L, 10001L);
        verifyNoMoreInteractions(transactions);
    }

    @Test
    void businessFailureThrowsWithTheOrderIdAndReason() {
        when(transactions.createVoucherOrder(5L, 10L, 10001L)).thenReturn(Result.fail("库存不足"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> consumer.receive(message));

        assertTrue(failure.getMessage().contains("10001"));
        assertTrue(failure.getMessage().contains("库存不足"));
        verify(transactions).createVoucherOrder(5L, 10L, 10001L);
    }

    @Test
    void missingResultIsAnExplicitFailure() {
        when(transactions.createVoucherOrder(5L, 10L, 10001L)).thenReturn(null);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> consumer.receive(message));

        assertTrue(failure.getMessage().contains("未返回处理结果"));
        assertTrue(failure.getMessage().contains("10001"));
    }

    @Test
    void missingSuccessFlagIsNotMistakenForSuccessOrUnboxed() {
        Result incomplete = new Result();
        incomplete.setErrorMsg("返回状态缺失");
        when(transactions.createVoucherOrder(5L, 10L, 10001L)).thenReturn(incomplete);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> consumer.receive(message));

        assertTrue(failure.getMessage().contains("返回状态缺失"));
    }

    @Test
    void databaseExceptionPropagatesToTheListenerContainer() {
        IllegalStateException databaseFailure = new IllegalStateException("数据库暂时不可用");
        when(transactions.createVoucherOrder(5L, 10L, 10001L)).thenThrow(databaseFailure);

        assertSame(databaseFailure, assertThrows(IllegalStateException.class,
                () -> consumer.receive(message)));
        verify(transactions).createVoucherOrder(5L, 10L, 10001L);
        verifyNoMoreInteractions(transactions);
    }
}
