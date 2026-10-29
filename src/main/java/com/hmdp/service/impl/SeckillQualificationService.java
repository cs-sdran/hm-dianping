package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.utils.RedisConstants;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.PathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;

@Service
public class SeckillQualificationService extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> {
    //确认用户是否购买过秒杀券的类

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> script;
    static {
        script=new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill.lua"));
        script.setResultType(Long.class);
    }

    private static final DefaultRedisScript<Long> rollbackscript;
    static
    {
        rollbackscript=new DefaultRedisScript<>();
        rollbackscript.setLocation(new ClassPathResource("seckill_rollback.lua"));
        rollbackscript.setResultType(Long.class);
    }

    public long checkAndReserve(Long voucherId,Long userId)
    {
        //keys1 库存key  keys2订单key argv1 用户Id
        List<String> list = Arrays.asList(RedisConstants.SECKILL_STOCK_KEY + voucherId, RedisConstants.SECKILL_ORDER_RECORD + voucherId);

        return stringRedisTemplate.execute(script, list, userId.toString());


    }
    public long releaseReservation(Long voucherId,Long userId)
    {
        List<String> list = Arrays.asList(RedisConstants.SECKILL_STOCK_KEY + voucherId, RedisConstants.SECKILL_ORDER_RECORD + voucherId);

        return stringRedisTemplate.execute(rollbackscript,list,userId.toString());
    }

    public boolean initializeStock(long voucherId,int stock)
    {
        if(stock<0)
            throw new IllegalArgumentException("不能为负数");

        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(RedisConstants.SECKILL_STOCK_KEY + voucherId, String.valueOf(stock));

        return Boolean.TRUE.equals(b);
    }
}
