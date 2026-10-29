package com.hmdp.service.impl;

import com.hmdp.controller.FollowController;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class FollowCommonsTest {
    @Mock private StringRedisTemplate redis;
    @Mock private SetOperations<String, String> follows;
    @Mock private IUserService users;
    private FollowServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FollowServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "userService", users);
        UserDTO viewer = new UserDTO();
        viewer.setId(10L);
        UserHolder.saveUser(viewer);
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
    }

    private void stubIntersection(Set<String> ids) {
        when(redis.opsForSet()).thenReturn(follows);
        when(follows.intersect("follows:10", "follows:20")).thenReturn(ids);
    }

    private void assertEmptySuccess(Result result) {
        assertTrue(result.getSuccess());
        assertTrue(result.getData() instanceof List);
        assertTrue(((List<?>) result.getData()).isEmpty());
    }

    @Test
    void anonymousUserDoesNotQueryRedisOrDatabase() {
        UserHolder.removeUser();
        Result result = service.followCommons(20L);
        assertFalse(result.getSuccess());
        assertEquals("请先登录", result.getErrorMsg());
        verifyNoInteractions(redis, follows, users);
    }

    @Test
    void nullIntersectionReturnsEmptyListWithoutDatabaseQuery() {
        stubIntersection(null);
        assertEmptySuccess(service.followCommons(20L));
        verifyNoInteractions(users);
    }

    @Test
    void emptyIntersectionReturnsEmptyListWithoutDatabaseQuery() {
        stubIntersection(Collections.emptySet());
        assertEmptySuccess(service.followCommons(20L));
        verifyNoInteractions(users);
    }

    @Test
    void getRouteBindsTargetAndReturnsOnlyPublicUserFields() throws Exception {
        stubIntersection(Collections.singleton("30"));
        User common = new User().setId(30L).setNickName("common-user")
                .setIcon("avatar.png").setPhone("private-phone").setPassword("private-password");
        when(users.listByIds(Collections.singletonList(30L)))
                .thenReturn(Collections.singletonList(common));
        FollowController controller = new FollowController();
        ReflectionTestUtils.setField(controller, "followService", service);
        // 独立测试路由与响应，登录上下文由 setUp 提供，不启动真实数据库或 Redis。
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/follow/common/20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(30))
                .andExpect(jsonPath("$.data[0].nickName").value("common-user"))
                .andExpect(jsonPath("$.data[0].icon").value("avatar.png"))
                .andExpect(jsonPath("$.data[0].phone").doesNotExist())
                .andExpect(jsonPath("$.data[0].password").doesNotExist());
        verify(users).listByIds(Collections.singletonList(30L));
    }

    @Test
    void deletedCommonUserReturnsEmptyList() {
        stubIntersection(Collections.singleton("30"));
        when(users.listByIds(Collections.singletonList(30L)))
                .thenReturn(Collections.emptyList());
        assertEmptySuccess(service.followCommons(20L));
    }
}
