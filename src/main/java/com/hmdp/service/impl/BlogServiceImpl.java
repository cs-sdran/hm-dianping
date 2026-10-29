package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        // 作者使用当前登录用户，覆盖请求中传入的用户 ID。
        blog.setUserId(user.getId());
        boolean success = save(blog);
        if (!success) {
            return Result.fail("发布失败");
        }

        // follow_user_id 是被关注的作者，user_id 才是接收动态的粉丝。
        List<Follow> followers = followService.list(new QueryWrapper<Follow>()
                .eq("follow_user_id", user.getId()));
        long timestamp = System.currentTimeMillis();
        for (Follow follower : followers) {
            // 每位粉丝一个 ZSet；成员是笔记 ID，分数是本次发布的时间戳。
            stringRedisTemplate.opsForZSet().add(
                    RedisConstants.FEED_KEY + follower.getUserId(),
                    blog.getId().toString(), timestamp);
        }

        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogById(Long id) { //根据id查询blog,并根据作者id查询相关信息
        Blog blog = getById(id);
        if(blog==null)
            return Result.fail("blog不存在");
        //获得blog后，根据其中的用户id查询信息
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if(user==null)
        {
            return Result.fail("用户不存在");
        }
        if(user.getIcon()!=null)
        blog.setIcon(user.getIcon());

        if(user.getNickName()!=null)
        blog.setName(user.getNickName());


        filllikedstatus(blog);

        return Result.ok(blog);
    }



    //判断用户有没有点过赞
    public boolean isLikedBlog(Long blogId, Long userId)
    {
        //即判断用户id是否出现在blog的点赞列表中，点赞列表使用zset存储
        //key redis.constant score 时间戳  用户Id
        Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + blogId, userId.toString());
        if(score==null)
            return  false;

        return true;
    }

    //用户点赞，若点过赞，则取消点赞，否则点赞
    @Override
    public Result LikeBlog(long id) {
        //传入blogid
        if(getById(id)==null)
            return Result.fail("笔记不存在");
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        //判断用户是否点过赞
        boolean likedBlog = isLikedBlog(id, userId);
        //若点过赞，则数据库-1 否则+1
        if(likedBlog) //已经点过赞,将用户从点赞列表中移除
        {
            if(getById(id).getLiked()<=0)
                return Result.fail("点赞数已经为0");


            boolean update = update().eq("id", id).setSql("liked=liked-1").gt("liked", 0).update();//数据库-1
            if(!update)
            {
                return Result.fail("数据库操作失败");
            }
            stringRedisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKED_KEY+id,userId.toString());

            return Result.ok("点赞-1");
        }


        boolean update = update().eq("id", id).setSql("liked=liked+1").update();
        if(!update)
        {
           return Result.fail("数据库操作失败");
        }

        stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY+id,userId.toString(),System.currentTimeMillis());

        return Result.ok("点赞+1");

    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            if(user!=null){
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());}
            filllikedstatus(blog);
        });
        return Result.ok(records);
    }

    @Override
    //通过blogid获取点赞前五位的头像昵称id
    public Result queryBlogLikes(long id) {

        Blog blog = getById(id);
        if(blog==null)
            return Result.fail("blog不存在");
        // 获取点赞最早的前 5 位，按 Redis 返回的顺序组装结果。
        Set<String> userIds = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0, 4);
        if (userIds == null || userIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<UserDTO> userDTOS = new ArrayList<>();
        for (String userId : userIds) {
            User user = userService.getById(Long.valueOf(userId));
            if (user == null) {
                continue;
            }
            UserDTO userDTO = new UserDTO();
            userDTO.setId(user.getId());
            userDTO.setIcon(user.getIcon());
            userDTO.setNickName(user.getNickName());
            userDTOS.add(userDTO);
        }
        return Result.ok(userDTOS);
    }



    //当前用户点赞状态
    private void filllikedstatus(Blog blog)
    {
        blog.setIsLike(false);
        UserDTO user = UserHolder.getUser();
        if(user==null)
            return;
        boolean likedBlog = isLikedBlog(blog.getId(), user.getId());
        if(likedBlog)
        {
            blog.setIsLike(true);
            return;
        }
       return;
    }

    @Override
    public Result queryBlogOfFollow(long max, Integer offset) { //查看关注的发布的笔记
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        if (max < 0 || offset == null || offset < 0) {
            return Result.fail("分页参数不正确");
        }

        Set<ZSetOperations.TypedTuple<String>> set = stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(RedisConstants.FEED_KEY + user.getId(), 0, max, offset, 2);
        if (set == null || set.isEmpty()) {
            ScrollResult result = new ScrollResult();
            result.setList(Collections.emptyList());
            // 没有读取到新记录，保留本次查询的位置。
            result.setMinTime(max);
            result.setOffset(offset);
            return Result.ok(result);
        }

        List<Long> longs = new ArrayList<>();
        long minTime = Long.MAX_VALUE;
        int sameTimeCount = 0;
        for (ZSetOperations.TypedTuple<String> tuple : set) {
            longs.add(Long.valueOf(tuple.getValue()));
            long timestamp = tuple.getScore().longValue();
            if (timestamp == minTime) {
                sameTimeCount++;
            } else {
                minTime = timestamp;
                sameTimeCount = 1;
            }
        }

        // 时间边界未变时，累计之前跳过的数量，避免同时间记录跨多页后重复。
        int nextOffset = minTime == max ? offset + sameTimeCount : sameTimeCount;

        // 按 Redis 返回的顺序查询笔记；已删除的笔记跳过。
        List<Blog> blogs = new ArrayList<>();
        for (Long id : longs) {
            Blog blog = getById(id);
            if (blog == null) {
                continue;
            }

            Long authorId = blog.getUserId();
            if (authorId != null) {
                User author = userService.getById(authorId);
                if (author != null) {
                    blog.setName(author.getNickName());
                    blog.setIcon(author.getIcon());
                }
            }

            filllikedstatus(blog);
            blogs.add(blog);
        }

        // 分页位置根据原始 Redis 记录计算，已删除的笔记同样计入已读数量。
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setMinTime(minTime);
        result.setOffset(nextOffset);
        return Result.ok(result);

    }
}
