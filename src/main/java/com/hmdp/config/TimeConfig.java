package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** 签到和逻辑过期统一使用北京时间；测试可以替换 Clock 验证月末等边界。 */
@Configuration
public class TimeConfig {
    @Bean
    public Clock businessClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
