package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoucherOrderTxServiceTest {
    @Mock private VoucherOrderMapper mapper;
    @Mock private ISeckillVoucherService stocks;
    private VoucherOrderTxService service;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderTxService();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "seckillVoucherService", stocks);
    }

    @Test
    void alreadyCommittedSameOrderReturnsOriginalIdWithoutAnotherStockUpdate() {
        when(mapper.selectById(123L)).thenReturn(new VoucherOrder().setId(123L).setUserId(20L).setVoucherId(10L));
        Result result = service.createVoucherOrder(10L, 20L, 123L);
        assertTrue(result.getSuccess());
        assertEquals("123", result.getData());
        verify(mapper).selectById(123L);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(stocks);
    }

    @Test
    void collidingIdForDifferentUserCannotBeTreatedAsSuccess() {
        when(mapper.selectById(123L)).thenReturn(new VoucherOrder().setId(123L).setUserId(21L).setVoucherId(10L));
        assertThrows(IllegalStateException.class, () -> service.createVoucherOrder(10L, 20L, 123L));
        verify(mapper).selectById(123L);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(stocks);
    }

    @Test
    void collidingIdForDifferentVoucherCannotBeTreatedAsSuccess() {
        when(mapper.selectById(123L)).thenReturn(new VoucherOrder().setId(123L).setUserId(20L).setVoucherId(11L));
        assertThrows(IllegalStateException.class, () -> service.createVoucherOrder(10L, 20L, 123L));
        verify(mapper).selectById(123L);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(stocks);
    }
}
