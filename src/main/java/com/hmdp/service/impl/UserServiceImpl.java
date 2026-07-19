package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1 验证手机号

        if(RegexUtils.isPhoneInvalid(phone)) //手机号不符合
        {
            return Result.fail("手机号格式不正确");
        }
        // 2 生成验证码
        String code= RandomUtil.randomNumbers(6);
        // 3 保存验证码到redis,设置过期时间为2分钟
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码

        log.debug("验证码为:{}",code);

        return  Result.ok();
    }

    //登录功能
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1 校验手机号
        String phone=loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone))
        {
            return Result.fail("手机号格式错误");
        }
        //2 校验验证码
         //2.1取出验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code=loginForm.getCode();

        //3 不一致则登录失败，否则下一步
        //比较验证码,验证码为空或者不相同则报错
        if(cacheCode==null || !cacheCode.equals(code))
        {
            return  Result.fail("验证码错误");
        }
        //4 用户存在则直接登录

         User user= query().eq("phone",phone).one();
        //5 用户不存在则创建用户，并保存到数据库
       if(user==null)
       {
           user=createWithPhone(phone);
       }

       // 6保存用户信息到redis中
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> Map = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));

        String tokenKey=RedisConstants.LOGIN_USER_KEY+token;

        stringRedisTemplate.opsForHash().putAll(tokenKey,Map);
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);



        return Result.ok(token);
    }

    private User createWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
