package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.support.IsolatedRedis;
import org.junit.jupiter.api.*;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** GEO 使用真实 Redis；店铺 Mapper 使用模拟对象。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopGeoServiceTest {
    private IsolatedRedis instance;
    private StringRedisTemplate redis;
    private ShopServiceImpl shops;
    private ShopMapper mapper;

    @BeforeAll
    void start() throws Exception {
        instance = new IsolatedRedis();
        redis = instance.template();
    }

    @AfterAll
    void stop() throws Exception {
        if (instance != null) {
            instance.close();
        }
    }

    @BeforeEach
    void setUp() {
        instance.clear();
        shops = new ShopServiceImpl();
        mapper = mock(ShopMapper.class);
        ReflectionTestUtils.setField(shops, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(shops, "baseMapper", mapper);
    }

    @SuppressWarnings("unchecked")
    private List<Shop> shopList(Result result) {
        assertTrue(result.getSuccess(), result.getErrorMsg());
        return (List<Shop>) result.getData();
    }

    private Shop shop(long id, long type, double longitude) {
        return new Shop().setId(id).setTypeId(type).setName("shop-" + id).setX(longitude).setY(30D);
    }

    private void database(List<Shop> records) {
        when(mapper.selectList(any())).thenReturn(records);
        when(mapper.selectBatchIds(anyCollection())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            List<Shop> found = records.stream().filter(shop -> ids.contains(shop.getId()))
                    .collect(Collectors.toList());
            Collections.reverse(found); // 数据库返回顺序故意不同，验证服务恢复距离顺序。
            return found;
        });
    }

    @Test
    void geoAutoImportsFiltersFiveKilometersAndPaginatesInDistanceOrder() {
        List<Shop> records = Arrays.asList(shop(90, 1, 120), shop(10, 1, 120.001), shop(80, 1, 120.002),
                shop(20, 1, 120.003), shop(70, 1, 120.004), shop(30, 1, 120.005), shop(60, 1, 120.006),
                shop(99, 1, 121));
        database(records);
        List<Shop> first = shopList(shops.queryShopByType(1, 1, 120D, 30D));
        List<Shop> second = shopList(shops.queryShopByType(1, 2, 120D, 30D));
        assertEquals(Arrays.asList(90L, 10L, 80L, 20L, 70L), first.stream().map(Shop::getId).collect(Collectors.toList()));
        assertEquals(Arrays.asList(30L, 60L), second.stream().map(Shop::getId).collect(Collectors.toList()));
        assertTrue(shopList(shops.queryShopByType(1, 3, 120D, 30D)).isEmpty());
        assertTrue(first.get(0).getDistance() < 1);
        for (int i = 1; i < first.size(); i++) {
            assertTrue(first.get(i).getDistance() > first.get(i - 1).getDistance());
        }
        verify(mapper, times(1)).selectList(any());
    }

    @Test
    void geoTypeIsolationAndEmptyIndexReturnEmptyLists() {
        redis.opsForGeo().add("shop:geo:2", new Point(120, 30), "2");
        when(mapper.selectList(any())).thenReturn(Collections.emptyList());
        assertTrue(shopList(shops.queryShopByType(1, 1, 120D, 30D)).isEmpty());
        verify(mapper, never()).selectBatchIds(anyCollection());
    }

    @Test
    void invalidGeoArgumentsFailBeforeReadingDatabase() {
        assertFalse(shops.queryShopByType(1, 0, 120D, 30D).getSuccess());
        assertFalse(shops.queryShopByType(0, 1, 120D, 30D).getSuccess());
        assertFalse(shops.queryShopByType(1, 1, 120D, null).getSuccess());
        assertFalse(shops.queryShopByType(1, 1, 181D, 30D).getSuccess());
        assertFalse(shops.queryShopByType(1, 1, 120D, 86D).getSuccess());
        assertFalse(shops.queryShopByType(1, 1, Double.NaN, 30D).getSuccess());
        verifyNoInteractions(mapper);
        assertTrue(redis.keys("*").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void absentCoordinatesUseOriginalDatabasePagination() {
        when(mapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<Shop> page = invocation.getArgument(0);
            QueryWrapper<Shop> query = invocation.getArgument(1);
            assertTrue(query.getSqlSegment().contains("type_id ="));
            assertEquals(2L, page.getCurrent());
            assertEquals(5L, page.getSize());
            page.setRecords(Collections.singletonList(shop(1, 1, 120)));
            return page;
        });
        assertEquals(1, shopList(shops.queryShopByType(1, 2, null, null)).size());
        assertTrue(redis.keys("*").isEmpty());
    }

    @Test
    void newShopInvalidatesWholeTypeIndexSoOlderShopsAreNotLost() {
        Shop older = shop(90, 1, 120);
        Shop added = shop(10, 1, 120.001);
        redis.opsForGeo().add("shop:geo:1", new Point(120, 30), "90");
        when(mapper.insert(added)).thenReturn(1);
        assertTrue(shops.saveShop(added).getSuccess());
        assertFalse(redis.hasKey("shop:geo:1"));
        database(Arrays.asList(older, added));
        assertEquals(2, shopList(shops.queryShopByType(1, 1, 120D, 30D)).size());
    }

    @Test
    void shopUpdateInvalidatesOldAndNewTypesAndExistingDetailCaches() {
        Shop old = shop(90, 1, 120);
        Shop changed = shop(90, 2, 120.001);
        redis.opsForGeo().add("shop:geo:1", new Point(120, 30), "90");
        redis.opsForGeo().add("shop:geo:2", new Point(120, 30), "10");
        redis.opsForValue().set("cache:shop:90", "old");
        redis.opsForValue().set("cache:shop:logic90", "old");
        when(mapper.selectById(90L)).thenReturn(old);
        when(mapper.updateById(changed)).thenReturn(1);
        assertTrue(shops.updateShop(changed).getSuccess());
        assertFalse(redis.hasKey("shop:geo:1"));
        assertFalse(redis.hasKey("shop:geo:2"));
        assertFalse(redis.hasKey("cache:shop:90"));
        assertFalse(redis.hasKey("cache:shop:logic90"));
    }

    @Test
    void unsuccessfulShopWriteDoesNotInvalidateExistingGeoData() {
        Shop original = shop(90, 1, 120);
        redis.opsForGeo().add("shop:geo:1", new Point(120, 30), "90");
        when(mapper.insert(any())).thenReturn(0);
        assertFalse(shops.saveShop(original).getSuccess());
        assertTrue(redis.hasKey("shop:geo:1"));
        when(mapper.selectById(90L)).thenReturn(original);
        when(mapper.updateById(any())).thenReturn(0);
        assertFalse(shops.updateShop(new Shop().setId(90L).setName("new name")).getSuccess());
        assertTrue(redis.hasKey("shop:geo:1"));
    }
}
