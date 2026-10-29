package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.support.IsolatedRedis;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CacheClientTest {
    private IsolatedRedis instance;
    private StringRedisTemplate redis;
    private CacheClient cache;

    @BeforeAll
    void start() throws Exception {
        instance = new IsolatedRedis();
        redis = instance.template();
    }

    @AfterAll
    void stop() throws Exception {
        instance.close();
    }

    @BeforeEach
    void setUp() {
        instance.clear();
        cache = new CacheClient(redis, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    @AfterEach
    void closeCache() {
        cache.close();
    }

    @Test
    void passThroughCachesHitsAndMissingValuesWithTtl() {
        AtomicInteger queries = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            Shop shop = cache.queryWithPassThrough("test:shop:", 5L, Shop.class, id -> {
                queries.incrementAndGet();
                return new Shop().setId(id).setName("cached");
            }, 30, TimeUnit.MINUTES);
            assertEquals("cached", shop.getName());
            assertNull(cache.queryWithPassThrough("test:missing:", 6L, Shop.class, id -> {
                queries.incrementAndGet();
                return null;
            }, 30, TimeUnit.MINUTES));
        }
        assertEquals(2, queries.get());
        assertTrue(redis.getExpire("test:shop:5") > 0);
        assertEquals("", redis.opsForValue().get("test:missing:6"));
        assertTrue(redis.getExpire("test:missing:6") <= 120);
    }

    @Test
    void concurrentMutexMissesOnlyLoadOnce() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        ExecutorService readers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Shop>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(readers.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(5, TimeUnit.SECONDS));
                    return cache.queryWithMutex("test:mutex:", 5L, Shop.class, "test:lock:", id -> {
                        queries.incrementAndGet();
                        return new Shop().setId(id);
                    }, 30, TimeUnit.MINUTES);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            for (Future<Shop> result : results) {
                assertEquals(Long.valueOf(5), result.get(5, TimeUnit.SECONDS).getId());
            }
            assertEquals(1, queries.get());
            assertFalse(Boolean.TRUE.equals(redis.hasKey("test:lock:5")));
        } finally {
            go.countDown();
            readers.shutdownNow();
        }
    }

    @Test
    void logicalColdMissAndNegativeCacheDoNotRequirePrewarming() {
        AtomicInteger queries = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            assertNotNull(cache.queryWithLogicalExpire("test:logical:", 5L, Shop.class,
                    "test:lock:", id -> {
                        queries.incrementAndGet();
                        return new Shop().setId(id);
                    }, 30, TimeUnit.MINUTES));
            assertNull(cache.queryWithLogicalExpire("test:logical:", 6L, Shop.class,
                    "test:lock:", id -> {
                        queries.incrementAndGet();
                        return null;
                    }, 30, TimeUnit.MINUTES));
        }
        assertEquals(2, queries.get());
        assertEquals(Long.valueOf(-1), redis.getExpire("test:logical:5"));
        assertTrue(redis.getExpire("test:logical:6") > 0);
    }

    @Test
    void expiredLogicalValueIsReturnedWhileOneWorkerRefreshes() throws Exception {
        cache.setWithLogicalExpire("test:logical:5", new Shop().setId(5L).setName("old"), -1, TimeUnit.SECONDS);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger queries = new AtomicInteger();
        java.util.function.Function<Long, Shop> database = id -> {
            queries.incrementAndGet();
            loading.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not release loader");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            return new Shop().setId(id).setName("new");
        };
        try {
            assertEquals("old", cache.queryWithLogicalExpire("test:logical:", 5L, Shop.class,
                    "test:lock:", database, 30, TimeUnit.MINUTES).getName());
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            assertEquals("old", cache.queryWithLogicalExpire("test:logical:", 5L, Shop.class,
                    "test:lock:", database, 30, TimeUnit.MINUTES).getName());
            assertEquals(1, queries.get());
        } finally {
            release.countDown();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Boolean.TRUE.equals(redis.hasKey("test:lock:5")) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("new", cache.queryWithLogicalExpire("test:logical:", 5L, Shop.class,
                "test:lock:", database, 30, TimeUnit.MINUTES).getName());
        assertEquals(1, queries.get());
    }

    @Test
    void databaseFailureReleasesLockAndWrongTokenCannotUnlock() {
        assertThrows(IllegalStateException.class, () -> cache.queryWithMutex("test:mutex:", 5L,
                Shop.class, "test:lock:", id -> { throw new IllegalStateException("DB unavailable"); },
                30, TimeUnit.MINUTES));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("test:lock:5")));
        assertTrue(cache.tryLock("test:lock:5", "owner"));
        cache.unlock("test:lock:5", "another-request");
        assertEquals("owner", redis.opsForValue().get("test:lock:5"));
        cache.unlock("test:lock:5", "owner");
        assertFalse(Boolean.TRUE.equals(redis.hasKey("test:lock:5")));
    }
}
