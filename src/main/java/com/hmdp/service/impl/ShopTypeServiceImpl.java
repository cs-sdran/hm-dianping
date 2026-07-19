package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        //1查询redis
        List<String> list = stringRedisTemplate.opsForList().range(RedisConstants.SHOP_TYPE, 0, -1);
        //2缓存命中则直接返回
        if (list != null && !list.isEmpty()) {
            List<ShopType> typeList = list.stream()
                    .filter(StrUtil::isNotBlank)
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(typeList);
        }
        //3缓存未命中则查询数据库
        List<ShopType> sort = query().orderByAsc("sort").list();
        //4查询数据库失败则返回错误提示
        if (sort == null) {
            return Result.fail("数据库查询失败");
        }
        //5查询数据库成功则写入redis并返回
        List<String> collect = sort.stream()
                .filter(bean -> !BeanUtil.isEmpty(bean))
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());

        stringRedisTemplate.opsForList().rightPushAll(RedisConstants.SHOP_TYPE, collect);
        //设置过期时间
        stringRedisTemplate.expire(RedisConstants.SHOP_TYPE, RedisConstants.SHOP_TYPE_TTL, TimeUnit.MINUTES);

        return Result.ok(sort);
    }
}
