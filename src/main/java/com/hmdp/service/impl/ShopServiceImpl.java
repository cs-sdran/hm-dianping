package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final Logger log = LoggerFactory.getLogger(ShopServiceImpl.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (typeId == null || typeId <= 0 || current == null || current <= 0) {
            return Result.fail("商铺类型和页码必须大于0");
        }
        if (x == null && y == null) {
            Page<Shop> page = page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE),
                    new QueryWrapper<Shop>().eq("type_id", typeId).orderByAsc("id"));
            return Result.ok(page.getRecords());
        }
        if (!validCoordinates(x, y)) {
            return Result.fail("请提供有效的经度和纬度");
        }

        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        ensureGeoIndex(typeId.longValue());
        long from = (current.longValue() - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        long end = from + SystemConstants.DEFAULT_PAGE_SIZE;
        // 兼容 Redis 3.2，使用 GEORADIUS；距离返回单位为米。
        GeoResults<RedisGeoCommands.GeoLocation<String>> nearby = stringRedisTemplate.opsForGeo().radius(
                key, new Circle(new Point(x, y), new Distance(5000, RedisGeoCommands.DistanceUnit.METERS)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance().sortAscending().limit(end));
        if (nearby == null || nearby.getContent().size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>();
        Map<Long, Double> distances = new LinkedHashMap<>();
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = nearby.getContent();
        for (int index = (int) from; index < content.size(); index++) {
            GeoResult<RedisGeoCommands.GeoLocation<String>> item = content.get(index);
            Long id = Long.valueOf(item.getContent().getName());
            ids.add(id);
            distances.put(id, item.getDistance().getValue());
        }
        Map<Long, Shop> shopsById = new HashMap<>();
        for (Shop shop : baseMapper.selectBatchIds(ids)) {
            shopsById.put(shop.getId(), shop);
        }
        List<Shop> shops = new ArrayList<>();
        for (Long id : ids) {
            Shop shop = shopsById.get(id);
            if (shop != null && Long.valueOf(typeId).equals(shop.getTypeId())) {
                shop.setDistance(distances.get(id));
                shops.add(shop);
            }
        }
        return Result.ok(shops);
    }

    private void ensureGeoIndex(Long typeId) {
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return;
        }
        // 首次查询或索引失效时，自动导入该类型的全部店铺，无需手动执行初始化测试。
        List<Shop> shops = baseMapper.selectList(new QueryWrapper<Shop>().eq("type_id", typeId));
        Map<String, Point> locations = new LinkedHashMap<>();
        for (Shop shop : shops) {
            if (validCoordinates(shop.getX(), shop.getY())) {
                locations.put(shop.getId().toString(), new Point(shop.getX(), shop.getY()));
            } else {
                log.warn("跳过无效坐标的商户，商户 ID：{}", shop.getId());
            }
        }
        if (!locations.isEmpty()) {
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    private boolean validCoordinates(Double x, Double y) {
        return x != null && y != null && Double.isFinite(x) && Double.isFinite(y)
                && x >= -180 && x <= 180 && y >= -85.05112878 && y <= 85.05112878;
    }

    @Override
    public Result saveShop(Shop shop) {
        if (shop == null || shop.getTypeId() == null || shop.getTypeId() <= 0
                || !validCoordinates(shop.getX(), shop.getY())) {
            return Result.fail("商铺类型和经纬度不合法");
        }
        if (!save(shop)) {
            return Result.fail("保存商铺失败");
        }
        stringRedisTemplate.delete(RedisConstants.SHOP_GEO_KEY + shop.getTypeId());
        return Result.ok(shop.getId());
    }

    @Override
    public Shop getById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("商户id不能为空");
        }

        return cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_LOGIC_KEY,
                id, Shop.class, RedisConstants.LOCK_SHOP_KEY, baseMapper::selectById,
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }

    @Override
    public Result updateShop(Shop shop) {
        if (shop == null || shop.getId() == null) {
            return Result.fail("商户id不能为空");
        }

        Shop oldShop = baseMapper.selectById(shop.getId());
        if (oldShop == null) {
            return Result.fail("商户不存在");
        }
        Long typeId = shop.getTypeId() == null ? oldShop.getTypeId() : shop.getTypeId();
        Double x = shop.getX() == null ? oldShop.getX() : shop.getX();
        Double y = shop.getY() == null ? oldShop.getY() : shop.getY();
        if (typeId == null || typeId <= 0 || !validCoordinates(x, y)) {
            return Result.fail("商铺类型和经纬度不合法");
        }

        // 首先更新数据库，然后删除缓存
        boolean success = updateById(shop);
        if (!success) {
            return Result.fail("商户不存在");
        }

        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_LOGIC_KEY + shop.getId());

        // 类型或位置变动后使相关 GEO 索引失效，下次附近查询从数据库完整重建。
        Set<String> geoKeys = new LinkedHashSet<>();
        geoKeys.add(RedisConstants.SHOP_GEO_KEY + oldShop.getTypeId());
        geoKeys.add(RedisConstants.SHOP_GEO_KEY + typeId);
        stringRedisTemplate.delete(geoKeys);

        return Result.ok();
    }



    //获得互斥锁
    @Override
    public boolean trylock(String Key, String token) {
        return cacheClient.tryLock(Key, token);
    }

    @Override
    public void unlock(String Key, String token) {
        cacheClient.unlock(Key, token);
    }



    //设计一个将数据库数据转化为逻辑过期格式，并写入redis的方法
    //传过来的信息：商铺id,逻辑过期时间，逻辑过期单位
    public Result ToLogic(Long shopId, Long logicTime, TimeUnit timeUnit) {
        if (shopId == null || logicTime == null || logicTime <= 0 || timeUnit == null) {
            return Result.fail("商户 ID 和逻辑过期时间不合法");
        }
        String redisKey = RedisConstants.CACHE_SHOP_LOGIC_KEY + shopId;
        Shop shop = baseMapper.selectById(shopId);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(redisKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("未查询到商户");
        }
        cacheClient.setWithLogicalExpire(redisKey, shop, logicTime, timeUnit);
        return Result.ok();
    }




}
