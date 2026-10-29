package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** 通用缓存工具：空值缓存、互斥重建、逻辑过期；数据库查询由调用方传入。 */
@Component
public class CacheClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CacheClient.class);
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>();
    static {
        UNLOCK.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final ExecutorService rebuilds = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate redis, Clock businessClock) {
        this.redis = redis;
        this.clock = businessClock;
    }

    public void set(String key, Object value, long time, TimeUnit unit) {
        redis.opsForValue().set(key, value == null ? "" : JSONUtil.toJsonStr(value),
                value == null ? RedisConstants.CACHE_NULL_TTL : time,
                value == null ? TimeUnit.MINUTES : unit);
    }

    public void setWithLogicalExpire(String key, Object value, long time, TimeUnit unit) {
        if (value == null) {
            set(key, null, time, unit);
            return;
        }
        RedisData data = new RedisData();
        data.setData(value);
        data.setExpireTime(LocalDateTime.now(clock).plusNanos(unit.toNanos(time)));
        redis.opsForValue().set(key, JSONUtil.toJsonStr(data));
    }

    public <R, ID> R queryWithPassThrough(String prefix, ID id, Class<R> type,
                                         Function<ID, R> database, long time, TimeUnit unit) {
        String key = prefix + id;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : JSONUtil.toBean(cached, type);
        }
        R value = database.apply(id);
        set(key, value, time, unit);
        return value;
    }

    public <R, ID> R queryWithMutex(String prefix, ID id, Class<R> type, String lockPrefix,
                                    Function<ID, R> database, long time, TimeUnit unit) {
        return queryOnMiss(prefix + id, lockPrefix + id, id, type, database, time, unit, false);
    }

    public <R, ID> R queryWithLogicalExpire(String prefix, ID id, Class<R> type, String lockPrefix,
                                            Function<ID, R> database, long time, TimeUnit unit) {
        String key = prefix + id;
        String lockKey = lockPrefix + id;
        String cached = redis.opsForValue().get(key);
        if (cached == null) {
            return queryOnMiss(key, lockKey, id, type, database, time, unit, true);
        }
        if (cached.isEmpty()) {
            return null;
        }
        RedisData data = JSONUtil.toBean(cached, RedisData.class);
        R oldValue = fromData(data, type);
        if (data.getExpireTime().isAfter(LocalDateTime.now(clock))) {
            return oldValue;
        }

        String token = UUID.randomUUID().toString(true);
        if (!tryLock(lockKey, token)) {
            return oldValue;
        }
        boolean submitted = false;
        try {
            // 持锁后再次读取：其他请求可能已刷新或者删除缓存。
            cached = redis.opsForValue().get(key);
            if (cached == null) {
                return load(key, id, database, time, unit, true);
            }
            if (cached.isEmpty()) {
                return null;
            }
            data = JSONUtil.toBean(cached, RedisData.class);
            R current = fromData(data, type);
            if (data.getExpireTime().isAfter(LocalDateTime.now(clock))) {
                return current;
            }
            rebuilds.submit(() -> {
                try {
                    load(key, id, database, time, unit, true);
                } catch (Exception failure) {
                    log.error("异步缓存重建失败，key：{}", key, failure);
                } finally {
                    unlock(lockKey, token);
                }
            });
            submitted = true;
            return current;
        } finally {
            if (!submitted) {
                unlock(lockKey, token);
            }
        }
    }

    private <R, ID> R queryOnMiss(String key, String lockKey, ID id, Class<R> type,
                                  Function<ID, R> database, long time, TimeUnit unit, boolean logical) {
        String token = UUID.randomUUID().toString(true);
        for (int attempt = 0; attempt < 50; attempt++) {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                if (cached.isEmpty()) {
                    return null;
                }
                return logical ? fromData(JSONUtil.toBean(cached, RedisData.class), type)
                        : JSONUtil.toBean(cached, type);
            }
            if (tryLock(lockKey, token)) {
                try {
                    cached = redis.opsForValue().get(key);
                    if (cached != null) {
                        if (cached.isEmpty()) {
                            return null;
                        }
                        return logical ? fromData(JSONUtil.toBean(cached, RedisData.class), type)
                                : JSONUtil.toBean(cached, type);
                    }
                    return load(key, id, database, time, unit, logical);
                } finally {
                    unlock(lockKey, token);
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待缓存重建时被中断", interrupted);
            }
        }
        throw new IllegalStateException("缓存重建超时：" + key);
    }

    private <R, ID> R load(String key, ID id, Function<ID, R> database,
                            long time, TimeUnit unit, boolean logical) {
        R value = database.apply(id);
        if (logical) {
            setWithLogicalExpire(key, value, time, unit);
        } else {
            set(key, value, time, unit);
        }
        return value;
    }

    private <R> R fromData(RedisData data, Class<R> type) {
        return JSONUtil.toBean(JSONUtil.toJsonStr(data.getData()), type);
    }

    public boolean tryLock(String key, String token) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                key, token, RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS));
    }

    public void unlock(String key, String token) {
        redis.execute(UNLOCK, Collections.singletonList(key), token);
    }

    @PreDestroy
    @Override
    public void close() {
        rebuilds.shutdownNow();
        try {
            if (!rebuilds.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("缓存重建线程池未在关闭等待时间内结束");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
