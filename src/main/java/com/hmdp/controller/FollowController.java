package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId)
    {
        return followService.isFollow(followUserId);
    }

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") boolean isFollow)
    {
        return followService.follow(followUserId,isFollow);
    }

    @GetMapping("/common/{id}")
    public Result GetCommonFollows(@PathVariable("id")Long otherUserId)
    {
        return followService.followCommons(otherUserId);
    }

}
