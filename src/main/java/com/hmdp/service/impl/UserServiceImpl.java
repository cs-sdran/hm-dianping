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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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

    @Resource
    private  StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone) {
        //判断手机号是否正确
        if(RegexUtils.isPhoneInvalid(phone))
        {
            return Result.fail("手机号格式错误");
        }

        String s = RandomUtil.randomNumbers(6);//生成验证码
        stringRedisTemplate.opsForValue().set
                (RedisConstants.LOGIN_CODE_KEY+phone,s,RedisConstants.LOGIN_CODE_TTL,
                        TimeUnit.MINUTES);//将验证码保存至redis,设置两分钟TTL


        log.info("验证码为："+s);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        //接收手机号
        String phone = loginForm.getPhone();
        //接受验证码
        String code = loginForm.getCode();

        //1.校验手机号格式
        if(RegexUtils.isPhoneInvalid(phone))//手机号不合法
        {
            return Result.fail("手机号不合法");
        }
        //2.获取该手机号对应的验证码
        String cachecode =
                stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);

        //3.比较验证码
        if(cachecode == null || !cachecode.equals(code))
        {
            return Result.fail("验证码错误");}

        //4.验证码校验正确后根据手机号获得用户
        User user = new User();
        user=lambdaQuery().eq(User::getPhone,phone).one();
        if(user==null)//创建用户
        {
           user=createnewuser(phone);
        }

        //5.将user转换为userdto,并将userdto转换为Map类型
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user,userDTO);
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true)
                .setFieldValueEditor(
                        (fieldName, fieldValue) -> fieldValue.toString()
                ));

        //6.生成随机UUID
        String uuid=UUID.randomUUID().toString(true);
        String tokenKey = RedisConstants.LOGIN_USER_KEY + uuid;

       stringRedisTemplate.opsForHash().putAll(tokenKey,map);
       stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);

        return Result.ok(uuid);
    }

    @Override
    public Result logout(String token) {
        stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        return Result.ok();
    }

    private User createnewuser(String phone)
    {
        User user = new User();
        user.setPhone(phone);

        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));

        save(user);

        return user;
    }
}
