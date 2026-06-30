# LegacyGraph Redis 应用实施方案

> 本文分析 LegacyGraph 当前 Redis 使用现状，梳理可落地的应用场景，并给出分阶段实施方案与代码骨架。
>
> *文档生成日期：2026-06-30*

---

## 📊 一、现状结论

**结论：项目已"接好水管，但没接龙头" —— 依赖与配置就绪，代码零使用。**

| 维度 | 现状 |
|------|------|
| Maven 依赖 | ✅ `spring-boot-starter-data-redis` 已在 `backend/pom.xml` 引入（基于 Lettuce） |
| 连接配置 | ✅ `application.yml` 已配 `spring.data.redis`（host/port/password、`database: 11`、Lettuce 连接池） |
| 运维/部署文档 | ✅ `运维手册.md`、`部署文档.md` 已把 Redis 列为外部依赖（6.x/7+，用途标注"缓存"） |
| 代码使用 | ❌ **无任何使用**：无 `@EnableCaching`、无 `RedisTemplate`/`StringRedisTemplate` 注入、无 `@Cacheable`/`@CacheEvict`、无 `RedisConfig` |
| 健康检查 | ⚠️ `运维手册.md` 提到 `actuator/health` 期望出现 `"redis": {"status":"UP"}`，但当前 Redis 未参与任何业务逻辑 |

> 配置位置：`backend/src/main/resources/application.yml` 第 37–49 行。
> 由于已引入 starter 且配了密码，**当前若 Redis 不可达，Spring Boot 默认的 RedisHealthIndicator 可能让 `/health` 变为 DOWN**，这是接入前需要先确认的运维点。

### 关键发现：LLM 缓存已"半成品"

`PromptRunRepository` 已存在为缓存设计的查询，但从未被调用：

```java
@Select("SELECT id FROM lg_prompt_run WHERE input_hash = #{inputHash} " +
        "AND status = 'success' ORDER BY created_at DESC LIMIT 1")
Long findCachedRunIdByInputHash(String inputHash);
```

且 Phase 0 改造后 `LlmGateway` 已经为每次调用写入 `inputHash`（SHA-256）。**这意味着 LLM 结果缓存的去重键已就绪，只差一层缓存读取**，是 Redis 接入收益最高、风险最低的切入点。

---

## 🎯 二、可落地的应用场景

按"收益/工作量/风险"排序，分为三档。

### 🔴 高价值（建议优先）

#### 1. LLM 调用结果缓存（去重 + 降本）

**问题**：`LlmGateway.callWithTemplate` 每次都真实请求 LLM。相同代码/文档片段在「扫描后 AI 编排」「重复扫描」「手动重跑」中会重复调用，既慢又烧 token。

**方案**：以 `inputHash`（已有）为 key，缓存解析后的结构化结果（`parsedOutput`）。
- 命中 → 直接反序列化返回，跳过 LLM 与 DB 写入（或仅记一条 `status=CACHE_HIT` 审计）。
- 未命中 → 正常调用，成功后回填缓存。

**收益**：扫描闭环里文档/代码 AI 抽取重复率高，可显著降低 token 成本与扫描耗时；与现有 `PromptRun.inputHash` 设计天然契合。

**Key 设计**：`lg:llm:result:{templateName}:{inputHash}`，TTL 7–30 天。

---

#### 2. JWT 登出黑名单 / 会话失效

**问题**：当前 JWT 是无状态的，`AuthController.logout` 是**空操作**（`return Result.success()`），登出后旧 token 在过期前依然有效；用户被禁用/改密后已签发 token 也无法立即失效。这是安全缺口。

**方案**：登出时把 token（或其 jti）写入 Redis 黑名单，TTL = token 剩余有效期；`JwtAuthenticationFilter` 校验时检查黑名单。

**收益**：补齐"真正登出"和"强制下线"能力，安全性提升。

**Key 设计**：`lg:auth:blacklist:{tokenSha256}`，TTL = token 剩余秒数。
（进阶：`lg:auth:user-token-version:{userId}` 做"全端下线"，改密/禁用时自增版本号。）

---

#### 3. 扫描进度缓存 + 前端轮询减压

**问题**：`ScanVersionService.getScanProgress` 每次都查 `lg_scan_task` 全表并在内存聚合百分比；前端创建扫描页**高频轮询**该接口。多个客户端同时盯一个扫描时，DB 压力被放大。

**方案**：
- `ProjectScanner` / `AiScanOrchestrator` 每完成一个子任务，把进度快照写入 Redis（`completedTasks/totalTasks/status/各子任务状态`）。
- `getScanProgress` 优先读 Redis，未命中再回源 DB 并回填。
- 进阶：用 Redis Pub/Sub + SSE 把进度推送给前端，替代轮询。

**收益**：降低轮询对 PG 的压力，进度更实时。

**Key 设计**：`lg:scan:progress:{versionId}`（Hash 或 JSON），TTL 24h；完成后可延长或落库后清理。

---

### 🟠 中价值

#### 4. 图谱查询 / 报告结果缓存

**问题**：`GraphQueryController` 的 `api-chain`、`table-impact`、`feature-view`、`business-view`、`unified` 等接口走 Neo4j 多跳查询；`ReportExportController` 的迁移就绪度、置信度趋势、覆盖率、图谱质量等是重聚合。这些在**同一扫描版本内结果不变**，却会被反复查看。

**方案**：按 `(projectId, versionId, 查询参数)` 缓存只读结果，TTL 较长；**图谱发生写入（合并/审核确认/重新扫描）时按 versionId 维度失效**。

**收益**：图谱/报告页打开更快，减轻 Neo4j 与 PG 聚合压力。

**Key 设计**：`lg:graph:{versionId}:{view}:{paramHash}`、`lg:report:{projectId}:{versionId}:{type}`，TTL 1–24h；失效用 `lg:graph:{versionId}:*` 模式或维护索引集合。

---

#### 5. 系统配置 / 字典缓存

**问题**：`SysConfigService.getValue/getAllConfigMap`、`SysDictService` 属"读多写极少"的热点数据，每次访问都查 DB。

**方案**：`@Cacheable` 缓存，写操作 `@CacheEvict`。

**收益**：实现简单、立竿见影，是验证 Redis 缓存栈是否打通的最佳"试金石"。

**Key 设计**：`lg:config:{configKey}`、`lg:config:all`、`lg:dict:{dictType}`，TTL 1h + 写时清除。

---

#### 6. LLM Provider / ChatModel 配置多实例一致性

**问题**：`LlmGateway.chatModelCache` 与 `LlmProviderService` 用进程内 `ConcurrentHashMap` 缓存 ChatModel。`clearCache()` 只清当前 JVM；**多实例部署时，在节点 A 切换默认 Provider，节点 B 仍用旧配置**。

**方案**：用 Redis Pub/Sub 广播"配置变更"事件，各实例收到后清本地缓存；或把"当前默认 Provider 版本号"放 Redis，调用前比对。

**收益**：为水平扩容做准备（单实例部署可暂缓）。

---

### 🟡 低价值 / 锦上添花

#### 7. 分布式锁：防止扫描重复触发

**问题**：`ScanController.start` / `resume` 无并发保护，同一 `versionId` 被连点或多实例同时触发会重复扫描、重复写图谱。

**方案**：基于 Redis 的分布式锁（`SET key value NX PX ttl`，或引入 Redisson），key = `lg:lock:scan:{versionId}`，扫描期间持有。

**收益**：保证扫描任务幂等/互斥。

#### 8. 接口限流

对登录、LLM Agent、问答（`/qa/ask`）等做基于 Redis 的滑动窗口/令牌桶限流，防滥用与 token 失控。Key：`lg:ratelimit:{api}:{userId}`。

#### 9. 向量检索结果缓存

`VectorRetrievalService.semanticSearch` 对**相同 query + version** 可缓存 Top-K 结果（QA 高频提问场景）。Key：`lg:vec:{versionId}:{queryHash}`，TTL 较短（数据可能更新）。

---

## 🗺️ 三、场景优先级总览

| 优先级 | 场景 | 工作量 | 收益 | 风险 |
|--------|------|--------|------|------|
| 🔴 高 | 1. LLM 结果缓存 | 小 | 降本提速，复用现有 inputHash | 低（缓存不命中即回源） |
| 🔴 高 | 2. JWT 登出黑名单 | 小-中 | 补安全缺口 | 低 |
| 🔴 高 | 3. 扫描进度缓存 | 中 | 降轮询压力 | 低 |
| 🟠 中 | 5. 配置/字典缓存 | 小 | 简单见效（试金石） | 低 |
| 🟠 中 | 4. 图谱/报告缓存 | 中 | 读加速 | 中（需正确失效） |
| 🟠 中 | 6. Provider 多实例一致性 | 中 | 为扩容准备 | 中 |
| 🟡 低 | 7. 扫描分布式锁 | 小-中 | 幂等互斥 | 低 |
| 🟡 低 | 8. 接口限流 | 中 | 防滥用 | 低 |
| 🟡 低 | 9. 向量检索缓存 | 小 | QA 提速 | 中（时效性） |

---

## 🚀 四、分阶段实施路线

### Phase A：打基础 + 试金石（0.5–1 人日）

1. 新增 `RedisConfig`：配置 `RedisTemplate`/`StringRedisTemplate`、JSON 序列化（`GenericJackson2JsonRedisSerializer`）、`CacheManager`，开启 `@EnableCaching`，统一 key 前缀 `lg:` 与默认 TTL。
2. 落地**场景 5（配置/字典缓存）**作为最小验证：跑通"@Cacheable 命中、@CacheEvict 失效、Redis 宕机降级回源"。
3. 确认 `actuator/health` 中 Redis 状态符合预期（决定是否需要把 Redis 设为非强依赖）。

### Phase B：高价值业务接入（2–3 人日）

4. **场景 1 LLM 结果缓存**：在 `LlmGateway.callWithTemplate` 调用前查缓存、成功后回填（详见下方骨架）。
5. **场景 2 JWT 登出黑名单**：`logout` 写黑名单、`JwtAuthenticationFilter` 校验黑名单。
6. **场景 3 扫描进度缓存**：编排器写进度快照、`getScanProgress` 读缓存优先。

### Phase C：读加速与扩容准备（按需）

7. 场景 4 图谱/报告缓存（含版本级失效）。
8. 场景 6 Provider 多实例一致性（Pub/Sub）。
9. 场景 7/8/9 分布式锁、限流、向量缓存。

---

## 🧩 五、关键实现骨架

> 以下为方案示意，落地时遵循项目现有分层与 `开发规范文档.md`。

### 5.1 RedisConfig（Phase A）

```java
@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory cf) {
        RedisTemplate<String, Object> t = new RedisTemplate<>();
        t.setConnectionFactory(cf);
        t.setKeySerializer(new StringRedisSerializer());
        t.setHashKeySerializer(new StringRedisSerializer());
        t.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        t.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        t.afterPropertiesSet();
        return t;
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory cf) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("lg:")
                .entryTtl(Duration.ofHours(1))
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer()));
        return RedisCacheManager.builder(cf).cacheDefaults(base).build();
    }
}
```

### 5.2 LLM 结果缓存（场景 1，Phase B）

在 `LlmGateway.callWithTemplate` 中，渲染 prompt、算出 `inputHash` 之后、真实调用之前：

```java
String inputHash = sha256(prompt);
String cacheKey = "llm:result:" + templateName + ":" + inputHash;

// 1) 查缓存（仅结构化类型缓存；String 类型可选）
if (responseType != String.class) {
    Object cached = redisTemplate.opsForValue().get(cacheKey);
    if (cached != null) {
        return objectMapper.convertValue(cached, responseType); // 命中：跳过 LLM
    }
}

// 2) ... 现有调用与解析逻辑 ...

// 3) 成功且通过 schema 校验后回填
redisTemplate.opsForValue().set(cacheKey, result, Duration.ofDays(7));
```

> 注意：缓存读写要 try/catch 包裹，**Redis 异常时降级为直接调用 LLM**，不能让缓存层故障阻断主流程（与现有"失败显式返回"风格一致）。

### 5.3 JWT 登出黑名单（场景 2，Phase B）

```java
// logout：写黑名单，TTL = token 剩余有效期
long ttl = jwtUtil.getExpirationDateFromToken(token).getTime() - System.currentTimeMillis();
if (ttl > 0) {
    stringRedisTemplate.opsForValue()
        .set("auth:blacklist:" + sha256(token), "1", Duration.ofMillis(ttl));
}

// JwtAuthenticationFilter：校验时先查黑名单
if (Boolean.TRUE.equals(stringRedisTemplate.hasKey("auth:blacklist:" + sha256(token)))) {
    // 视为未认证
}
```

### 5.4 配置缓存（场景 5，Phase A）

```java
@Cacheable(cacheNames = "config", key = "#configKey", unless = "#result == null")
public String getValue(String configKey) { ... }

@CacheEvict(cacheNames = "config", allEntries = true)
public boolean updateValue(String configKey, String value) { ... }
```

---

## ⚠️ 六、注意事项

1. **缓存降级**：所有缓存读写必须容错——Redis 不可用时回源 DB/LLM，**绝不阻断业务**。建议把缓存逻辑收敛到独立的 `CacheService`，统一异常处理。
2. **失效正确性**：图谱/报告缓存必须在写操作（合并、审核确认、重新扫描）时按 `versionId` 失效，否则会读到陈旧数据。优先按版本维度组织 key 以便批量失效。
3. **序列化**：DTO 走 JSON 序列化（`GenericJackson2JsonRedisSerializer`），避免 JDK 序列化的兼容性问题；注意 `BigDecimal`/`LocalDateTime` 与项目 Jackson 配置（`GMT+8`、`non_null`）保持一致。
4. **Key 规范**：统一前缀 `lg:` + 业务域，分号分层（`lg:llm:result:...`），便于排查与按域清理。
5. **DB 隔离**：当前配 `database: 11`，与其它系统共享实例时注意隔离。
6. **健康检查**：接入后确认 `actuator/health` 的 Redis 指示器行为，决定 Redis 是否作为强依赖（影响启动与就绪探针）。
7. **敏感数据**：LLM 输入已经过 `PiiMaskingService` 脱敏，但缓存的是**结构化输出**，仍应评估是否含敏感信息再决定 TTL 与是否加密。

---

## 📌 七、一句话总结

Redis 在本项目**已配未用**。最该先做的三件事：

1. **LLM 结果缓存** —— 复用已就绪的 `inputHash`，降本提速，收益最高。
2. **JWT 登出黑名单** —— 补上当前"登出是空操作"的安全缺口。
3. **扫描进度缓存** —— 给高频轮询减压。

建议从**配置缓存**这个最小场景先打通 Redis 缓存栈，再推进上述三项高价值业务接入。
