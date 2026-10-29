package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.convert.Convert;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow>  implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;


    @Override
    public Result isFollow(Long isFollowUserId) {//判断当前用户是否关注另一个用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        Integer count = query().eq("user_id", user.getId()).eq("follow_user_id", isFollowUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result follow(Long followUserId, boolean isFollow) {
        // isFollow 为 true 时关注，为 false 时取消关注。
        UserDTO user = UserHolder.getUser();
        if (user == null)
            return Result.fail("请先登录");

        if (!isFollow)//取关
        {
            QueryWrapper<Follow> wrapper = new QueryWrapper<>();
            wrapper.eq("user_id", user.getId());
            wrapper.eq("follow_user_id", followUserId);
            // 已经没有这条关注关系时，也视为取消关注成功。
            remove(wrapper);
            //取消关注后移除redis中的记录
            stringRedisTemplate.opsForSet().remove(
                    RedisConstants.FOLLOWS_KEY + user.getId(), followUserId.toString());
            return Result.ok("取关成功");
        }

        //关注
        Integer count = query()
                .eq("user_id", user.getId())
                .eq("follow_user_id", followUserId)
                .count();
        if (count > 0) {
            stringRedisTemplate.opsForSet().add(
                    RedisConstants.FOLLOWS_KEY + user.getId(), followUserId.toString());
            return Result.ok("已关注该用户");
        }

        Follow follow = new Follow();
        follow.setUserId(user.getId());
        follow.setFollowUserId(followUserId);
        follow.setCreateTime(LocalDateTime.now());
        boolean success;
        try {
            success = save(follow);
        } catch (DuplicateKeyException exception) {
            // 另一个请求可能已插入相同关系；确认关系存在后，才按已关注处理。
            Integer existingCount = query()
                    .eq("user_id", user.getId())
                    .eq("follow_user_id", followUserId)
                    .count();
            if (existingCount > 0) {
                stringRedisTemplate.opsForSet().add(
                        RedisConstants.FOLLOWS_KEY + user.getId(), followUserId.toString());
                return Result.ok("已关注该用户");
            }
            throw exception;
        }
        if (!success) {
            return Result.fail("关注失败");
        }

        stringRedisTemplate.opsForSet().add(
                RedisConstants.FOLLOWS_KEY + user.getId(), followUserId.toString());
        return Result.ok("关注成功");
    }

    @Override
    public Result followCommons(Long otherUserId) {//查询和他人的共同关注
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        String myid = RedisConstants.FOLLOWS_KEY + currentUser.getId();

        String otherid = RedisConstants.FOLLOWS_KEY + otherUserId;

        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(myid, otherid);//查出交集

        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> list = Convert.toList(Long.class, intersect);//转换为long

        List<User> users = userService.listByIds(list);

        List<UserDTO> userDTOS = users.stream().map(user -> {
            UserDTO userDTO = new UserDTO();
            BeanUtil.copyProperties(user, userDTO);
            return userDTO;
        }).collect(Collectors.toList());

        return Result.ok(userDTOS);

    }

}
