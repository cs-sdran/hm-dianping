package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit timeUnit)
    {
        String jsonStr = JSONUtil.toJsonStr(value);//转化为json对象
        stringRedisTemplate.opsForValue().set(key,jsonStr,time,timeUnit);
    }

    //方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicTime(String key,Object value,Long time,TimeUnit timeUnit)
    {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    //方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,
                                         Long time , TimeUnit timeUnit)
    {
        //R返回值类型，即为查询返回的对象类型
        //ID 查询值
        //class<R> type 传入返回值类型
        //数据库查询函数 dbFallback
        //time 正常查询时的ttl
        //timeUnit 时间单位
        String key=keyPrefix+id;
        String s = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(s)) //查询成功，直接反序列化后返回
        {
           return JSONUtil.toBean(s, type);
        }
        //判断是否为空值，若不是空值，则为“”空白缓存
        if(s!=null)
        {
            return null;
        }
        //若未查询成功，则查询数据库
        R r = dbFallback.apply(id);
        if(r==null)//未命中，缓存空值
        {
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //命中，写入缓存并返回
        this.set(key,r,time,timeUnit);

        return r;
    }



    //线程池
    private  static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //互斥锁
    public boolean trylock(String key)
    {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",10,TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }
    public void unlock(String key)
    {
        stringRedisTemplate.delete(key);
    }



    //方法4：根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
    public <R,ID> R queryWithLogicExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,
                                       Long time,TimeUnit timeUnit)
    {
        //1. 如果未逻辑过期，直接返回数据
        String key=keyPrefix+id;
        String s = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(s))//如果为空
        {
            return null;
        }
        RedisData bean = JSONUtil.toBean(s, RedisData.class);
        LocalDateTime expireTime = bean.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) bean.getData(), type);
        if(LocalDateTime.now().isAfter(expireTime))//未过期，直接返回
        {

            return r;
        }

        //2. 如果已逻辑过期，也先返回旧数据


        //3. 同时尝试获取互斥锁

        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;

        boolean isLock=trylock(lockKey);
        //4. 获取锁成功的线程，异步重建缓存

        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R newData = dbFallback.apply(id);

                    // 重建缓存
                    this.setWithLogicTime(key, newData, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        //5. 其他线程直接返回旧数据

        return r;
    }

}
