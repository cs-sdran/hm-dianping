package com.hmdp.utils;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        //1 获取token
        String token=request.getHeader("authorization");
        if(token==null)
        {
            response.setStatus(401);
            return false;
        }
        //2 获取redis中的用户,将hash类型转换为普通类型

        Map<Object, Object> usermap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);


        //3 判断用户是否存在
        //4 不存在则拦截
        if(usermap.isEmpty())
        {


            response.setStatus(401);
            return false;

        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(usermap, new UserDTO(), false);

        //5 存在则保存到threadlocal
        UserHolder.saveUser(userDTO);

        //6 刷新token有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);


        //7 放行

        return true;

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {

        UserHolder.removeUser();
    }
}
