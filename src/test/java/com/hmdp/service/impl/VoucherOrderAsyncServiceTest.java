package com.hmdp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoucherOrderAsyncServiceTest {
    @Mock private VoucherOrderTxService transactions;
    @Mock private StringRedisTemplate redis;
    @Mock private HashOperations<String, Object, Object> failures;
    @Mock private ScheduledExecutorService retries;
    private VoucherOrderAsyncService service;
    private VoucherOrder order;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderAsyncService();
        ReflectionTestUtils.setField(service, "voucherOrderTxService", transactions);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "retryExecutor", retries);
        when(redis.opsForHash()).thenReturn(failures);
        order = new VoucherOrder().setId(123L).setUserId(20L).setVoucherId(10L);
    }

    @AfterEach
    void stopExecutors() {
        service.destory();
    }

    private void process(int attempt) {
        ReflectionTestUtils.invokeMethod(service, "processOrder", order, attempt);
    }

    private JSONObject recordedFailure() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(failures).put(eq(RedisConstants.SECKILL_FAILED_ORDERS_KEY), eq("123"), payload.capture());
        return JSONUtil.parseObj(payload.getValue().toString());
    }

    @Test
    void successfulOrderClearsFailureWithoutRetrying() {
        when(transactions.createVoucherOrder(10L, 20L, 123L)).thenReturn(Result.ok("123"));
        process(1);
        verify(failures).delete(RedisConstants.SECKILL_FAILED_ORDERS_KEY, "123");
        verifyNoInteractions(retries);
    }

    @Test
    void transientFailureRetainsTaskThenRetriesSameOrderIdAndClearsIt() {
        when(transactions.createVoucherOrder(10L, 20L, 123L))
                .thenThrow(new IllegalStateException("数据库暂不可用"))
                .thenReturn(Result.ok("123"));
        process(1);
        JSONObject saved = recordedFailure();
        assertEquals(Long.valueOf(123L), saved.getLong("orderId"));
        assertEquals(Long.valueOf(20L), saved.getLong("userId"));
        assertEquals(Long.valueOf(10L), saved.getLong("voucherId"));
        assertEquals("RETRY_PENDING", saved.getStr("status"));
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(retries).schedule(action.capture(), eq(1L), eq(TimeUnit.SECONDS));
        action.getValue().run();
        verify(transactions, times(2)).createVoucherOrder(10L, 20L, 123L);
        verify(failures).delete(RedisConstants.SECKILL_FAILED_ORDERS_KEY, "123");
    }

    @Test
    void thirdFailedAttemptIsRetainedForManualReviewWithoutAnotherRetry() {
        when(transactions.createVoucherOrder(10L, 20L, 123L)).thenThrow(new IllegalStateException("持续故障"));
        process(3);
        JSONObject saved = recordedFailure();
        assertEquals("MANUAL_REVIEW", saved.getStr("status"));
        assertEquals(Integer.valueOf(3), saved.getInt("attempts"));
        verify(retries, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void businessRejectionIsRecordedWithoutAutomaticRetry() {
        when(transactions.createVoucherOrder(10L, 20L, 123L)).thenReturn(Result.fail("库存不足"));
        process(1);
        JSONObject saved = recordedFailure();
        assertEquals("MANUAL_REVIEW", saved.getStr("status"));
        assertEquals("库存不足", saved.getStr("reason"));
        verifyNoInteractions(retries);
    }

    @Test
    void redisFailureWhileRecordingDoesNotPreventRetry() {
        when(transactions.createVoucherOrder(10L, 20L, 123L)).thenThrow(new IllegalStateException("数据库故障"));
        doThrow(new IllegalStateException("Redis 故障")).when(failures).put(anyString(), any(), any());
        process(1);
        verify(retries).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.SECONDS));
    }

    @Test
    void failureRecordCleanupErrorDoesNotRetryCommittedOrder() {
        when(transactions.createVoucherOrder(10L, 20L, 123L)).thenReturn(Result.ok("123"));
        when(failures.delete(RedisConstants.SECKILL_FAILED_ORDERS_KEY, "123"))
                .thenThrow(new IllegalStateException("Redis 故障"));
        process(1);
        verifyNoInteractions(retries);
        verify(failures, never()).put(anyString(), any(), any());
    }

    @Test
    void stoppingSchedulerRetainsTaskForManualReview() {
        when(transactions.createVoucherOrder(10L, 20L, 123L)).thenThrow(new IllegalStateException("数据库故障"));
        when(retries.isShutdown()).thenReturn(true);
        process(1);
        assertEquals("MANUAL_REVIEW", recordedFailure().getStr("status"));
        verify(retries, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void schedulerRejectionUpdatesRecordToManualReview() {
        when(transactions.createVoucherOrder(10L, 20L, 123L)).thenThrow(new IllegalStateException("数据库故障"));
        when(retries.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RejectedExecutionException("已关闭"));
        process(1);
        ArgumentCaptor<Object> records = ArgumentCaptor.forClass(Object.class);
        verify(failures, times(2)).put(eq(RedisConstants.SECKILL_FAILED_ORDERS_KEY), eq("123"), records.capture());
        assertEquals("MANUAL_REVIEW", JSONUtil.parseObj(records.getAllValues().get(1).toString()).getStr("status"));
    }
}
