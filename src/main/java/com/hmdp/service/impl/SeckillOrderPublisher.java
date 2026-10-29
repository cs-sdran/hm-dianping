package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.RabbitMqConfig;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 发送订单并等待 Broker 确认；数据库写入仍由消费者异步执行。 */
@Service
@Slf4j
public class SeckillOrderPublisher {
    public enum PublishResult {
        CONFIRMED, NOT_SENT, NOT_ROUTED, UNKNOWN
    }

    @Resource private RabbitTemplate rabbitTemplate;
    @Resource private StringRedisTemplate stringRedisTemplate;

    private String exchange = RabbitMqConfig.ORDER_EXCHANGE;
    private String routingKey = RabbitMqConfig.ORDER_ROUTING_KEY;
    private long confirmTimeoutMillis = 5000;

    public PublishResult publish(VoucherOrder order) {
        // 发送前留存完整订单。超时、断连或进程在发送后退出时，仍有记录可核对。
        try {
            record(order, "PUBLISH_PENDING", "等待 RabbitMQ 确认");
        } catch (Exception failure) {
            log.error("发送记录保存失败，本次未发送，orderId={}", order.getId(), failure);
            return PublishResult.NOT_SENT;
        }

        SeckillOrderMessage message = new SeckillOrderMessage();
        message.setOrderId(order.getId());
        message.setUserId(order.getUserId());
        message.setSeckillId(order.getVoucherId());
        // 关联 ID 每次发送都不同；业务订单号保持不变，供消费端幂等处理。
        CorrelationData correlation = new CorrelationData(order.getId() + ":" + UUID.randomUUID());
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, message, amqpMessage -> {
                amqpMessage.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                amqpMessage.getMessageProperties().setMessageId(order.getId().toString());
                return amqpMessage;
            }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            // mandatory 退回先于确认 Future 完成，单独检查 ACK 不能证明消息已进入队列。
            if (correlation.getReturnedMessage() != null) {
                recordSafely(order, "PUBLISH_RETURNED", "消息未路由到队列");
                return PublishResult.NOT_ROUTED;
            }
            if (!confirm.isAck()) {
                recordSafely(order, "PUBLISH_UNKNOWN", "收到 NACK：" + confirm.getReason());
                return PublishResult.UNKNOWN;
            }
            clearPending(order.getId());
            return PublishResult.CONFIRMED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            recordSafely(order, "PUBLISH_UNKNOWN", "等待确认被中断");
            return PublishResult.UNKNOWN;
        } catch (Exception failure) {
            // 发送异常或确认超时，不等于 Broker 没收到，不能自动释放购买资格。
            recordSafely(order, "PUBLISH_UNKNOWN", failure.toString());
            return PublishResult.UNKNOWN;
        }
    }

    public void clearPending(Long orderId) {
        try {
            stringRedisTemplate.opsForHash().delete(RedisConstants.SECKILL_PENDING_PUBLISH_KEY, orderId.toString());
        } catch (Exception failure) {
            // 已收到 Broker 确认或已回补的结果，不因清理记录失败而改变。
            log.error("发送待核对记录未清理，orderId={}", orderId, failure);
        }
    }

    private void record(VoucherOrder order, String status, String reason) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("orderId", order.getId());
        record.put("userId", order.getUserId());
        record.put("voucherId", order.getVoucherId());
        record.put("status", status);
        record.put("reason", reason);
        record.put("updatedAt", System.currentTimeMillis());
        stringRedisTemplate.opsForHash().put(RedisConstants.SECKILL_PENDING_PUBLISH_KEY,
                order.getId().toString(), JSONUtil.toJsonStr(record));
    }

    private void recordSafely(VoucherOrder order, String status, String reason) {
        log.error("订单消息待核对，orderId={}, userId={}, voucherId={}, status={}, reason={}",
                order.getId(), order.getUserId(), order.getVoucherId(), status, reason);
        try {
            record(order, status, reason);
        } catch (Exception failure) {
            // 最初的 PUBLISH_PENDING 记录保留；日志也带有完整定位信息。
            log.error("发送状态更新失败，orderId={}", order.getId(), failure);
        }
    }
}
