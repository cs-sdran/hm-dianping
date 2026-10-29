package com.hmdp.consumer;


import com.hmdp.dto.Result;
import com.hmdp.config.RabbitMqConfig;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.service.impl.VoucherOrderTxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;


@Component
@Slf4j
public class SeckillOrderConsumer {

    @Resource
    private VoucherOrderTxService voucherOrderTxService;

    @RabbitListener(queues = RabbitMqConfig.ORDER_QUEUE)
    public void receive(SeckillOrderMessage seckillOrderMessage){
        long seckillId = seckillOrderMessage.getSeckillId();
        long orderId = seckillOrderMessage.getOrderId();
        long userId = seckillOrderMessage.getUserId();

        //拿到订单信息后开始创建订单
        Result voucherOrder = voucherOrderTxService.createVoucherOrder(seckillId, userId, orderId);
        if (voucherOrder == null) {
            throw new IllegalStateException("下单服务未返回处理结果，orderId=" + orderId);
        }
        if (!Boolean.TRUE.equals(voucherOrder.getSuccess())) {
            // 抛给 Spring 的监听容器，由已配置的有限重试及死信机制接手。
            throw new IllegalStateException("下单失败，orderId=" + orderId
                    + "，原因：" + voucherOrder.getErrorMsg());
        }

        // 事务方法成功返回后正常结束，交由 Spring AUTO 模式确认消息。
        log.info("订单创建成功，orderId={}, userId={}, voucherId={}", orderId, userId, seckillId);
    }
}
