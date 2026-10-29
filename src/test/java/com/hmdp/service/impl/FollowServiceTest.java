package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FollowServiceTest {
    @Mock private FollowMapper mapper;
    @Mock private StringRedisTemplate redis;
    @Mock private SetOperations<String, String> follows;
    private FollowServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FollowServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        UserDTO viewer = new UserDTO();
        viewer.setId(10L);
        UserHolder.saveUser(viewer);
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
    }

    @Test
    void concurrentInsertReturnsSuccessWhenOtherRequestCreatedRelation() {
        when(redis.opsForSet()).thenReturn(follows);
        when(mapper.selectCount(any())).thenReturn(0, 1);
        when(mapper.insert(any(Follow.class))).thenThrow(new DuplicateKeyException("unique relation"));

        Result result = service.follow(20L, true);

        assertTrue(result.getSuccess());
        assertEquals("已关注该用户", result.getData());
        verify(mapper, times(1)).insert(any(Follow.class));
        verify(follows).add("follows:10", "20");
    }

    @Test
    void duplicateKeyWithoutMatchingRelationIsNotReportedAsSuccess() {
        when(mapper.selectCount(any())).thenReturn(0);
        DuplicateKeyException failure = new DuplicateKeyException("unrelated key conflict");
        when(mapper.insert(any(Follow.class))).thenThrow(failure);

        assertSame(failure, assertThrows(DuplicateKeyException.class,
                () -> service.follow(20L, true)));
        verifyNoInteractions(redis, follows);
    }

    @Test
    void databaseOutageIsNotReportedAsAlreadyFollowed() {
        when(mapper.selectCount(any())).thenReturn(0);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("unavailable");
        when(mapper.insert(any(Follow.class))).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.follow(20L, true)));
        verifyNoInteractions(redis, follows);
    }

    @Test
    void sequentialRepeatDoesNotInsertAnotherRelation() {
        when(redis.opsForSet()).thenReturn(follows);
        when(mapper.selectCount(any())).thenReturn(1);

        assertTrue(service.follow(20L, true).getSuccess());
        verify(mapper, never()).insert(any(Follow.class));
        verify(follows).add("follows:10", "20");
    }

    @Test
    void addsRedisMemberOnlyAfterDatabaseInsertSucceeds() {
        when(mapper.selectCount(any())).thenReturn(0);
        when(mapper.insert(any(Follow.class))).thenReturn(1);
        when(redis.opsForSet()).thenReturn(follows);

        assertTrue(service.follow(20L, true).getSuccess());

        InOrder order = inOrder(mapper, redis, follows);
        order.verify(mapper).insert(any(Follow.class));
        order.verify(redis).opsForSet();
        order.verify(follows).add("follows:10", "20");
    }

    @Test
    void unsuccessfulInsertDoesNotCreateRedisOnlyRelation() {
        when(mapper.selectCount(any())).thenReturn(0);
        when(mapper.insert(any(Follow.class))).thenReturn(0);

        assertFalse(service.follow(20L, true).getSuccess());
        verifyNoInteractions(redis, follows);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void unfollowRemovesOnlyTargetMemberEvenIfDatabaseRelationAlreadyAbsent(int deletedRows) {
        when(mapper.delete(any())).thenReturn(deletedRows);
        when(redis.opsForSet()).thenReturn(follows);

        assertTrue(service.follow(20L, false).getSuccess());

        InOrder order = inOrder(mapper, redis, follows);
        order.verify(mapper).delete(any());
        order.verify(redis).opsForSet();
        order.verify(follows).remove("follows:10", "20");
        verifyNoMoreInteractions(follows);
    }

    @Test
    void failedDatabaseDeleteDoesNotRemoveRedisMember() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("unavailable");
        when(mapper.delete(any())).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.follow(20L, false)));
        verifyNoInteractions(redis, follows);
    }
}
