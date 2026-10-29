package com.hmdp.service.impl;

import com.hmdp.controller.BlogController;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class BlogServicePublishTest {
    @Mock private BlogMapper mapper;
    @Mock private IFollowService followService;
    @Mock private StringRedisTemplate redis;
    @Mock private ZSetOperations<String, String> feeds;
    private BlogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "followService", followService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        UserDTO author = new UserDTO();
        author.setId(10L);
        UserHolder.saveUser(author);
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
    }

    @Test
    void anonymousUserCannotPublish() {
        UserHolder.removeUser();

        Result result = service.saveBlog(new Blog());

        assertFalse(result.getSuccess());
        assertEquals("请先登录", result.getErrorMsg());
        verifyNoInteractions(mapper, followService, redis, feeds);
    }

    @Test
    void zeroInsertedRowsReturnsFailureWithoutBlogId() {
        when(mapper.insert(any(Blog.class))).thenReturn(0);

        Result result = service.saveBlog(new Blog().setTitle("test"));

        assertFalse(result.getSuccess());
        assertEquals("发布失败", result.getErrorMsg());
        assertNull(result.getData());
        verifyNoInteractions(followService, redis, feeds);
    }

    @Test
    void postRouteUsesLoggedInAuthorAndReturnsIdFilledByInsert() throws Exception {
        when(followService.list(any())).thenReturn(Collections.emptyList());
        when(mapper.insert(any(Blog.class))).thenAnswer(invocation -> {
            Blog blog = invocation.getArgument(0);
            blog.setId(77L);
            return 1;
        });
        BlogController controller = new BlogController();
        ReflectionTestUtils.setField(controller, "blogService", service);
        // 仅验证请求映射和业务，登录上下文由 setUp 提供，数据库使用模拟对象。
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/blog").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"test\",\"userId\":999,\"shopId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(77));

        ArgumentCaptor<Blog> saved = ArgumentCaptor.forClass(Blog.class);
        verify(mapper).insert(saved.capture());
        assertEquals(Long.valueOf(10L), saved.getValue().getUserId());
        assertEquals("test", saved.getValue().getTitle());
        assertEquals(Long.valueOf(1L), saved.getValue().getShopId());
        verifyNoInteractions(redis, feeds);
    }

    @Test
    void databaseExceptionDoesNotProduceSuccessResponse() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("unavailable");
        when(mapper.insert(any(Blog.class))).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.saveBlog(new Blog())));
        verifyNoInteractions(followService, redis, feeds);
    }

    @Test
    @SuppressWarnings("unchecked")
    void savedBlogIsPushedToEachFansFeedWithOneTimestamp() {
        when(mapper.insert(any(Blog.class))).thenAnswer(invocation -> {
            Blog blog = invocation.getArgument(0);
            blog.setId(77L);
            return 1;
        });
        when(followService.list(any())).thenReturn(Arrays.asList(
                new Follow().setUserId(20L).setFollowUserId(10L),
                new Follow().setUserId(30L).setFollowUserId(10L)));
        when(redis.opsForZSet()).thenReturn(feeds);

        Result result = service.saveBlog(new Blog().setUserId(999L));

        assertTrue(result.getSuccess());
        assertEquals(77L, result.getData());
        ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(followService).list(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("follow_user_id ="));
        assertEquals(Collections.singletonList(10L),
                new java.util.ArrayList<>(query.getValue().getParamNameValuePairs().values()));

        org.mockito.InOrder order = inOrder(mapper, followService, feeds);
        order.verify(mapper).insert(any(Blog.class));
        order.verify(followService).list(any());
        ArgumentCaptor<Double> timestamps = ArgumentCaptor.forClass(Double.class);
        order.verify(feeds).add(eq("feed:20"), eq("77"), timestamps.capture());
        order.verify(feeds).add(eq("feed:30"), eq("77"), timestamps.capture());
        assertTrue(timestamps.getAllValues().get(0) > 0);
        assertEquals(timestamps.getAllValues().get(0), timestamps.getAllValues().get(1));
        verifyNoMoreInteractions(feeds);
    }

    @Test
    void redisFailureIsNotReportedAsSuccessfulDelivery() {
        when(mapper.insert(any(Blog.class))).thenAnswer(invocation -> {
            Blog blog = invocation.getArgument(0);
            blog.setId(77L);
            return 1;
        });
        when(followService.list(any())).thenReturn(Collections.singletonList(
                new Follow().setUserId(20L).setFollowUserId(10L)));
        when(redis.opsForZSet()).thenReturn(feeds);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("redis unavailable");
        when(feeds.add(eq("feed:20"), eq("77"), anyDouble())).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.saveBlog(new Blog())));
        // Redis 故障发生在数据库插入之后；这里没有跨数据库和 Redis 的事务。
        verify(mapper).insert(any(Blog.class));
    }
}
