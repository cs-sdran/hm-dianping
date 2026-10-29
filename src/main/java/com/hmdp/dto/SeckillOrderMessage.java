package com.hmdp.dto;

import lombok.Data;

@Data
public class SeckillOrderMessage {

    private long orderId;

    private long userId;

    private long seckillId;
}
