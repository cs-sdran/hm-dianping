package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlogServiceLikesTest {
    private static final String KEY = RedisConstants.BLOG_LIKED_KEY + "10";
    @Mock private BlogMapper mapper;
    @Mock private IUserService users;
    @Mock private StringRedisTemplate redis;
    @Mock private ZSetOperations<String, String> likes;
    private BlogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "userService", users);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
    }

    private void stubLikes(Set<String> userIds) {
        when(mapper.selectById(10L)).thenReturn(new Blog().setId(10L));
        when(redis.opsForZSet()).thenReturn(likes);
        when(likes.range(KEY, 0L, 4L)).thenReturn(userIds);
    }

    private User user(long id) {
        return new User().setId(id).setNickName("user-" + id).setIcon("icon-" + id);
    }

    @SuppressWarnings("unchecked")
    private List<UserDTO> resultUsers(Result result) {
        assertTrue(result.getSuccess());
        assertTrue(result.getData() instanceof List);
        List<?> data = (List<?>) result.getData();
        assertTrue(data.stream().allMatch(item -> item instanceof UserDTO));
        return (List<UserDTO>) data;
    }

    @Test
    void missingBlogReturnsFailureWithoutRedisOrUserQueries() {
        Result result = service.queryBlogLikes(10L);
        assertFalse(result.getSuccess());
        assertEquals("blog不存在", result.getErrorMsg());
        verifyNoInteractions(redis, users);
    }

    @Test
    void nullRedisResultReturnsEmptyListWithoutUserQueries() {
        stubLikes(null);
        assertTrue(resultUsers(service.queryBlogLikes(10L)).isEmpty());
        verifyNoInteractions(users);
    }

    @Test
    void emptyRedisResultReturnsEmptyListWithoutUserQueries() {
        stubLikes(Collections.emptySet());
        assertTrue(resultUsers(service.queryBlogLikes(10L)).isEmpty());
        verifyNoInteractions(users);
    }

    @Test
    void keepsRedisOrderMapsDtoAndQueriesEachOfFiveUsersOnlyOnce() {
        List<Long> ids = Arrays.asList(8L, 3L, 12L, 2L, 15L);
        stubLikes(ids.stream().map(String::valueOf).collect(Collectors.toCollection(LinkedHashSet::new)));
        for (Long id : ids) {
            when(users.getById(id)).thenReturn(user(id));
        }

        List<UserDTO> result = resultUsers(service.queryBlogLikes(10L));

        assertEquals(ids, result.stream().map(UserDTO::getId).collect(Collectors.toList()));
        for (int index = 0; index < ids.size(); index++) {
            assertEquals("user-" + ids.get(index), result.get(index).getNickName());
            assertEquals("icon-" + ids.get(index), result.get(index).getIcon());
            verify(users, times(1)).getById(ids.get(index));
        }
        verifyNoMoreInteractions(users);
        verify(likes).range(KEY, 0L, 4L);
    }

    @Test
    void missingUserIsSkippedAndRemainingUsersKeepTheirOrder() {
        stubLikes(new LinkedHashSet<>(Arrays.asList("8", "3", "12")));
        when(users.getById(8L)).thenReturn(user(8L));
        when(users.getById(3L)).thenReturn(null);
        when(users.getById(12L)).thenReturn(user(12L));

        List<UserDTO> result = resultUsers(service.queryBlogLikes(10L));

        assertEquals(Arrays.asList(8L, 12L), result.stream().map(UserDTO::getId).collect(Collectors.toList()));
        verify(users).getById(8L);
        verify(users).getById(3L);
        verify(users).getById(12L);
        verifyNoMoreInteractions(users);
    }

    @Test
    void allMissingUsersReturnsSuccessfulEmptyList() {
        stubLikes(new LinkedHashSet<>(Arrays.asList("8", "3")));
        assertTrue(resultUsers(service.queryBlogLikes(10L)).isEmpty());
        verify(users).getById(8L);
        verify(users).getById(3L);
        verifyNoMoreInteractions(users);
    }
}
