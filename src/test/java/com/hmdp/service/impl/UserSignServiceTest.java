package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.support.IsolatedRedis;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserSignServiceTest {
    private IsolatedRedis instance;
    private StringRedisTemplate redis;
    private UserServiceImpl users;
    private LocalDate date;

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
        users = new UserServiceImpl();
        ReflectionTestUtils.setField(users, "stringRedisTemplate", redis);
        today(LocalDate.of(2026, 10, 24));
        login(5);
    }

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    private void login(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        UserHolder.saveUser(user);
    }

    private void today(LocalDate value) {
        date = value;
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        ReflectionTestUtils.setField(users, "businessClock",
                Clock.fixed(value.atTime(12, 0).atZone(zone).toInstant(), zone));
    }

    private String key() {
        return "sign:5:" + date.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")) + ":";
    }

    private void expectCount(int count) {
        Result result = users.signCount();
        assertTrue(result.getSuccess());
        assertEquals(count, result.getData());
    }

    @Test
    void anonymousUserIsRejectedWithoutWriting() {
        UserHolder.removeUser();
        assertFalse(users.sign().getSuccess());
        assertFalse(users.signCount().getSuccess());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key())));
    }

    @Test
    void emptyMonthAndRepeatedSignIn() {
        expectCount(0);
        assertTrue(users.sign().getSuccess());
        assertTrue(users.sign().getSuccess());
        expectCount(1);
        assertTrue(Boolean.TRUE.equals(redis.opsForValue().getBit(key(), 23)));
        assertFalse(Boolean.TRUE.equals(redis.opsForValue().getBit(key(), 22)));
        assertEquals(Long.valueOf(-1), redis.getExpire(key(), TimeUnit.SECONDS));
    }

    @Test
    void stopsAtTheFirstMissingDayAndTodayMustBeSigned() {
        for (int offset : new int[]{19, 21, 22, 23}) {
            redis.opsForValue().setBit(key(), offset, true);
        }
        expectCount(3);
        redis.opsForValue().setBit(key(), 23, false);
        expectCount(0);
    }

    @Test
    void usersDoNotShareSignInBits() {
        users.sign();
        login(8);
        expectCount(0);
        users.sign();
        expectCount(1);
        login(5);
        expectCount(1);
    }

    @Test
    void newMonthDoesNotCountPreviousMonth() {
        today(LocalDate.of(2026, 10, 31));
        users.sign();
        today(LocalDate.of(2026, 11, 1));
        expectCount(0);
        users.sign();
        expectCount(1);
        assertTrue(Boolean.TRUE.equals(redis.hasKey("sign:5:202610:")));
        assertTrue(Boolean.TRUE.equals(redis.hasKey("sign:5:202611:")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-01", "2026-10-24", "2026-02-28", "2028-02-29", "2026-04-30", "2026-10-31"})
    void allDaysSignedIncludingMonthEndAndLeapDay(String value) {
        today(LocalDate.parse(value));
        for (int offset = 0; offset < date.getDayOfMonth(); offset++) {
            redis.opsForValue().setBit(key(), offset, true);
        }
        expectCount(date.getDayOfMonth());
    }

    @Test
    void usesShanghaiDateEvenWhenUtcIsStillThePreviousDay() {
        ReflectionTestUtils.setField(users, "businessClock", Clock.fixed(
                java.time.Instant.parse("2026-10-31T16:00:00Z"), ZoneId.of("Asia/Shanghai")));
        users.sign();
        assertTrue(Boolean.TRUE.equals(redis.opsForValue().getBit("sign:5:202611:", 0)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("sign:5:202610:")));
        expectCount(1);
    }
}
