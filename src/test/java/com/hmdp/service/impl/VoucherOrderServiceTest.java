package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证入口的异步提交分支；Redis 脚本由独立的真实 Redis 测试覆盖。 */
@ExtendWith(MockitoExtension.class)
class VoucherOrderServiceTest {
    @Mock private ISeckillVoucherService vouchers;
    @Mock private RedissonClient redisson;
    @Mock private RLock lock;
    @Mock private SeckillOrderPublisher publisher;
    @Mock private SeckillQualificationService qualifications;
    @Mock private RedisIdWorker ids;

    private static final long ORDER_ID = 9007199254740993L;
    private VoucherOrderServiceImpl service;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-16T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
        now = LocalDateTime.now(clock);
        service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "clock", clock);
        ReflectionTestUtils.setField(service, "seckillVoucherService", vouchers);
        ReflectionTestUtils.setField(service, "redissonClient", redisson);
        ReflectionTestUtils.setField(service, "seckillOrderPublisher", publisher);
        ReflectionTestUtils.setField(service, "seckillQualificationService", qualifications);
        ReflectionTestUtils.setField(service, "redisIdWorker", ids);
        UserDTO user = new UserDTO();
        user.setId(20L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void cleanIdentity() {
        UserHolder.removeUser();
    }

    private SeckillVoucher voucher(LocalDateTime begin, LocalDateTime end) {
        return new SeckillVoucher().setVoucherId(10L).setStock(5).setBeginTime(begin).setEndTime(end);
    }

    private void stubActiveVoucher() {
        when(vouchers.getById(10L)).thenReturn(voucher(now, now.plusHours(1)));
    }

    private void stubLock() {
        when(redisson.getLock(RedisConstants.LOCK_ORDER_KEY + "20:10")).thenReturn(lock);
    }

    private void stubAcquiredLock() {
        stubActiveVoucher();
        stubLock();
        when(lock.tryLock()).thenReturn(true);
    }

    private void stubPreparedOrder() {
        stubAcquiredLock();
        when(ids.nextId(RedisConstants.SECKILL_ORDER)).thenReturn(ORDER_ID);
    }

    private void stubRejectedSubmission() {
        stubPreparedOrder();
        when(qualifications.checkAndReserve(10L, 20L)).thenReturn(0L);
        when(publisher.publish(any(VoucherOrder.class))).thenReturn(SeckillOrderPublisher.PublishResult.NOT_ROUTED);
    }

    @Test
    void missingVoucherDoesNotAcquireLock() {
        Result result = service.seckillVoucher(10L);
        assertFalse(result.getSuccess());
        assertEquals("秒杀券不存在", result.getErrorMsg());
        verifyNoInteractions(redisson, publisher, qualifications, ids);
    }

    @Test
    void rejectsBeforeStart() {
        when(vouchers.getById(10L)).thenReturn(voucher(now.plusSeconds(1), now.plusHours(1)));
        assertEquals("秒杀还未开始", service.seckillVoucher(10L).getErrorMsg());
        verifyNoInteractions(redisson, publisher, qualifications, ids);
    }

    @Test
    void rejectsExactlyAtEnd() {
        when(vouchers.getById(10L)).thenReturn(voucher(now.minusHours(1), now));
        assertEquals("秒杀已经结束", service.seckillVoucher(10L).getErrorMsg());
        verifyNoInteractions(redisson, publisher, qualifications, ids);
    }

    @Test
    void rejectsAfterEnd() {
        when(vouchers.getById(10L)).thenReturn(voucher(now.minusHours(1), now.minusSeconds(1)));
        assertEquals("秒杀已经结束", service.seckillVoucher(10L).getErrorMsg());
        verifyNoInteractions(redisson, publisher, qualifications, ids);
    }

    @Test
    void lockConflictDoesNotReserveOrUnlock() {
        stubActiveVoucher();
        stubLock();
        when(lock.tryLock()).thenReturn(false);
        assertEquals("请勿重复提交", service.seckillVoucher(10L).getErrorMsg());
        verifyNoInteractions(publisher, qualifications, ids);
        verify(lock, never()).unlock();
    }

    @Test
    void acceptsAtStartReturnsExactStringIdAndNeverReleasesAcceptedReservation() {
        stubPreparedOrder();
        when(qualifications.checkAndReserve(10L, 20L)).thenReturn(0L);
        when(publisher.publish(any(VoucherOrder.class))).thenReturn(SeckillOrderPublisher.PublishResult.CONFIRMED);

        Result result = service.seckillVoucher(10L);

        assertTrue(result.getSuccess());
        assertEquals(String.valueOf(ORDER_ID), result.getData());
        ArgumentCaptor<VoucherOrder> task = ArgumentCaptor.forClass(VoucherOrder.class);
        InOrder sequence = inOrder(ids, qualifications, publisher, lock);
        sequence.verify(ids).nextId(RedisConstants.SECKILL_ORDER);
        sequence.verify(qualifications).checkAndReserve(10L, 20L);
        sequence.verify(publisher).publish(task.capture());
        sequence.verify(lock).unlock();
        assertEquals(Long.valueOf(ORDER_ID), task.getValue().getId());
        assertEquals(Long.valueOf(10L), task.getValue().getVoucherId());
        assertEquals(Long.valueOf(20L), task.getValue().getUserId());
        verifyNoMoreInteractions(qualifications, publisher);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L, 5L, -1L, 99L})
    void unsuccessfulQualificationDoesNotSubmitOrRelease(long code) {
        stubPreparedOrder();
        when(qualifications.checkAndReserve(10L, 20L)).thenReturn(code);
        String expected = code == 1L ? "库存不足" : code == 2L ? "买过了"
                : code == 5L ? "库存未准备就绪" : "未知的资格校验结果";

        Result result = service.seckillVoucher(10L);

        assertFalse(result.getSuccess());
        assertEquals(expected, result.getErrorMsg());
        assertNull(result.getData());
        verifyNoInteractions(publisher);
        verify(qualifications).checkAndReserve(10L, 20L);
        verifyNoMoreInteractions(qualifications);
        verify(lock).unlock();
    }

    @Test
    void unroutableMessageReleasesOnceAndReturnsFailureInsteadOfCreatingOrder() {
        stubRejectedSubmission();
        when(qualifications.releaseReservation(10L, 20L)).thenReturn(0L);

        Result result = service.seckillVoucher(10L);

        assertFalse(result.getSuccess());
        assertEquals("下单消息未提交，请稍后重试", result.getErrorMsg());
        assertNull(result.getData());
        InOrder sequence = inOrder(qualifications, publisher, lock);
        sequence.verify(qualifications).checkAndReserve(10L, 20L);
        sequence.verify(publisher).publish(any(VoucherOrder.class));
        sequence.verify(qualifications).releaseReservation(10L, 20L);
        sequence.verify(publisher).clearPending(ORDER_ID);
        sequence.verify(lock).unlock();
        verifyNoMoreInteractions(qualifications, publisher);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 5L, 99L})
    void unsuccessfulReleaseReturnsRecoveryError(long code) {
        stubRejectedSubmission();
        when(qualifications.releaseReservation(10L, 20L)).thenReturn(code);

        Result result = service.seckillVoucher(10L);

        assertFalse(result.getSuccess());
        assertEquals("预扣恢复异常", result.getErrorMsg());
        assertNull(result.getData());
        verify(qualifications).releaseReservation(10L, 20L);
        verify(lock).unlock();
    }

    @Test
    void releaseExceptionPropagatesAndStillUnlocks() {
        stubRejectedSubmission();
        IllegalStateException failure = new IllegalStateException("Redis 回补失败");
        when(qualifications.releaseReservation(10L, 20L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.seckillVoucher(10L)));
        verify(qualifications).releaseReservation(10L, 20L);
        verify(lock).unlock();
    }

    @Test
    void unsentMessageReleasesReservationAndAllowsRetry() {
        stubPreparedOrder();
        when(qualifications.checkAndReserve(10L, 20L)).thenReturn(0L);
        when(publisher.publish(any(VoucherOrder.class))).thenReturn(SeckillOrderPublisher.PublishResult.NOT_SENT);
        when(qualifications.releaseReservation(10L, 20L)).thenReturn(0L);

        Result result = service.seckillVoucher(10L);

        assertFalse(result.getSuccess());
        assertNull(result.getData());
        verify(qualifications).releaseReservation(10L, 20L);
        verify(publisher).clearPending(ORDER_ID);
        verify(lock).unlock();
    }

    @Test
    void ambiguousPublishKeepsReservationAndReturnsOriginalIdForReconciliation() {
        stubPreparedOrder();
        when(qualifications.checkAndReserve(10L, 20L)).thenReturn(0L);
        when(publisher.publish(any(VoucherOrder.class))).thenReturn(SeckillOrderPublisher.PublishResult.UNKNOWN);

        Result result = service.seckillVoucher(10L);

        assertFalse(result.getSuccess());
        assertEquals("下单结果确认中，请勿重复提交", result.getErrorMsg());
        assertEquals(String.valueOf(ORDER_ID), result.getData());
        verify(qualifications, never()).releaseReservation(anyLong(), anyLong());
        verify(publisher, never()).clearPending(anyLong());
        verify(lock).unlock();
    }

    @Test
    void qualificationExceptionDoesNotSubmitAndStillUnlocks() {
        stubPreparedOrder();
        IllegalStateException failure = new IllegalStateException("Redis 预扣失败");
        when(qualifications.checkAndReserve(10L, 20L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.seckillVoucher(10L)));
        verifyNoInteractions(publisher);
        verify(qualifications).checkAndReserve(10L, 20L);
        verifyNoMoreInteractions(qualifications);
        verify(lock).unlock();
    }

    @Test
    void idGenerationFailureHappensBeforeReservationAndStillUnlocks() {
        stubAcquiredLock();
        when(ids.nextId(RedisConstants.SECKILL_ORDER)).thenThrow(new IllegalStateException("生成订单号失败"));

        assertThrows(IllegalStateException.class, () -> service.seckillVoucher(10L));
        verifyNoInteractions(qualifications, publisher);
        verify(lock).unlock();
    }
}
