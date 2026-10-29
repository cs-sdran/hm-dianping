package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    //1位符号位+31位时间戳+32位序列号
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final Long NOW_TIME =1792040399L;

    public long nextId(String key_prefix)
    {
        //生成时间戳
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long epochSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp=epochSecond-NOW_TIME;

        //生成序列号
        String format = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Long increment = stringRedisTemplate.opsForValue().increment("icr:" + key_prefix + ":" + format);

        //拼接

        return timestamp<<32|increment;
    }
}
