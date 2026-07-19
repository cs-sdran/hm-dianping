下面这张表很重要，建议你记住。

功能	    Redis数据类型	Spring 操作
短信验证码	String	opsForValue()
登录 Token	Hash	opsForHash()
商户缓存	    String	opsForValue()
缓存空值	    String	opsForValue()
分布式锁	    String	setIfAbsent()
秒杀库存	    String	opsForValue() / Lua
一人一单判断	Set	opsForSet()
点赞	    Set / ZSet	opsForSet() / opsForZSet()
点赞排行榜	ZSet	opsForZSet()
关注/共同关注	Set	opsForSet()
Feed 流	    ZSet	opsForZSet()
附近商户	    GEO	opsForGeo()
签到	    Bitmap	opsForValue().setBit()
UV 统计	    HyperLogLog	opsForHyperLogLog()

---

## Bug 复盘：登录连环崩——`new LoginInterceptor()` 引发的 Spring IoC 失效

### 故障现象

1. 发送验证码 → Redis 查不到
2. 登录 → 提示"服务器异常"
3. 登录后点个人主页 → 又被踢回登录页

### 根因：一句话

**在 `MvcConfig` 中用 `new LoginInterceptor()` 手动创建拦截器，绕过了 Spring IoC 容器，导致拦截器内 `@Autowired stringRedisTemplate` 始终为 `null`，运行时抛 NullPointerException。**

### 因果链

```
MvcConfig 中 new LoginInterceptor()
    → Spring 不参与实例化，@Autowired 全部失效
    → stringRedisTemplate = null
    → preHandle() 中调用 opsForHash() 触发 NPE
    → 全局异常处理器返回 "服务器异常"
    → 前端收到失败响应，跳回登录页
```

同时 `/user/me` 被写在排除列表里，拦截器不执行 → `UserHolder.getUser()` 从 ThreadLocal 拿不到用户 → 前端认为未登录。这也是现象③的附加原因。

验证码没进 Redis 则是 `application.yaml` 中 Redis 未启动或连接配置不对，`sendCode()` 方法本身代码无问题。

### 修复（MvcConfig.java）

| 改前 | 改后 | 原因 |
|------|------|------|
| `new LoginInterceptor()` | `@Autowired LoginInterceptor` + 注入使用 | 让 Spring 管理实例，依赖注入才能生效 |
| 排除列表含 `/user/me` | 移除 | `/me` 依赖拦截器填充 ThreadLocal |
| 未声明 `addPathPatterns` | 加 `addPathPatterns("/**")` | 显式声明范围 |

### 面试话术要点

**Q: 为什么 `@Component` 标记了，`new` 出来还是拿不到注入？**
Spring 只能管理自己创建的 Bean。`new` 是 JVM 层面的对象创建，完全绕过容器，`@Autowired`、`@Value`、AOP 代理全部失效。

**Q: 怎么预防？**
- 注册拦截器/过滤器只用 `@Autowired` 注入，不用 `new`
- 单测 Mock 依赖可提前暴露 NPE
- Code Review 看到 `new XxxInterceptor()` 直接打回

**Q: ThreadLocal 在这个项目里干什么？为什么不用 Session？**
Token + Redis + ThreadLocal 的方案天然无状态，支持水平扩展；Session 方案在集群下需要额外处理 Session 共享。ThreadLocal 用完必须在 `afterCompletion` 里 `remove()`，否则有内存泄漏风险。

---历时多轮深入探讨，我们从“请求怎么进来的”一路追到了“数据怎么落库的”，覆盖了黑马点评项目（及Java Web开发）的**核心骨架**。

为了方便你复盘，我把所有知识点按照 **“请求链路”** 和 **“核心工具”** 两条主线，梳理成了一份结构化的“知识地图”。你可以把它当作查漏补缺的目录。

---

### 🗺️ 一、核心架构与请求链路（贯穿始终的主线）

这组知识点串联了从 **APP 点击** 到 **后端响应** 的完整血脉，是理解项目的基石。

1.  **登录校验全流程（核心主线）**：
    - **登录（发证）**：`/user/login` 被拦截器**放行**，校验验证码后生成 **Token（UUID）**，
    - 将用户信息存入 **Redis**（Key: `login:token:xxx`），返回 Token 给前端。
    - **后续请求（验票）**：前端每次请求在 **Header（请求头）** 中携带 Token。
    - 后端通过 `request.getHeader("authorization")` 获取。
    - **双拦截器链**（执行顺序严格区分）：
        - **RefreshTokenInterceptor（order=0）**：拦截所有请求，根据 Token 查 Redis，
        - 查到则**存入 ThreadLocal** + **续期（expire）**，无论是否查到都放行。
        - **LoginInterceptor（order=1）**：只拦截业务接口（如 `/order/**`），
        - 检查 ThreadLocal 是否有用户，没有则拦截返回 401。
    - **善后清理**：请求结束后，在 `afterCompletion` 中**必须 `ThreadLocal.remove()`**，防止内存泄漏。

2.  **核心组件职责与写法**：
    - **拦截器（Interceptor）**：实现 `HandlerInterceptor`，重写 `preHandle`（业务逻辑）、`afterCompletion`（清理）。**必须加 `@Component`** 交给 Spring 管理（否则 `@Autowired` 注入为 null）。
    - **配置类（`MvcConfig`）**：实现 `WebMvcConfigurer`，重写 `addInterceptors`，利用 **注册器（`InterceptorRegistry`）** 将拦截器注册进去，并配置 `addPathPatterns`（拦截）和 `excludePathPatterns`（放行）。
    - **ThreadLocal（线程局部变量）**：`UserHolder` 封装，用于在**同一请求线程**中透传用户信息，避免层层传参。**特性**：不同线程数据隔离。
    - **Session（会话）**：`request.getSession()` 基于 Cookie 中的 `JSESSIONID` 查找服务器内存 Map。**缺点**：分布式下无法共享，所以黑马点评用 Redis 替代。

---

### 🗄️ 二、数据存储与操作（DAO 层与缓存层）

这组知识点解决了“数据从哪取、怎么存”的问题。

1.  **MyBatis-Plus（数据库操作）**：
    - 实体类与数据库表映射。
    - **`QueryWrapper` 与 `LambdaQueryWrapper`**：构建 SQL 查询条件。**前者**字段名用**字符串**（`eq("name", "张")`），**后者**用**Lambda 表达式**（`eq(User::getName, "张")`），推荐后者（编译期检查）。
    - **`#{}` vs `${}`**：`#{}` 预编译防 SQL 注入（绝大多数场景），`${}` 字符串拼接（仅用于动态表名/列名）。
2.  **Redis 与工具集成**：
    - 用 **Hash** 结构存储用户信息（`StringRedisTemplate.opsForHash().putAll(key, map)`）。
    - **`BeanUtil.beanToMap()`**（Hutool）：将对象转为 Map，**配合 Redis 存储**。
        - **致命陷阱**：`setFieldValueEditor` 执行**优先级高于** `setIgnoreNullValue`。若值为 null 直接 `toString()` 会空指针，必须判空：`fieldValue != null ? fieldValue.toString() : null`。

---

### 🛠️ 三、Java 基础工具与规范（夯实内功）

这组知识点是日常增删改查的“锅碗瓢盆”。

1.  **集合框架（List/Set/Map）**：
    - **List（`ArrayList`）**：有序可重复，`get(i)`、`add(e)`、`isEmpty()`。
    - **Set（`HashSet`）**：无序不可重复，`contains(o)` 判重。
    - **Map（`HashMap`）**：键值对，`put(k,v)`、`get(k)`、`containsKey(k)`。
2.  **对象转换工具（BeanUtil vs BeanUtils）**：
    - **Apache/Spring `BeanUtils`**：专注**属性拷贝**（`copyProperties`）。
    - **Hutool `BeanUtil`**：**全能型**，支持 `beanToMap`、`mapToBean` 等。
    - **`BeanUtil.copyProperties(user, UserDTO.class)`**：创建新目标对象，拷贝同名属性。**用途**：隐藏密码等敏感字段（数据脱敏）。
3.  **常用注解与依赖注入**：
    - **`@Resource`（Java 标准）**：默认**按名称（byName）**注入，找不到再按类型。
    - **`@Autowired`（Spring）**：默认**按类型（byType）**注入，配合 `@Qualifier` 按名称。
    - **Lombok `@Slf4j`**：生成日志对象，支持 `log.debug("{}", param)` 占位符；误用 `@CommonsLog` 会导致参数个数报错。
4.  **代码规范**：
    - **魔法值**：硬编码的数字/字符串（如 `6`、`"login:code:"`）。必须抽取为 `public static final` 常量或枚举。

---

### ⚠️ 四、避坑指南（高亮重点）

这些都是你在复现项目时极大概率会遇到的问题，单独拎出来提醒自己：

1.  **拦截器注入失效**：拦截器类**必须加 `@Component`**，配置类中用 `@Autowired` 注入。**绝不能用 `new`**。
2.  **双拦截器执行顺序**：`RefreshTokenInterceptor` 必须 `order(0)` 最先执行，`LoginInterceptor` 必须 `order(1)` 后执行，否则 ThreadLocal 为空导致全部拦截。
3.  **BeanUtil 空指针陷阱**：`beanToMap` 中 `setFieldValueEditor` 先执行，要**显式判空**（`null` 返回 `null`），才能让 `setIgnoreNullValue` 生效。
4.  **Token 获取不到**：确认前端是否把 Token 放在 **Header** 的 `Authorization` 中。`getHeader("authorization")` 大小写不敏感。
5.  **登录接口被拦截**：检查 `excludePathPatterns` 是否添加了 `/user/login` 和 `/user/code`。

---

### 📌 复盘建议

你可以按照 **“请求来了 -> 拦截器(1) -> 拦截器(2) -> Controller -> Service -> Mapper(MyBatis-Plus) -> Redis/MySQL -> 响应返回 -> 清理”** 这条时间线，闭上眼睛把每个节点的代码逻辑默念一遍。

如果在默念过程中发现某个节点（比如“拦截器到底怎么注册到 Spring 的”）卡住了，直接回看对应的那一段对话就行。你现在对整个链路的理解已经超过 80% 的初学者了，加油！🚀

*复盘时间：2026-07-03*

---

## Bug 复盘：店铺类型缓存三连击——filter 写反 + 空值误判 + 返回类型错误

### 故障现象

调用 `/shop-type/list` 接口，首次请求返回空数组 `[]`，后续请求永远返回空数组，数据库明明有数据但始终不生效。

### 根因：一句话

**三个独立 Bug 叠加形成死锁：filter 谓词反转导致数据被过滤，`!= null` 误判导致首次查 Redis 返回空列表后永远不穿透到数据库，最后还把 JSON 字符串当成对象返回给前端。**

### 因果链

```
首次请求 → Redis 无缓存 → range() 返回空列表 []
    → stream.filter(StrUtil::isBlank) 保留空白、丢弃非空白 → typeList = []
    → typeList != null 为 true（空列表 ≠ null）
    → 直接返回 Result.ok([]) ← 永远走不到数据库查询
```

即使侥幸走到写缓存分支：
```
数据库查询 sort = [实体1, 实体2, ...]
    → stream.filter(BeanUtil::isEmpty) 保留空对象、丢弃正常对象 → collect ≈ []
    → leftPushAll 推入空列表（且顺序反转）
    → return Result.ok(collect) ← 返回的是 List<String>（JSON 串），而非 List<ShopType>
```

### 修复（ShopTypeServiceImpl.java）

| 位置 | 改前 | 改后 | 原因 |
|------|------|------|------|
| filter 谓词（读缓存） | `StrUtil::isBlank` | `StrUtil::isNotBlank` | `isBlank` 保留空白→丢弃有效 JSON，语义反了 |
| 命中判断 | `if (typeList != null)` | `if (list != null && !list.isEmpty())` | Redis 空结果返回空列表非 null，null 检查永远为 true |
| filter 谓词（写缓存） | `BeanUtil::isEmpty` | `bean -> !BeanUtil.isEmpty(bean)` | 同上，保留空对象而丢弃正常实体 |
| 写入顺序 | `leftPushAll` | `rightPushAll` | leftPush 会反转 sort 排序，rightPush 保持原序 |
| 返回值 | `Result.ok(collect)` | `Result.ok(sort)` | collect 是 `List<String>`（JSON 字符串），sort 是 `List<ShopType>` |

### 面试话术要点

**Q: `filter` 的谓词返回 true 是保留还是丢弃？**
Stream 的 `filter` 保留返回 `true` 的元素。`StrUtil.isBlank("")` 对空字符串返回 `true`，所以 `filter(StrUtil::isBlank)` 实际保留了空字符串、丢弃了有效数据——恰好与意图相反。

**Q: 为什么 `range()` 返回空列表而不是 null？**
Spring Data Redis 的 `opsForList().range()` 在 key 不存在时返回 `Collections.emptyList()`，这是框架约定。用 `!= null` 做缓存命中判断是无效的——空列表永远非 null。必须用 `isEmpty()`。

**Q: `leftPushAll` 和 `rightPushAll` 区别？**
`leftPushAll` 将集合元素逐个从左侧推入，最终顺序与集合相反（先入的被挤到右边）；`rightPushAll` 保持与集合一致的顺序。缓存读写的顺序应保持一致，否则排序会乱。

*复盘时间：2026-07-04*
