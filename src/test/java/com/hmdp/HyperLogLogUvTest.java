package com.hmdp;

import com.hmdp.support.IsolatedRedis;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UV 学习实验：运行本类即可，不需要启动 Spring Boot 或连接 MySQL。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HyperLogLogUvTest {
    private IsolatedRedis instance;
    private StringRedisTemplate redis;

    @BeforeAll
    void start() throws Exception {
        // 复用项目测试工具：启动临时 Redis，避免修改开发环境的数据。
        instance = new IsolatedRedis();
        redis = instance.template();
    }

    @AfterAll
    void stop() throws Exception {
        if (instance != null) {
            instance.close();
        }
    }

    @BeforeEach
    void resetExperiment() {
        // clear 只清理本测试工具启动的 Redis。
        instance.clear();
    }

    @Test
    void testUv() {
        String key = "test:uv:java:1";

        // add 对应 PFADD，记录访客；size 对应 PFCOUNT，查询独立访客数。
        redis.opsForHyperLogLog().add(key, "userA", "userB", "userC");
        Long firstCount = redis.opsForHyperLogLog().size(key);
        System.out.println("UV after A, B, C: " + firstCount);
        assertEquals(Long.valueOf(3), firstCount);

        // 相同访客再次访问，人数不变。add 的返回值不是 UV 人数。
        redis.opsForHyperLogLog().add(key, "userA");
        Long repeatCount = redis.opsForHyperLogLog().size(key);
        System.out.println("UV after A visits again: " + repeatCount);
        assertEquals(Long.valueOf(3), repeatCount);

        redis.opsForHyperLogLog().add(key, "userD");
        Long finalCount = redis.opsForHyperLogLog().size(key);
        System.out.println("UV after D: " + finalCount);
        assertEquals(Long.valueOf(4), finalCount);
    }

    @Test
    void missingKeyHasZeroVisitors() {
        assertEquals(Long.valueOf(0), redis.opsForHyperLogLog().size("test:uv:missing"));
    }

    @Test
    void differentDaysUseDifferentKeys() {
        LocalDate day = LocalDate.of(2026, 10, 24);
        String todayKey = dailyKey(day);
        String tomorrowKey = dailyKey(day.plusDays(1));

        redis.opsForHyperLogLog().add(todayKey, "userA", "userB", "userA");
        redis.opsForHyperLogLog().add(tomorrowKey, "userA");

        // A 在同一天重复访问只计一次，在另一天访问则计入另一天。
        assertEquals(Long.valueOf(2), redis.opsForHyperLogLog().size(todayKey));
        assertEquals(Long.valueOf(1), redis.opsForHyperLogLog().size(tomorrowKey));
    }

    @Test
    void oneMillionVisitorsAreWrittenInBatches() {
        String key = "test:uv:million";
        int total = 1_000_000;
        int batchSize = 1_000;
        String[] visitors = new String[batchSize];
        long start = System.nanoTime();

        for (int i = 0; i < total; i++) {
            // 复用数组，每填满 1000 个访客发送一次，避免发送一百万次请求。
            visitors[i % batchSize] = "user_" + i;
            if ((i + 1) % batchSize == 0) {
                redis.opsForHyperLogLog().add(key, visitors);
            }
        }

        long estimated = redis.opsForHyperLogLog().size(key);
        double error = Math.abs(estimated - total) / (double) total;
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf(Locale.ROOT,
                "UV experiment: actual=%d, estimated=%d, error=%.4f%%, elapsed=%dms%n",
                total, estimated, error * 100, elapsedMillis);

        // HyperLogLog 是近似计数，不要求结果恰好等于一百万。
        // 3% 是本实验的回归检查阈值，不代表算法对所有数据的误差保证。
        assertTrue(error < 0.03, "UV estimate differs from the known population by more than 3%");

        // 数组里仍是最后一批访客，再次添加它们不应改变结果。
        redis.opsForHyperLogLog().add(key, visitors);
        assertEquals(Long.valueOf(estimated), redis.opsForHyperLogLog().size(key));
    }

    private String dailyKey(LocalDate date) {
        return "test:uv:daily:" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
