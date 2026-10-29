package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties)
    {
        Config config = new Config();
        // 与 StringRedisTemplate 使用相同连接配置，双实例测试也可切换独立 Redis 库。
        SingleServerConfig server = config.useSingleServer()
                .setAddress((redisProperties.isSsl() ? "rediss://" : "redis://")
                        + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase());
        if (StringUtils.hasText(redisProperties.getPassword())) {
            server.setPassword(redisProperties.getPassword());
        }
        RedissonClient redissonClient = Redisson.create(config);
        return redissonClient;
    }
}
