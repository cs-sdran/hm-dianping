package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String  CACHE_SHOP_LOGIC_KEY="cache:shop:logic";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;
    public static final String  LOCK_ORDER_KEY="lock:order:";
    public static final  String SECKILL_ORDER="seck:order:";
    public static final  String SECKILL_ORDER_RECORD="seck:order:record:";


    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_FAILED_ORDERS_KEY = "seckill:failed:orders";
    public static final String SECKILL_PENDING_PUBLISH_KEY = "seckill:publish:pending";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String FOLLOWS_KEY="follows:";

}
