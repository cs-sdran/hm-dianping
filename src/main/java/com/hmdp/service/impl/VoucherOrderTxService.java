package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

@Service
public class VoucherOrderTxService extends ServiceImpl<VoucherOrderMapper, VoucherOrder> {

        @Resource
        private RedisIdWorker redisIdWorker;

        @Resource
        private ISeckillVoucherService seckillVoucherService;



        //这个方法实现判断订单是否重复，并完成新建订单
        @Transactional
        public Result createVoucherOrder(long voucherId,long userId)
        {


            //生成订单号
            long orderId = redisIdWorker.nextId(RedisConstants.SECKILL_ORDER);
             return  createVoucherOrder(voucherId,userId,orderId);


        }

        @Transactional
        public Result createVoucherOrder(long voucherId,long userId,long orderId)
        {
            // 重试必须使用原订单号。提交成功但调用结果不确定时，重放不能再次扣库存。
            VoucherOrder existing = getById(orderId);
            if (existing != null) {
                if (Long.valueOf(voucherId).equals(existing.getVoucherId())
                        && Long.valueOf(userId).equals(existing.getUserId())) {
                    return Result.ok(String.valueOf(orderId));
                }
                throw new IllegalStateException("订单号已被其他订单使用");
            }
            //判断订单是否重复

            //根据用户Id和voucherid查询当前用户是否已经购买过当前秒杀券
            int count = query().eq("user_id", userId)
                    .eq("voucher_id", voucherId).count();
            if(count>0)
            {
                return Result.fail("该用户已经购买过该秒杀券");
            }

            //创建订单
            //判断库存
            boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock", 0).update();
            if(!success)//库存不足
            {
                return  Result.fail("库存不足");
            }
            //扣减库存成功，创建订单
            VoucherOrder voucherOrder1 = new VoucherOrder();
            voucherOrder1.setVoucherId(voucherId);
            voucherOrder1.setId(orderId);
            voucherOrder1.setUserId(userId);
            boolean save = save(voucherOrder1);
            if(!save)
            {
                throw new RuntimeException("保存订单失败");

            }
            return Result.ok(String.valueOf(orderId));

        }
}
