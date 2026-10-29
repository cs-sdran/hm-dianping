package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private SeckillOrderPublisher seckillOrderPublisher;

    @Resource
    private SeckillQualificationService seckillQualificationService;

    @Resource
    private RedisIdWorker redisIdWorker;

    // 使用同一个时间点判断活动窗口；测试时可固定时钟验证结束边界。
    private Clock clock = Clock.systemDefaultZone();

    @Override
    public Result seckillVoucher(Long voucherId) {
        //根据优惠券id查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if(voucher==null)
        {return Result.fail("秒杀券不存在");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 活动窗口为 [beginTime, endTime)。
        if(now.isBefore(voucher.getBeginTime()))
        {
            return Result.fail("秒杀还未开始");
        }
        //判断秒杀是否结束
        if(!now.isBefore(voucher.getEndTime()))
        {
            return Result.fail("秒杀已经结束");
        }


        //对这次请求进行加锁
        Long userId = UserHolder.getUser().getId();
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId + ":" + voucherId);
        boolean acquired = lock.tryLock();
        if(!acquired)//未获得锁
        {
            return Result.fail("请勿重复提交");
        }
        try {

            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setId(redisIdWorker.nextId(RedisConstants.SECKILL_ORDER));
            voucherOrder.setUserId(userId);
            long choose = seckillQualificationService.checkAndReserve(voucherId, userId);
            if(choose==1)
                return Result.fail("库存不足");
            if(choose==2)
                return Result.fail("买过了");
            if(choose==5)
                return Result.fail("库存未准备就绪");
            if(choose==0) {
                SeckillOrderPublisher.PublishResult published = seckillOrderPublisher.publish(voucherOrder);
                if(published == SeckillOrderPublisher.PublishResult.CONFIRMED)
                    // RabbitMQ 已确认并完成路由；数据库订单仍由消费者异步创建。
                    return Result.ok(String.valueOf(voucherOrder.getId()));
                if(published == SeckillOrderPublisher.PublishResult.NOT_SENT
                        || published == SeckillOrderPublisher.PublishResult.NOT_ROUTED) {
                    long releaseResult = seckillQualificationService.releaseReservation(voucherId, userId);
                    if(releaseResult == 0) {
                        seckillOrderPublisher.clearPending(voucherOrder.getId());
                        return Result.fail("下单消息未提交，请稍后重试");
                    }
                    log.error("预扣恢复异常，voucherId={}, userId={}, orderId={}, releaseResult={}",
                            voucherId, userId, voucherOrder.getId(), releaseResult);
                    return Result.fail("预扣恢复异常");
                }
                // 超时和断连可能发生在 Broker 已接收后；保留预扣及待核对记录。
                return new Result(false, "下单结果确认中，请勿重复提交", String.valueOf(voucherOrder.getId()), null);
            }
            return Result.fail("未知的资格校验结果");
        }
        finally {
            lock.unlock();
        }

    }
}
