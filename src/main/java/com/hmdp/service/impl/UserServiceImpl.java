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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
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

    @Resource
    private Clock businessClock;
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
        user=query().eq("phone",phone).one();
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

    @Override
    public Result sign() {
        //获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if(user==null)
            return Result.fail("请先登录");
        Long userId = user.getId();
        //获取当日日期
        LocalDate now = LocalDate.now(businessClock);
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM:"));

        String key=RedisConstants.USER_SIGN_KEY+userId.toString()+format;
        //获取今日是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        //在bitmap中的位置需要-1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);

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

    @Override
    public Result signCount() {  //签到天数记录
        //使用bitfield读取连续几位
        //首先获取今日日期
        //获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if(user==null)
            return Result.fail("请先登录");
        Long userId = user.getId();
        //获取当日日期
        LocalDate now = LocalDate.now(businessClock);
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM:"));

        String key=RedisConstants.USER_SIGN_KEY+userId.toString()+format;
        int count = 0 ;
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));

        if(result==null||result.isEmpty())
        {
            return Result.ok(0);
        }
        //获取值
        Long num = result.get(0);
        if(num==null||num==0)
            return Result.ok(0);

        while(true)
        {
            //获取数字的最后一位
            if((1&num)==1)
            {
                count++;
                num>>>=1;
            }
            else {
                break;
            }
        }
        return Result.ok(count);
    }
}
