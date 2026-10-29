package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlogServiceFeedTest {
    @Mock private BlogMapper mapper;
    @Mock private IUserService users;
    @Mock private StringRedisTemplate redis;
    @Mock private ZSetOperations<String, String> zsets;
    private BlogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "userService", users);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        UserDTO viewer = new UserDTO();
        viewer.setId(10L);
        UserHolder.saveUser(viewer);
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
    }

    private void stubFeed(Set<ZSetOperations.TypedTuple<String>> entries) {
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.reverseRangeByScoreWithScores("feed:10", 0, 1000, 0, 2))
                .thenReturn(entries);
    }

    private ZSetOperations.TypedTuple<String> entry(long id, double timestamp) {
        return new DefaultTypedTuple<>(String.valueOf(id), timestamp);
    }

    @SuppressWarnings("unchecked")
    private List<Blog> resultBlogs(Result result) {
        List<?> data = resultPage(result).getList();
        assertTrue(data.stream().allMatch(item -> item instanceof Blog));
        return (List<Blog>) data;
    }

    private ScrollResult resultPage(Result result) {
        assertTrue(result.getSuccess());
        assertTrue(result.getData() instanceof ScrollResult);
        return (ScrollResult) result.getData();
    }

    @Test
    void anonymousViewerDoesNotReadFeedOrDatabase() {
        UserHolder.removeUser();
        Result result = service.queryBlogOfFollow(1000, 0);
        assertFalse(result.getSuccess());
        assertEquals("请先登录", result.getErrorMsg());
        verifyNoInteractions(redis, zsets, mapper, users);
    }

    @Test
    void nullFeedReturnsEmptyListWithoutDatabaseQueries() {
        stubFeed(null);
        ScrollResult page = resultPage(service.queryBlogOfFollow(1000, 0));
        assertTrue(page.getList().isEmpty());
        assertEquals(Long.valueOf(1000), page.getMinTime());
        assertEquals(Integer.valueOf(0), page.getOffset());
        verifyNoInteractions(mapper, users);
    }

    @Test
    void emptyFeedReturnsEmptyListWithoutDatabaseQueries() {
        stubFeed(Collections.emptySet());
        ScrollResult page = resultPage(service.queryBlogOfFollow(1000, 0));
        assertTrue(page.getList().isEmpty());
        assertEquals(Long.valueOf(1000), page.getMinTime());
        assertEquals(Integer.valueOf(0), page.getOffset());
        verifyNoInteractions(mapper, users);
    }

    @Test
    void preservesFeedOrderUsesAuthorIdsAndFillsViewersLikeStatus() {
        stubFeed(new LinkedHashSet<>(Arrays.asList(entry(77, 900), entry(12, 800))));
        when(mapper.selectById(77L)).thenReturn(new Blog().setId(77L).setUserId(20L));
        when(mapper.selectById(12L)).thenReturn(new Blog().setId(12L).setUserId(30L));
        when(users.getById(20L)).thenReturn(new User().setId(20L).setNickName("author20").setIcon("20.png"));
        when(users.getById(30L)).thenReturn(new User().setId(30L).setNickName("author30").setIcon("30.png"));
        when(zsets.score("blog:liked:77", "10")).thenReturn(0D);
        when(zsets.score("blog:liked:12", "10")).thenReturn(null);

        List<Blog> blogs = resultBlogs(service.queryBlogOfFollow(1000, 0));

        assertEquals(Arrays.asList(77L, 12L), blogs.stream().map(Blog::getId).collect(Collectors.toList()));
        assertEquals("author20", blogs.get(0).getName());
        assertEquals("20.png", blogs.get(0).getIcon());
        assertEquals("author30", blogs.get(1).getName());
        assertEquals("30.png", blogs.get(1).getIcon());
        assertTrue(blogs.get(0).getIsLike());
        assertFalse(blogs.get(1).getIsLike());
        verify(users).getById(20L);
        verify(users).getById(30L);
        verifyNoMoreInteractions(users);
    }

    @Test
    void deletedBlogIsSkippedAndRemainingBlogIsStillReturned() {
        stubFeed(new LinkedHashSet<>(Arrays.asList(entry(77, 900), entry(12, 800))));
        when(mapper.selectById(77L)).thenReturn(null);
        when(mapper.selectById(12L)).thenReturn(new Blog().setId(12L).setUserId(30L));
        when(users.getById(30L)).thenReturn(new User().setId(30L).setNickName("author30"));

        List<Blog> blogs = resultBlogs(service.queryBlogOfFollow(1000, 0));

        assertEquals(1, blogs.size());
        assertEquals(Long.valueOf(12L), blogs.get(0).getId());
        verify(zsets, never()).score("blog:liked:77", "10");
        verify(users).getById(30L);
        verifyNoMoreInteractions(users);
    }

    @Test
    void missingAuthorDoesNotDiscardExistingBlogOrSkipLikeStatus() {
        stubFeed(Collections.singleton(entry(77, 900)));
        when(mapper.selectById(77L)).thenReturn(new Blog().setId(77L).setUserId(20L));
        when(users.getById(20L)).thenReturn(null);
        when(zsets.score("blog:liked:77", "10")).thenReturn(500D);

        List<Blog> blogs = resultBlogs(service.queryBlogOfFollow(1000, 0));

        assertEquals(1, blogs.size());
        assertEquals(Long.valueOf(77L), blogs.get(0).getId());
        assertNull(blogs.get(0).getName());
        assertNull(blogs.get(0).getIcon());
        assertTrue(blogs.get(0).getIsLike());
        verify(users).getById(20L);
        verifyNoMoreInteractions(users);
    }

    @Test
    void consecutivePagesAccumulateOffsetAcrossSameTimestampThenResetAtOlderTime() {
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.reverseRangeByScoreWithScores("feed:10", 0, 2000, 0, 2))
                .thenReturn(new LinkedHashSet<>(Arrays.asList(entry(1, 1000), entry(2, 900))));
        when(zsets.reverseRangeByScoreWithScores("feed:10", 0, 900, 1, 2))
                .thenReturn(new LinkedHashSet<>(Arrays.asList(entry(3, 900), entry(4, 900))));
        when(zsets.reverseRangeByScoreWithScores("feed:10", 0, 900, 3, 2))
                .thenReturn(new LinkedHashSet<>(Arrays.asList(entry(5, 900), entry(6, 900))));
        when(zsets.reverseRangeByScoreWithScores("feed:10", 0, 900, 5, 2))
                .thenReturn(Collections.singleton(entry(7, 800)));
        when(zsets.reverseRangeByScoreWithScores("feed:10", 0, 800, 1, 2))
                .thenReturn(Collections.emptySet());
        for (long id = 1; id <= 7; id++) {
            when(mapper.selectById(id)).thenReturn(new Blog().setId(id));
        }

        List<Long> seen = new ArrayList<>();
        long max = 2000;
        int offset = 0;
        long[] expectedTimes = {900, 900, 900, 800, 800};
        int[] expectedOffsets = {1, 3, 5, 1, 1};
        for (int index = 0; index < expectedTimes.length; index++) {
            Result result = service.queryBlogOfFollow(max, offset);
            ScrollResult page = resultPage(result);
            seen.addAll(resultBlogs(result).stream().map(Blog::getId).collect(Collectors.toList()));
            assertEquals(expectedTimes[index], page.getMinTime().longValue());
            assertEquals(expectedOffsets[index], page.getOffset().intValue());
            max = page.getMinTime();
            offset = page.getOffset();
        }
        assertEquals(Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L, 7L), seen);
        verifyNoInteractions(users);
    }

    @Test
    void deletedEntriesStillAdvanceCursorEvenWhenNoBlogsCanBeDisplayed() {
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.reverseRangeByScoreWithScores("feed:10", 0, 900, 3, 2))
                .thenReturn(new LinkedHashSet<>(Arrays.asList(entry(77, 900), entry(12, 900))));

        ScrollResult page = resultPage(service.queryBlogOfFollow(900, 3));

        assertTrue(page.getList().isEmpty());
        assertEquals(Long.valueOf(900), page.getMinTime());
        assertEquals(Integer.valueOf(5), page.getOffset());
        verifyNoInteractions(users);
    }

    @Test
    void deletedOldestEntryStillDefinesNextPageTime() {
        stubFeed(new LinkedHashSet<>(Arrays.asList(entry(77, 900), entry(12, 800))));
        when(mapper.selectById(77L)).thenReturn(new Blog().setId(77L));
        when(mapper.selectById(12L)).thenReturn(null);

        Result result = service.queryBlogOfFollow(1000, 0);
        ScrollResult page = resultPage(result);

        assertEquals(1, resultBlogs(result).size());
        assertEquals(Long.valueOf(800), page.getMinTime());
        assertEquals(Integer.valueOf(1), page.getOffset());
    }

    @Test
    void invalidPagingParametersDoNotQueryRedis() {
        assertFalse(service.queryBlogOfFollow(-1, 0).getSuccess());
        assertFalse(service.queryBlogOfFollow(1000, null).getSuccess());
        assertFalse(service.queryBlogOfFollow(1000, -1).getSuccess());
        verifyNoInteractions(redis, zsets, mapper, users);
    }
}
