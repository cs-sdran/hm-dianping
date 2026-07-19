package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {//缓存穿透代码
       // Shop shop = queryByPassThrough(id);
        Shop shop = queryByMutex(id);
        if(shop==null)
            return Result.fail("店铺不存在");

        return Result.ok(shop);
    }
    public Shop queryByMutex(Long id)//缓存穿透代码+缓存击穿互斥锁代码
    {
        //1查询redis
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        String shopInfo = stringRedisTemplate.opsForValue().get(key);
        //2若存在则直接返回
        if(StrUtil.isNotBlank(shopInfo))
        {
            Shop shop = JSONUtil.toBean(shopInfo,Shop.class);
            return shop;
        }
        //判断查询出来的是否为空值，不为空值说明是“”，即为空白缓存
        if(shopInfo!=null)
        {
            return null;
        }
        //3不存在则查询数据库
        Shop shop = null;
        try {
            String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
            boolean isLock = tryLock(lockKey);
            if(!isLock)//获取锁失败
            {
                Thread.sleep(50);
                return queryByMutex(id);
            }
            //获取锁成功
            shop = getById(id);
            //4查询数据库为空则返回
            if (shop == null) {
                //将空值写入缓存
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);

                return null;
            }
            //5查询数据库成功则写入redis
            String jsonStr = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(key,jsonStr,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(key);//释放锁
        }

        return shop;
    }


    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return  Result.fail("商铺id不能为空");
        }
        //1更新数据库
        updateById(shop);
        //2删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+shop.getId());
        return  Result.ok();
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key)
    {
        stringRedisTemplate.delete(key);
    }


}
