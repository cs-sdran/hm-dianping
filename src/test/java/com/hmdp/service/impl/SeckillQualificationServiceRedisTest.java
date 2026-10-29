package com.hmdp.service.impl;

import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实执行 Java -> StringRedisTemplate -> 预扣/回补 Lua -> Redis。
 * 自动启动独立、无持久化的 Redis，不连接开发 Redis，不操作 MySQL。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeckillQualificationServiceRedisTest {
    private Process redisProcess;
    private LettuceConnectionFactory firstConnection;
    private LettuceConnectionFactory secondConnection;
    private StringRedisTemplate redis;
    private SeckillQualificationService firstService;
    private SeckillQualificationService secondService;
    private final Set<String> fixtureKeys = new HashSet<>();
    private long nextVoucherId = 900000000L;
    private Long voucherId;

    @BeforeAll
    void startIsolatedRedis() throws Exception {
        Path target = Paths.get("target").toAbsolutePath().normalize();
        Files.createDirectories(target);
        Path runDirectory = Files.createTempDirectory(target, "qualification-redis-");
        int port;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        try {
            redisProcess = new ProcessBuilder(redisExecutable(), "--bind", "127.0.0.1",
                    "--port", String.valueOf(port), "--save", "", "--appendonly", "no",
                    "--dir", runDirectory.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(runDirectory.resolve("redis.log").toFile())
                    .start();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean listening = false;
            while (System.nanoTime() < deadline) {
                if (!redisProcess.isAlive()) {
                    throw new IllegalStateException("测试 Redis 启动失败，请查看 " + runDirectory.resolve("redis.log"));
                }
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                    listening = true;
                    break;
                } catch (IOException notReady) {
                    Thread.sleep(50);
                }
            }
            if (!listening) {
                throw new IllegalStateException("测试 Redis 启动超时");
            }

            // 两个独立 Redis 客户端调用同一个真实脚本，验证共享资格数据。
            firstConnection = connection(port);
            secondConnection = connection(port);
            redis = new StringRedisTemplate(firstConnection);
            firstService = service(redis);
            secondService = service(new StringRedisTemplate(secondConnection));
        } catch (Exception failure) {
            stopIsolatedRedis();
            throw failure;
        }
    }

    private String redisExecutable() {
        String configured = System.getProperty("hmdp.test.redis.executable");
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv("REDIS_SERVER");
        }
        if (configured != null && !configured.trim().isEmpty()) {
            return configured;
        }
        Path localWindowsRedis = Paths.get("D:/Redis/Redis-x64-3.2.100/redis-server.exe");
        return Files.isRegularFile(localWindowsRedis) ? localWindowsRedis.toString() : "redis-server";
    }

    private LettuceConnectionFactory connection(int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet();
        return factory;
    }

    private SeckillQualificationService service(StringRedisTemplate template) {
        SeckillQualificationService service = new SeckillQualificationService();
        // 只注入真实 Redis 客户端，不模拟执行结果；本服务的资格方法不使用数据库 Mapper。
        ReflectionTestUtils.setField(service, "stringRedisTemplate", template);
        return service;
    }

    @BeforeEach
    void allocateFixture() {
        fixtureKeys.clear();
        voucherId = ++nextVoucherId;
        registerKeys(voucherId);
    }

    private void registerKeys(Long id) {
        fixtureKeys.add(stockKey(id));
        fixtureKeys.add(usersKey(id));
    }

    private String stockKey(Long id) {
        return RedisConstants.SECKILL_STOCK_KEY + id;
    }

    private String usersKey(Long id) {
        return RedisConstants.SECKILL_ORDER_RECORD + id;
    }

    private void stock(String value) {
        redis.opsForValue().set(stockKey(voucherId), value);
    }

    private void assertUserAbsent(Long userId) {
        assertEquals(Boolean.FALSE, redis.opsForSet().isMember(usersKey(voucherId), userId.toString()));
    }

    @AfterEach
    void removeOnlyFixtureKeys() {
        if (redis != null && !fixtureKeys.isEmpty()) {
            redis.delete(new ArrayList<>(fixtureKeys));
        }
    }

    @AfterAll
    void stopIsolatedRedis() throws Exception {
        try {
            if (secondConnection != null) {
                secondConnection.destroy();
            }
            if (firstConnection != null) {
                firstConnection.destroy();
            }
        } finally {
            if (redisProcess != null && redisProcess.isAlive()) {
                redisProcess.destroy();
                if (!redisProcess.waitFor(5, TimeUnit.SECONDS)) {
                    redisProcess.destroyForcibly();
                    redisProcess.waitFor(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void firstPurchaseDecrementsStockAndRecordsUser() {
        stock("5");
        assertEquals(0L, firstService.checkAndReserve(voucherId, 20L));
        assertEquals("4", redis.opsForValue().get(stockKey(voucherId)));
        assertEquals(Boolean.TRUE, redis.opsForSet().isMember(usersKey(voucherId), "20"));
    }

    @Test
    void duplicateFromAnotherClientDoesNotDecrementAgain() {
        stock("5");
        assertEquals(0L, firstService.checkAndReserve(voucherId, 20L));
        assertEquals(2L, secondService.checkAndReserve(voucherId, 20L));
        assertEquals("4", redis.opsForValue().get(stockKey(voucherId)));
        assertEquals(Long.valueOf(1), redis.opsForSet().size(usersKey(voucherId)));
    }

    @Test
    void missingInventoryReturnsFiveWithoutCreatingKeys() {
        assertEquals(5L, firstService.checkAndReserve(voucherId, 20L));
        assertEquals(Boolean.FALSE, redis.hasKey(stockKey(voucherId)));
        assertEquals(Boolean.FALSE, redis.hasKey(usersKey(voucherId)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", ""})
    void nonNumericInventoryReturnsFiveWithoutMutation(String value) {
        stock(value);
        assertEquals(5L, firstService.checkAndReserve(voucherId, 20L));
        assertEquals(value, redis.opsForValue().get(stockKey(voucherId)));
        assertUserAbsent(20L);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveInventoryReturnsOneWithoutMutation(int value) {
        stock(String.valueOf(value));
        assertEquals(1L, firstService.checkAndReserve(voucherId, 20L));
        assertEquals(String.valueOf(value), redis.opsForValue().get(stockKey(voucherId)));
        assertUserAbsent(20L);
    }

    @Test
    void differentUsersCanEachReserve() {
        stock("5");
        assertEquals(0L, firstService.checkAndReserve(voucherId, 20L));
        assertEquals(0L, secondService.checkAndReserve(voucherId, 21L));
        assertEquals("3", redis.opsForValue().get(stockKey(voucherId)));
        assertEquals(Long.valueOf(2), redis.opsForSet().size(usersKey(voucherId)));
    }

    @Test
    void sameUserCanReserveDifferentVouchers() {
        Long anotherVoucher = ++nextVoucherId;
        registerKeys(anotherVoucher);
        stock("5");
        redis.opsForValue().set(stockKey(anotherVoucher), "5");
        assertEquals(0L, firstService.checkAndReserve(voucherId, 20L));
        assertEquals(0L, secondService.checkAndReserve(anotherVoucher, 20L));
        assertEquals("4", redis.opsForValue().get(stockKey(voucherId)));
        assertEquals("4", redis.opsForValue().get(stockKey(anotherVoucher)));
    }

    @Test
    void largeUserIdIsPreservedAsString() {
        Long userId = 9007199254740993L;
        stock("5");
        assertEquals(0L, firstService.checkAndReserve(voucherId, userId));
        assertEquals(Boolean.TRUE, redis.opsForSet().isMember(usersKey(voucherId), userId.toString()));
        assertEquals(2L, secondService.checkAndReserve(voucherId, userId));
    }

    @Test
    void soldOutCheckTakesPriorityOverDuplicateCheck() {
        stock("1");
        assertEquals(0L, firstService.checkAndReserve(voucherId, 20L));
        assertEquals(1L, secondService.checkAndReserve(voucherId, 20L));
        assertEquals("0", redis.opsForValue().get(stockKey(voucherId)));
        assertEquals(Long.valueOf(1), redis.opsForSet().size(usersKey(voucherId)));
    }

    @Test
    void fiftyConcurrentRequestsBySameUserReserveOnlyOnce() throws Exception {
        stock("5");
        List<Long> results = concurrentRequests(index -> 20L);
        assertEquals(1L, results.stream().filter(code -> code == 0L).count());
        assertEquals(49L, results.stream().filter(code -> code == 2L).count());
        assertEquals("4", redis.opsForValue().get(stockKey(voucherId)));
        assertEquals(Long.valueOf(1), redis.opsForSet().size(usersKey(voucherId)));
    }

    @Test
    void fiftyUsersCompetingForTenUnitsCannotOversell() throws Exception {
        stock("10");
        List<Long> results = concurrentRequests(index -> 1000L + index);
        assertEquals(10L, results.stream().filter(code -> code == 0L).count());
        assertEquals(40L, results.stream().filter(code -> code == 1L).count());
        assertEquals("0", redis.opsForValue().get(stockKey(voucherId)));
        assertEquals(Long.valueOf(10), redis.opsForSet().size(usersKey(voucherId)));
    }

    @Test
    void releaseRestoresStockAndRemovesOnlyReservedUser() {
        stock("5");
        assertEquals(0L, firstService.checkAndReserve(voucherId, 20L));
        assertEquals("4", redis.opsForValue().get(stockKey(voucherId)));

        assertEquals(0L, secondService.releaseReservation(voucherId, 20L));
        assertEquals("5", redis.opsForValue().get(stockKey(voucherId)));
        assertUserAbsent(20L);
    }

    @Test
    void immediateRepeatedReleaseDoesNotIncreaseStockTwice() {
        stock("5");
        assertEquals(0L, firstService.checkAndReserve(voucherId, 20L));
        assertEquals(0L, firstService.releaseReservation(voucherId, 20L));

        assertEquals(1L, secondService.releaseReservation(voucherId, 20L));
        assertEquals("5", redis.opsForValue().get(stockKey(voucherId)));
        assertUserAbsent(20L);
    }

    @Test
    void releaseWithoutReservationDoesNotInventStock() {
        stock("5");
        assertEquals(1L, firstService.releaseReservation(voucherId, 20L));
        assertEquals("5", redis.opsForValue().get(stockKey(voucherId)));
        assertEquals(Boolean.FALSE, redis.hasKey(usersKey(voucherId)));
    }

    @Test
    void releaseWithoutEitherKeyDoesNotCreateKeys() {
        assertEquals(1L, firstService.releaseReservation(voucherId, 20L));
        assertEquals(Boolean.FALSE, redis.hasKey(stockKey(voucherId)));
        assertEquals(Boolean.FALSE, redis.hasKey(usersKey(voucherId)));
    }

    @Test
    void releaseWithMissingStockKeepsReservationForInvestigation() {
        stock("5");
        assertEquals(0L, firstService.checkAndReserve(voucherId, 20L));
        redis.delete(stockKey(voucherId));

        assertEquals(5L, secondService.releaseReservation(voucherId, 20L));
        assertEquals(Boolean.FALSE, redis.hasKey(stockKey(voucherId)));
        assertEquals(Boolean.TRUE, redis.opsForSet().isMember(usersKey(voucherId), "20"));
    }

    @Test
    void releasingDifferentUserDoesNotCancelExistingReservation() {
        stock("5");
        assertEquals(0L, firstService.checkAndReserve(voucherId, 20L));

        assertEquals(1L, secondService.releaseReservation(voucherId, 21L));
        assertEquals("4", redis.opsForValue().get(stockKey(voucherId)));
        assertEquals(Boolean.TRUE, redis.opsForSet().isMember(usersKey(voucherId), "20"));
        assertEquals(Long.valueOf(1), redis.opsForSet().size(usersKey(voucherId)));
    }

    @Test
    void releaseDoesNotAffectAnotherVoucherForSameUser() {
        Long anotherVoucher = ++nextVoucherId;
        registerKeys(anotherVoucher);
        stock("5");
        redis.opsForValue().set(stockKey(anotherVoucher), "5");
        assertEquals(0L, firstService.checkAndReserve(voucherId, 20L));
        assertEquals(0L, secondService.checkAndReserve(anotherVoucher, 20L));

        assertEquals(0L, firstService.releaseReservation(voucherId, 20L));
        assertEquals("5", redis.opsForValue().get(stockKey(voucherId)));
        assertUserAbsent(20L);
        assertEquals("4", redis.opsForValue().get(stockKey(anotherVoucher)));
        assertEquals(Boolean.TRUE, redis.opsForSet().isMember(usersKey(anotherVoucher), "20"));
    }

    @Test
    void userCanReserveAgainAfterRelease() {
        stock("5");
        assertEquals(0L, firstService.checkAndReserve(voucherId, 20L));
        assertEquals(0L, firstService.releaseReservation(voucherId, 20L));

        assertEquals(0L, secondService.checkAndReserve(voucherId, 20L));
        assertEquals("4", redis.opsForValue().get(stockKey(voucherId)));
        assertEquals(Boolean.TRUE, redis.opsForSet().isMember(usersKey(voucherId), "20"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5"})
    void invalidStockDuringReleaseThrowsWithoutRemovingReservation(String invalidStock) {
        stock("5");
        assertEquals(0L, firstService.checkAndReserve(voucherId, 20L));
        // 模拟预扣之后库存被外部修改，验证 INCR 失败时不会先删除购买记录。
        stock(invalidStock);

        assertThrows(DataAccessException.class, () -> secondService.releaseReservation(voucherId, 20L));
        assertEquals(invalidStock, redis.opsForValue().get(stockKey(voucherId)));
        assertEquals(Boolean.TRUE, redis.opsForSet().isMember(usersKey(voucherId), "20"));
    }

    @Test
    void fiftyConcurrentReleasesOfOneUnchangedReservationRestoreOnlyOneUnit() throws Exception {
        stock("5");
        assertEquals(0L, firstService.checkAndReserve(voucherId, 20L));

        List<Long> results = concurrentCalls(index -> {
            SeckillQualificationService service = index % 2 == 0 ? firstService : secondService;
            return service.releaseReservation(voucherId, 20L);
        });
        assertEquals(1L, results.stream().filter(code -> code == 0L).count());
        assertEquals(49L, results.stream().filter(code -> code == 1L).count());
        assertEquals("5", redis.opsForValue().get(stockKey(voucherId)));
        assertUserAbsent(20L);
    }

    @Test
    void initializeStockCreatesInventoryWithoutPurchaseRecords() {
        assertTrue(firstService.initializeStock(voucherId, 5));
        assertEquals("5", redis.opsForValue().get(stockKey(voucherId)));
        assertEquals(Boolean.FALSE, redis.hasKey(usersKey(voucherId)));
    }

    @Test
    void initializeZeroStockIsAllowedAndCannotBeReserved() {
        assertTrue(firstService.initializeStock(voucherId, 0));
        assertEquals("0", redis.opsForValue().get(stockKey(voucherId)));
        assertEquals(1L, secondService.checkAndReserve(voucherId, 20L));
        assertEquals(Boolean.FALSE, redis.hasKey(usersKey(voucherId)));
    }

    @Test
    void initializeNegativeStockIsRejectedWithoutCreatingKeys() {
        assertThrows(IllegalArgumentException.class, () -> firstService.initializeStock(voucherId, -1));
        assertEquals(Boolean.FALSE, redis.hasKey(stockKey(voucherId)));
        assertEquals(Boolean.FALSE, redis.hasKey(usersKey(voucherId)));
    }

    @Test
    void initializeAgainDoesNotResetDecrementedStockOrPurchaseRecord() {
        assertTrue(firstService.initializeStock(voucherId, 5));
        assertEquals(0L, firstService.checkAndReserve(voucherId, 20L));

        assertFalse(secondService.initializeStock(voucherId, 10));
        assertEquals("4", redis.opsForValue().get(stockKey(voucherId)));
        assertEquals(Boolean.TRUE, redis.opsForSet().isMember(usersKey(voucherId), "20"));
    }

    @Test
    void initializeAgainDoesNotReplenishSoldOutStock() {
        stock("0");
        assertFalse(firstService.initializeStock(voucherId, 5));
        assertEquals("0", redis.opsForValue().get(stockKey(voucherId)));
    }

    @Test
    void fiftyConcurrentInitializationsCreateOnlyOneInventoryValue() throws Exception {
        List<Long> results = concurrentCalls(index -> {
            SeckillQualificationService service = index % 2 == 0 ? firstService : secondService;
            return service.initializeStock(voucherId, 5 + index) ? 1L : 0L;
        });
        assertEquals(1L, results.stream().filter(created -> created == 1L).count());
        assertEquals(49L, results.stream().filter(created -> created == 0L).count());
        int stored = Integer.parseInt(redis.opsForValue().get(stockKey(voucherId)));
        assertTrue(stored >= 5 && stored <= 54);
        assertEquals(Boolean.FALSE, redis.hasKey(usersKey(voucherId)));
    }

    private List<Long> concurrentRequests(IntFunction<Long> users) throws Exception {
        return concurrentCalls(index -> {
            SeckillQualificationService service = index % 2 == 0 ? firstService : secondService;
            return service.checkAndReserve(voucherId, users.apply(index));
        });
    }

    private List<Long> concurrentCalls(IntFunction<Long> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 50; index++) {
                final int requestIndex = index;
                futures.add(pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发测试启动超时");
                    }
                    return request.apply(requestIndex);
                }));
            }
            start.countDown();
            List<Long> results = new ArrayList<>();
            for (Future<Long> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
