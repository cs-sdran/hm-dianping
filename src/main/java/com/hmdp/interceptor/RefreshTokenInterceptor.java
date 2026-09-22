package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

//登录状态恢复
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    //进入controller方法之前，读取当前线程的token
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取token
        String token = request.getHeader("authorization");
        //若token为空，直接放行
        if(StrUtil.isBlank(token))
            return true;

        //根据token获取rediskey
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;

        //根据key查询用户
        Map<Object, Object> user = stringRedisTemplate.opsForHash().entries(tokenKey);
        //用户为空直接放行
        if(user.isEmpty())
        {
            return true;
        }

        //查找出用户，map转换为userdto类型
        UserDTO userDTO = BeanUtil.fillBeanWithMap(user, new UserDTO(), false);
        //存入线程
        UserHolder.saveUser(userDTO);

        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();//用完后清理线程
    }
}
