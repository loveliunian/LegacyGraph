# Redis 缓存优化方案

**优先级**: 🟡 中等（含 3 个 P0 严重问题）  
**预期收益**: 防止 Redis 雪崩、缓存穿透、缓存击穿

> **实施状态** (2026-07-08，P0 全部 + P2 全部已实现):
> - ✅ §1.1 KEYS→SCAN — `CacheService.evictByPrefix`
> - ✅ §1.2 null 值缓存（占位符 + 按 key 前缀差异化 TTL）— `CacheService.put/get`
> - ✅ §1.2 GraphCacheInvalidator 批量失效（6→1 evictByPatterns）
> - ✅ §2.5 Redis 连接池调优 — `application.yml`
> - ✅ §2.6 缓存 TTL 随机化（±10% 抖动）— `CacheService.ttlWithJitter()`
> - ✅ §3.1 缓存预热 — `CacheWarmer.java`（新文件）
> - ✅ §3.4 向量缓存版本化 — `VectorRetrievalService` (embeddingModel + corpusVersion)
> - ⬜ §1.3 分布式锁防击穿（Redisson）— 需引入依赖
> - ⬜ §2.7 大对象 GZIP 压缩 — 需改序列化层
> - ⬜ §3.2 SemanticCache 迁移 Redis — 架构调整
> - ⬜ §3.3 缓存监控指标 — 需 Prometheus

---

## 一、🔴 严重问题（必须立即修复）

### 1.1 evictByPrefix 使用 KEYS 命令导致 Redis 阻塞

**位置**: `service/CacheService.java:158`

**问题**:
```java
public void evictByPrefix(String prefix) {
    Set<String> keys = redisTemplate.keys(fullKey(prefix) + "*");  // O(N) 阻塞操作
    if (!keys.isEmpty()) {
        redisTemplate.delete(keys);
    }
}
```

**影响**: 
- `KEYS` 是 O(N) 操作，会阻塞 Redis 主线程
- 高并发时可能触发缓存雪崩
- 调用链：`GraphCacheInvalidator.java:33-44` 一次失效操作调用 6 次

**修复方案**: 使用 SCAN 替代 KEYS
```java
public void evictByPrefix(String prefix) {
    try {
        ScanOptions options = ScanOptions.scanOptions()
            .match(fullKey(prefix) + "*")
            .count(1000)
            .build();
        Cursor<String> cursor = redisTemplate.scan(options);
        List<String> keysToDelete = new ArrayList<>();
        while (cursor.hasNext()) {
            keysToDelete.add(cursor.next());
            if (keysToDelete.size() >= 100) {
                redisTemplate.delete(keysToDelete);
                keysToDelete.clear();
            }
        }
        if (!keysToDelete.isEmpty()) {
            redisTemplate.delete(keysToDelete);
        }
    } catch (Exception e) {
        logFailure("evictByPrefix", prefix, e);
    }
}
```

**新增批量失效方法**:
```java
public void evictByPatterns(List<String> patterns) {
    try {
        List<String> allKeys = new ArrayList<>();
        for (String pattern : patterns) {
            ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(1000)
                .build();
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    allKeys.add(cursor.next());
                }
            }
        }
        if (!allKeys.isEmpty()) {
            // 分批删除，每批 100 个
            Lists.partition(allKeys, 100).forEach(redisTemplate::delete);
        }
    } catch (Exception e) {
        logFailure("evictByPatterns", patterns.toString(), e);
    }
}
```

**GraphCacheInvalidator 优化**:
```java
// 当前：6 次独立调用
public void invalidateVersion(String versionId) {
    cacheService.evictByPrefix("graph:" + versionId + ":");
    cacheService.evictByPrefix("graph:");
    cacheService.evictByPrefix("validation-report::" + versionId);
    cacheService.evictByPrefix("report-");
    cacheService.evictByPrefix("vec:search:");
    cacheService.evictByPrefix("validation-report");
}

// 优化：1 次批量调用
public void invalidateVersion(String versionId) {
    List<String> patterns = new ArrayList<>();
    if (versionId != null) {
        String v = versionId.replace("-", "");
        patterns.add("graph:" + v + ":*");
        patterns.add("validation-report::*" + versionId + "*");
    } else {
        patterns.add("graph:*");
        patterns.add("validation-report*");
    }
    patterns.add("report-*");
    patterns.add("vec:search:*");
    cacheService.evictByPatterns(patterns);
}
```

---

### 1.2 缓存穿透风险：null 值未缓存

**位置**: 
- `CacheService.java:66-81` (get 方法)
- `CacheService.java:101-109` (getOrLoad 方法)

**问题**:
- `get()` 返回 null 时不缓存
- `getOrLoad()` 当 loader 返回 null 时也不缓存
- `put()` 拒绝写入 null

**影响**: 恶意或错误请求反复查询不存在的数据，直接穿透到 DB

**修复方案**:
```java
private static final String NULL_PLACEHOLDER = "__NULL__";

// 不同 key 类型使用不同的 null TTL，降低热点数据被穿透的风险
private static final Map<String, Duration> NULL_TTL_BY_PREFIX = Map.of(
    "graph:", Duration.ofMinutes(5),         // 图谱数据，写入后立即可查
    "report-", Duration.ofMinutes(10),        // 报告数据，生成较慢
    "scan-progress:", Duration.ofSeconds(30), // 进度数据，快速变化
    "prompt-templates:", Duration.ofHours(1)  // 模板数据，极少变化
);
private static final Duration DEFAULT_NULL_TTL = Duration.ofMinutes(5);

private Duration getNullTtl(String key) {
    return NULL_TTL_BY_PREFIX.entrySet().stream()
        .filter(e -> key.startsWith(e.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(DEFAULT_NULL_TTL);
}

public <T> T get(String key, Class<T> type) {
    try {
        Object raw = redisTemplate.opsForValue().get(fullKey(key));
        if (raw == null) return null;
        if (NULL_PLACEHOLDER.equals(raw)) return null;  // 命中空值缓存
        // ... 反序列化逻辑
        return objectMapper.convertValue(raw, type);
    } catch (Exception e) {
        logFailure("get", key, e);
        return null;
    }
}

public void put(String key, Object value, Duration ttl) {
    try {
        if (value == null) {
            // 缓存空值，短 TTL
            redisTemplate.opsForValue().set(fullKey(key), NULL_PLACEHOLDER, getNullTtl(key));
            return;
        }
        redisTemplate.opsForValue().set(fullKey(key), value, ttl);
    } catch (Exception e) {
        logFailure("put", key, e);
    }
}

public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
    T cached = get(key, type);
    if (cached != null) return cached;
    
    T loaded = loader.get();
    put(key, loaded, ttl);  // 即使 loaded 为 null 也缓存
    return loaded;
}
```

---

### 1.3 缓存击穿风险：缺乏分布式锁

**位置**: 
- `ScanVersionService.java:329-481` (进度查询)
- `GraphQueryService.java:83-87` (图谱查询)
- `ReportingService.java:98-191` (报告生成)

**问题**: 
- `getScanProgress()` 先读缓存，miss 后查 DB 再写缓存，非原子操作
- 高并发轮询时，多个请求同时 miss，全部打到 DB

**修复方案**: 分布式锁

**方案 A: 简单单飞（本地锁）— ⚠️ 仅单实例有效**

> **适用场景**: 单机部署或开发环境。多实例部署时本地 `ConcurrentHashMap` 无法跨 JVM 共享，必须用方案 B。

```java
private final ConcurrentHashMap<String, CompletableFuture<?>> inflight = new ConcurrentHashMap<>();

public <T> T getOrLoadSingleFlight(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
    T cached = get(key, type);
    if (cached != null) return cached;
    
    CompletableFuture<T> future = new CompletableFuture<>();
    CompletableFuture<?> existing = inflight.putIfAbsent(key, future);
    
    if (existing != null) {
        // 已有请求在加载，等待其结果
        try {
            return (T) existing.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return loader.get();  // 降级：直接加载
        }
    }
    
    try {
        T loaded = loader.get();
        put(key, loaded, ttl);
        future.complete(loaded);
        return loaded;
    } catch (Exception e) {
        future.completeExceptionally(e);
        throw e;
    } finally {
        inflight.remove(key);
    }
}
```

**方案 B: 分布式锁（Redisson）**
```java
// 引入 Redisson
@Bean
public RedissonClient redissonClient(RedisConnectionFactory connectionFactory) {
    Config config = new Config();
    config.useSingleServer()
        .setAddress("redis://" + redisHost + ":" + redisPort)
        .setPassword(redisPassword);
    return Redisson.create(config);
}

public <T> T getOrLoadWithLock(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
    T cached = get(key, type);
    if (cached != null) return cached;
    
    RLock lock = redissonClient.getLock("lock:" + key);
    try {
        if (lock.tryLock(1, 30, TimeUnit.SECONDS)) {
            try {
                // Double-check
                cached = get(key, type);
                if (cached != null) return cached;
                
                T loaded = loader.get();
                put(key, loaded, ttl);
                return loaded;
            } finally {
                lock.unlock();
            }
        } else {
            // 获取锁失败，直接加载
            return loader.get();
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return loader.get();
    }
}
```

---

## 二、🟡 中等问题（建议优化）

### 2.1 LLM 缓存 Key 过长

**位置**: `llm/LlmGateway.java:157, 627`

**问题**: 缓存 key 包含完整 prompt 文本，可能超过 10KB

**修复方案**: 对 prompt 做 SHA256 哈希
```java
private String buildLlmCacheKey(String model, String prompt) {
    String hash = DigestUtils.sha256Hex(prompt).substring(0, 16);
    return "llm:" + model + ":" + hash;
}
```

---

### 2.2 GraphQueryService 缓存 Key 碰撞风险

**位置**: `service/GraphQueryService.java:63-66`

**问题**: 使用 `Integer.toHexString(Arrays.hashCode(params))` 生成 key 后缀，碰撞概率约 1/2^32

**修复方案**: 使用 SHA256
```java
private String graphKey(String versionId, String view, String... params) {
    String v = normalizeVersionId(versionId);
    String paramStr = String.join(":", params);
    String hash = DigestUtils.sha256Hex(paramStr).substring(0, 12);
    return "graph:" + v + ":" + view + ":" + hash;
}
```

---

### 2.3 Neo4j 节点缓存 TTL 过短

**位置**: `repository/Neo4jQueryRepository.java:32`

**问题**: `NODE_CACHE_TTL = Duration.ofSeconds(60)` 对于只读查询太短

**修复方案**:
```java
private static final Duration NODE_CACHE_TTL = Duration.ofMinutes(5);
```

---

### 2.4 Report 缓存 TTL 配置不完整

**位置**: `config/RedisConfig.java:100-102`

**问题**: 只为 `report-migration-readiness` 配置了 1 小时 TTL，其他报告类型缺失

**修复方案**:
```java
// RedisConfig.java:100 后添加
perCache.put("report-confidence-trend", base.entryTtl(Duration.ofHours(2)));
perCache.put("report-test-coverage", base.entryTtl(Duration.ofHours(2)));
perCache.put("report-graph-quality", base.entryTtl(Duration.ofHours(2)));
perCache.put("report-graph-metrics", base.entryTtl(Duration.ofHours(2)));
```

---

### 2.5 Redis 连接池配置偏保守

**位置**: `application.yml:53-57`

**当前配置**:
```yaml
lettuce:
  pool:
    max-active: 8
    max-wait: -1ms
    max-idle: 8
    min-idle: 0
```

**优化配置**:
```yaml
lettuce:
  pool:
    max-active: 16
    max-wait: 2000ms
    max-idle: 8
    min-idle: 4
```

---

### 2.6 缓存 TTL 随机化（防雪崩）

**问题**: 大量缓存 Key 使用相同的 TTL，可能在同一时刻集中过期，导致瞬时请求全部穿透到 DB（缓存雪崩的另一种形式）。

**修复方案**: 在基础 TTL 上增加 ±10% 的随机抖动：
```java
private final ThreadLocalRandom random = ThreadLocalRandom.current();

public void putWithJitter(String key, Object value, Duration baseTtl) {
    // ±10% 随机偏移
    long baseSeconds = baseTtl.toSeconds();
    long jitter = (long) (baseSeconds * 0.1 * (random.nextDouble() * 2 - 1));
    Duration actualTtl = Duration.ofSeconds(baseSeconds + jitter);
    
    redisTemplate.opsForValue().set(fullKey(key), value, actualTtl);
}
```

**适用场景**:
- 图谱节点缓存（`NODE_CACHE_TTL`）：同一版本大量节点同时缓存，集中过期风险高
- 报告缓存（`report-*`）：同一时间生成的多份报告同时过期

---

### 2.7 大对象 GZIP 压缩

**问题**: 图谱查询结果（如 `getUnifiedGraph`）可能返回大量节点和边，JSON 序列化后可达数 MB。直接存入 Redis 占用大量内存。

**修复方案**: 超过阈值的缓存值做 GZIP 压缩：
```java
private static final int COMPRESSION_THRESHOLD_BYTES = 1024;  // 超过 1KB 压缩

public void put(String key, Object value, Duration ttl) {
    try {
        byte[] raw = objectMapper.writeValueAsBytes(value);
        byte[] data = raw;
        
        if (raw.length > COMPRESSION_THRESHOLD_BYTES) {
            data = gzipCompress(raw);
            // 标记为压缩数据，读取时解压
            String compressedKey = fullKey(key) + ":gzip";
            redisTemplate.opsForValue().set(compressedKey, data, ttl);
            return;
        }
        
        redisTemplate.opsForValue().set(fullKey(key), data, ttl);
    } catch (Exception e) {
        logFailure("put", key, e);
    }
}

private byte[] gzipCompress(byte[] data) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
        gos.write(data);
    }
    return bos.toByteArray();
}
```

**预期收益**: JSON 文本压缩率通常 5-10x，大幅降低 Redis 内存占用，特别是在大版本图谱场景。

> **注意**: 需要权衡 CPU（压缩/解压）和内存/网络（Redis 读写）的开销。建议先对图谱查询、报告生成两类数据开启压缩，监控 Redis 内存和 CPU 后决定是否全量覆盖。

---

## 三、🟢 轻微问题（可选优化）

### 3.1 缺乏缓存预热机制

**问题**: 冷启动时所有请求打到 DB<br/>
**建议优先级**: 从 P3 提升至 **P2**，因为每次部署后首次请求的冷启动延迟直接影响用户体验。预热几类高频数据（系统配置、字典、Prompt 模板）成本极低，收益显著。

**修复方案**:
```java
@Component
public class CacheWarmer {
    @Autowired
    private SysConfigService sysConfigService;
    @Autowired
    private SysDictService sysDictService;
    @Autowired
    private PromptTemplateService promptTemplateService;
    
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCache() {
        log.info("Warming up cache...");
        sysConfigService.getAllConfigMap();
        sysDictService.getAllDictMap();
        promptTemplateService.listActive();
        log.info("Cache warm-up completed");
    }
}
```

---

### 3.2 SemanticCache 存储在 PostgreSQL 而非 Redis

**位置**: `qa/SemanticCache.java`

**问题**: QA 语义缓存存储在 `lg_semantic_cache` 表，查询慢于 Redis

**建议**: 混合方案 — Redis 存热点缓存，PG 存持久化数据

---

### 3.3 缺乏缓存监控指标

**问题**: 无法观测缓存命中率、延迟等指标

**修复方案**:
```java
@Bean
public CacheMetricsRegistrar cacheMetricsRegistrar(MeterRegistry registry) {
    return new CacheMetricsRegistrar(registry);
}
```

---

### 3.4 VectorRetrievalService 缓存失效不完整

**位置**: `service/VectorRetrievalService.java:113-118`

**问题**: 有 TTL 但文档更新时未主动失效

**修复**: 在文档更新流程中添加失效钩子。详细实现方案见 [05-图谱查询与写图链路优化.md §五](./05-图谱查询与写图链路优化.md#五向量检索缓存加入模型版本与主动失效)，核心步骤：
1. 缓存 key 包含 `embeddingModel` 和 `corpusVersion`
2. 向量写入完成后发布 `VectorCorpusUpdatedEvent`
3. 监听器精准淘汰 `vec:search:{project}:{version}:*`

---

## 四、验证清单

- [ ] 压测 `evictByPrefix`，确认 Redis 不再阻塞（`SLOWLOG GET` 无 KEYS 命令）
- [ ] 模拟缓存穿透请求，确认 null 值被缓存且 TTL 按 key 前缀差异化
- [ ] 高并发轮询进度接口，确认无缓存击穿
- [ ] 多实例部署场景，验证 Redisson 分布式锁方案 B 生效
- [ ] 监控 Redis 内存使用，确认 LLM 缓存 key 长度合理
- [ ] 验证 TTL 随机抖动生效（相同 key 前缀的多个缓存过期时间不完全相同）
- [ ] 对大版本图谱缓存开启 GZIP 压缩，对比 Redis 内存占用
- [ ] 检查缓存命中率（Prometheus/Grafana）

---

## 五、执行顺序

1. **立即修复** `CacheService.evictByPrefix()` 使用 SCAN 替代 KEYS
2. **立即添加** null 值缓存防止穿透
3. **本周完成** 分布式锁或单飞模式防止击穿
4. **本月完成** 其他 TTL、Key、连接池优化
