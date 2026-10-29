package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 真实 Spring 事务代理和回调，数据库 Connection/Mapper 与 Redis 服务使用模拟对象。
 * 验证提交、回滚、afterCommit 的顺序；不代表真实 MySQL 数据持久化测试。
 */
@ExtendWith(MockitoExtension.class)
class VoucherServiceTransactionTest {
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private VoucherMapper mapper;
    @Mock private ISeckillVoucherService seckillVouchers;
    @Mock private SeckillQualificationService qualifications;

    private IVoucherService service;
    private TransactionTemplate transactions;
    private Voucher voucher;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);

        VoucherServiceImpl target = new VoucherServiceImpl();
        ReflectionTestUtils.setField(target, "baseMapper", mapper);
        ReflectionTestUtils.setField(target, "seckillVoucherService", seckillVouchers);
        ReflectionTestUtils.setField(target, "seckillQualificationService", qualifications);
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (IVoucherService) factory.getProxy();

        voucher = new Voucher().setShopId(1L).setTitle("初始化测试券").setStock(5)
                .setBeginTime(LocalDateTime.of(2026, 10, 18, 10, 0))
                .setEndTime(LocalDateTime.of(2026, 10, 18, 11, 0));
    }

    private void stubVoucherSaved() {
        when(mapper.insert(any(Voucher.class))).thenAnswer(call -> {
            Voucher saved = call.getArgument(0);
            saved.setId(10L);
            return 1;
        });
    }

    private void stubBothSaved() {
        stubVoucherSaved();
        when(seckillVouchers.save(any(SeckillVoucher.class))).thenReturn(true);
    }

    @Test
    void initializesOnlyAfterOuterTransactionCommits() throws Exception {
        stubBothSaved();
        when(qualifications.initializeStock(10L, 5)).thenReturn(true);

        transactions.execute(status -> {
            service.addSeckillVoucher(voucher);
            verifyNoInteractions(qualifications);
            try {
                verify(connection, never()).commit();
            } catch (java.sql.SQLException failure) {
                throw new AssertionError(failure);
            }
            return null;
        });

        ArgumentCaptor<SeckillVoucher> details = ArgumentCaptor.forClass(SeckillVoucher.class);
        InOrder sequence = inOrder(mapper, seckillVouchers, connection, qualifications);
        sequence.verify(mapper).insert(voucher);
        sequence.verify(seckillVouchers).save(details.capture());
        sequence.verify(connection).commit();
        sequence.verify(qualifications).initializeStock(10L, 5);
        assertEquals(Long.valueOf(10L), details.getValue().getVoucherId());
        assertEquals(Integer.valueOf(5), details.getValue().getStock());
        assertEquals(voucher.getBeginTime(), details.getValue().getBeginTime());
        assertEquals(voucher.getEndTime(), details.getValue().getEndTime());
        verify(connection, never()).rollback();
        verify(connection).close();
        verifyNoMoreInteractions(qualifications);
    }

    @Test
    void outerRollbackNeverInitializesRedisEvenAfterBothSavesReturnSuccess() throws Exception {
        stubBothSaved();

        transactions.execute(status -> {
            service.addSeckillVoucher(voucher);
            verifyNoInteractions(qualifications);
            status.setRollbackOnly();
            return null;
        });

        verify(connection).rollback();
        verify(connection, never()).commit();
        verifyNoInteractions(qualifications);
    }

    @Test
    void firstSaveFalseStopsSecondSaveAndRollsBack() throws Exception {
        when(mapper.insert(any(Voucher.class))).thenReturn(0);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.addSeckillVoucher(voucher));

        assertEquals("保存优惠券失败", failure.getMessage());
        verifyNoInteractions(seckillVouchers, qualifications);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void secondSaveFalseRollsBackAndDoesNotInitialize() throws Exception {
        stubVoucherSaved();
        when(seckillVouchers.save(any(SeckillVoucher.class))).thenReturn(false);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.addSeckillVoucher(voucher));

        assertEquals("保存秒杀信息失败", failure.getMessage());
        verify(connection).rollback();
        verify(connection, never()).commit();
        verifyNoInteractions(qualifications);
    }

    @Test
    void firstSaveExceptionRollsBackWithoutSecondSave() throws Exception {
        IllegalStateException failure = new IllegalStateException("数据库插入失败");
        when(mapper.insert(any(Voucher.class))).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.addSeckillVoucher(voucher)));
        verifyNoInteractions(seckillVouchers, qualifications);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void secondSaveExceptionRollsBackWithoutInitializing() throws Exception {
        stubVoucherSaved();
        IllegalStateException failure = new IllegalStateException("秒杀信息插入失败");
        when(seckillVouchers.save(any(SeckillVoucher.class))).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.addSeckillVoucher(voucher)));
        verify(connection).rollback();
        verify(connection, never()).commit();
        verifyNoInteractions(qualifications);
    }

    @Test
    void falseInitializationReportsFailureAfterCommitWithoutRollingBack() throws Exception {
        stubBothSaved();
        when(qualifications.initializeStock(10L, 5)).thenReturn(false);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.addSeckillVoucher(voucher));

        assertEquals("库存初始化未完成", failure.getMessage());
        InOrder sequence = inOrder(connection, qualifications);
        sequence.verify(connection).commit();
        sequence.verify(qualifications).initializeStock(10L, 5);
        verify(connection, never()).rollback();
        verify(connection).close();
    }

    @Test
    void initializationExceptionPropagatesAfterCommitWithoutRollingBack() throws Exception {
        stubBothSaved();
        IllegalStateException failure = new IllegalStateException("Redis 连接失败");
        when(qualifications.initializeStock(10L, 5)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.addSeckillVoucher(voucher)));
        verify(connection).commit();
        verify(connection, never()).rollback();
        verify(connection).close();
    }

    @Test
    void negativeStockIsRejectedBeforeAnyInsert() throws Exception {
        voucher.setStock(-1);
        assertThrows(IllegalArgumentException.class, () -> service.addSeckillVoucher(voucher));
        verifyNoInteractions(mapper, seckillVouchers, qualifications);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void nullStockIsRejectedBeforeAnyInsert() throws Exception {
        voucher.setStock(null);
        assertThrows(IllegalArgumentException.class, () -> service.addSeckillVoucher(voucher));
        verifyNoInteractions(mapper, seckillVouchers, qualifications);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }
}
