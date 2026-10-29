package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 旧内存队列练习保留供对照，已退出 Spring 容器，不再启动后台线程。 */
@Deprecated
@Slf4j
public class VoucherOrderAsyncService {
    //新建一个阻塞队列
    @Resource
    private VoucherOrderTxService voucherOrderTxService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final int MAX_ATTEMPTS = 3;

    private final   BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024);

    private final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor();

    public boolean submit(VoucherOrder order)//下单方法
    {
        boolean offer = orderTasks.offer(order);
        return offer;
    }
    private void processNextOrder() throws InterruptedException {//取订单方法
        VoucherOrder take = orderTasks.take();
        processOrder(take, 1);
    }


    private void consumeOrders()//只要线程不被中断，就持续处理
    {
        while(!Thread.currentThread().isInterrupted())
        {
        try {
            processNextOrder();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return;

        }
        catch (Exception e)
        {
            log.error("异步下单异常",e);
        }
        }
    }

    private void processOrder(VoucherOrder order, int attempt) {
        Result result;
        try {
            result = voucherOrderTxService.createVoucherOrder(
                    order.getVoucherId(), order.getUserId(), order.getId());
            if (result == null) {
                throw new IllegalStateException("下单服务未返回处理结果");
            }
        } catch (Exception failure) {
            boolean retry = attempt < MAX_ATTEMPTS && !retryExecutor.isShutdown()
                    && !Thread.currentThread().isInterrupted();
            recordFailure(order, attempt, failure.toString(), retry);
            log.error("异步下单异常，orderId={}, userId={}, voucherId={}, attempt={}",
                    order.getId(), order.getUserId(), order.getVoucherId(), attempt, failure);
            if (retry) {
                try {
                    // 独立调度线程延迟重试，不阻塞新订单消费；始终沿用原订单号。
                    retryExecutor.schedule(() -> processOrder(order, attempt + 1), attempt, TimeUnit.SECONDS);
                } catch (RejectedExecutionException stopped) {
                    recordFailure(order, attempt, "重试线程已停止：" + failure, false);
                }
            }
            return;
        }

        if (!Boolean.TRUE.equals(result.getSuccess())) {
            // 库存不足、已有其他订单等业务失败不盲目重试或回补，保留任务供核对。
            recordFailure(order, attempt, result.getErrorMsg(), false);
            log.error("异步下单需核对，orderId={}, reason={}", order.getId(), result.getErrorMsg());
            return;
        }
        try {
            stringRedisTemplate.opsForHash().delete(
                    RedisConstants.SECKILL_FAILED_ORDERS_KEY, order.getId().toString());
        } catch (Exception cleanupFailure) {
            // 订单已提交，清理记录失败不能被当成订单失败，也不能释放其购买资格。
            log.error("订单已完成，失败记录清理未完成，orderId={}", order.getId(), cleanupFailure);
        }
    }

    private void recordFailure(VoucherOrder order, int attempt, String reason, boolean retry) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("orderId", order.getId());
        record.put("userId", order.getUserId());
        record.put("voucherId", order.getVoucherId());
        record.put("attempts", attempt);
        record.put("status", retry ? "RETRY_PENDING" : "MANUAL_REVIEW");
        record.put("reason", reason);
        record.put("updatedAt", System.currentTimeMillis());
        try {
            stringRedisTemplate.opsForHash().put(RedisConstants.SECKILL_FAILED_ORDERS_KEY,
                    order.getId().toString(), JSONUtil.toJsonStr(record));
        } catch (Exception storeFailure) {
            // Redis 故障时仍继续本进程的有限重试，并把完整任务信息留在错误日志中。
            log.error("失败任务写入 Redis 未完成，task={}", JSONUtil.toJsonStr(record), storeFailure);
        }
    }

    @PostConstruct
    public void init()
    {
        EXECUTOR_SERVICE.submit(this::consumeOrders);
    }
    @PreDestroy
    public void destory()
    {
        retryExecutor.shutdownNow();
        EXECUTOR_SERVICE.shutdownNow();
    }
}
