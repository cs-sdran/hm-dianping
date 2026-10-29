package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Resource
    private SeckillQualificationService seckillQualificationService;

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 在写库之前校验，不能等事务提交后初始化 Redis 时才发现非法库存。
        if (voucher.getStock() == null || voucher.getStock() < 0) {
            throw new IllegalArgumentException("库存不能为空或负数");
        }
        // 保存优惠券
        if (!save(voucher)) {
            throw new IllegalStateException("保存优惠券失败");
        }
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new IllegalStateException("保存秒杀信息失败");
        }

        long voucherId = voucher.getId();
        int stock = voucher.getStock();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 此时数据库已提交；初始化失败必须报错，但不能回滚已提交的数据。
                boolean initialized = seckillQualificationService.initializeStock(voucherId, stock);
                if (!initialized) {
                    throw new IllegalStateException("库存初始化未完成");
                }
            }
        });
    }
}
