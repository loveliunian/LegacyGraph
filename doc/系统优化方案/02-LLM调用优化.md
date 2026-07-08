# LLM 调用优化方案

**优先级**: 🔴 显著  
**预期收益**: QA 延迟降低 30-40%，提升 API 稳定性，减少无效 Token 消耗

> **实施状态** (2026-07-08，全部核心项已实现):
> - ✅ §1.1 EnhancedQaAgent 并发化（classify∥HyDE, GraphRAG∥检索）— `EnhancedQaAgent.java`
> - ✅ §1.2 GraphRAG 规划与检索并行 — `EnhancedQaAgent.java`
> - ✅ §1.2 多路召回 fan-out/fan-in（主查询∥变体∥关键词）— `HybridRetrievalService.java`
> - ✅ §2.1 ReasoningModelClient 指数退避重试（3 次, IO/429/5xx）— `ReasoningModelClient.java`
> - ✅ §2.2 RetryTemplate 退避策略优化（fixed→exponential）— 同上
> - ✅ §3.3 LLM 缓存 Key 区分模型版本 — 已是现有行为
> - ✅ §3.1 PromptTemplateLoader 缓存 — `@Cacheable` + `@CacheEvict` + RedisConfig TTL 6h（已有代码）
> - ✅ §5.2 中间结果流式推送 — 已有 11 个 `thinking` 阶段事件（cache_check→generating）
> - ⬜ §3.2 JSON 本地修复优先于 LLM 自校正 — 待实施
> - ⬜ §4.1 上下文截断策略 — 待实施
> - ⬜ §5.1 模型降级策略（CircuitBreaker）— 待引入 resilience4j
> - ⬜ §5.3 Token 用量监控 — 需 Prometheus 基础设施

---

## 一、QA 串行调用并发化（最高优先级）

### 1.1 EnhancedQaAgent 意图/改写/HyDE 串行

**位置**: `qa/EnhancedQaAgent.java:120-182`

**问题**:
```java
// 当前：串行执行，总耗时 = T1 + T2 + T3
IntentClassification intent = intentClassifier.classify(query);      // ~1s
RewrittenQuery rewritten = queryRewriter.rewrite(query, intent);     // ~1s
String hydeDoc = hydeGenerator.generateHypotheticalDocument(query);  // ~1s
```

**影响**: 三者无依赖关系，串行执行浪费 2-3s

**修复方案**:
```java
// 优化：并发执行，总耗时 = max(T1, T2, T3)
CompletableFuture<IntentClassification> intentFuture = 
    CompletableFuture.supplyAsync(() -> intentClassifier.classify(query), taskExecutor);

CompletableFuture<RewrittenQuery> rewriteFuture = 
    CompletableFuture.supplyAsync(() -> queryRewriter.rewrite(query, intent), taskExecutor);

CompletableFuture<String> hydeFuture = 
    CompletableFuture.supplyAsync(() -> hydeGenerator.generateHypotheticalDocument(query), taskExecutor);

// 等待所有完成
CompletableFuture.allOf(intentFuture, rewriteFuture, hydeFuture).join();

IntentClassification intent = intentFuture.join();
RewrittenQuery rewritten = rewriteFuture.join();
String hydeDoc = hydeFuture.join();
```

**注意**: `rewriteFuture` 依赖 `intent`，需要调整：
```java
// 正确依赖关系
CompletableFuture<IntentClassification> intentFuture = ...;
CompletableFuture<String> hydeFuture = ...;  // 独立

CompletableFuture<RewrittenQuery> rewriteFuture = intentFuture.thenApplyAsync(
    intent -> queryRewriter.rewrite(query, intent), taskExecutor);

CompletableFuture.allOf(intentFuture, rewriteFuture, hydeFuture).join();
```

---

### 1.2 GraphRAG 规划与向量召回串行

**位置**: `qa/EnhancedQaAgent.java:188-220`

**问题**:
```java
// 当前：串行
GraphRagPlan plan = plannerAgent.plan(query, intent);           // ~1-2s
HybridRetrievalResult result = hybridRetrievalService.retrieve(query, ...);  // ~1s
```

**修复方案**:
```java
// 优化：并发
CompletableFuture<GraphRagPlan> planFuture = 
    CompletableFuture.supplyAsync(() -> plannerAgent.plan(query, intent), taskExecutor);
CompletableFuture<HybridRetrievalResult> retrievalFuture = 
    CompletableFuture.supplyAsync(() -> hybridRetrievalService.retrieve(query, ...), taskExecutor);

CompletableFuture.allOf(planFuture, retrievalFuture).join();
GraphRagPlan plan = planFuture.join();
HybridRetrievalResult result = retrievalFuture.join();
```

---

## 二、重试机制完善

### 2.1 ReasoningModelClient 无重试

**位置**: `llm/ReasoningModelClient.java:159-219`

**问题**: 推理模型调用无重试，API 抖动直接失败

**修复方案**:
```java
public String callCompletion(String model, String prompt, int maxTokens) {
    return retryTemplate.execute(ctx -> {
        try {
            return doCallCompletion(model, prompt, maxTokens);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    });
}

// 注入 RetryTemplate
private final RetryTemplate retryTemplate = RetryTemplate.builder()
    .maxAttempts(3)
    .exponentialBackoff(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10))
    .retryOn(IOException.class, RuntimeException.class)
    .build();
```

---

### 2.2 RetryTemplate 退避策略优化

**位置**: `config/LlmConfig.java:31-39`

**当前配置**:
```java
return RetryTemplate.builder()
    .maxAttempts(3)
    .fixedBackoff(Duration.ofSeconds(2))  // 固定 2s
    .build();
```

**优化配置**:
```java
return RetryTemplate.builder()
    .maxAttempts(3)
    .exponentialBackoff(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10))
    .retryOn(IOException.class, HttpClientErrorException.TooManyRequests.class)
    .build();
```

**理由**: 指数退避（1s→2s→4s）更适合 API 限流场景

---

## 三、Prompt 效率优化

### 3.1 PromptTemplateLoader DB 查询缓存

**位置**: `llm/PromptTemplateLoader.java:35-46`

**问题**: 每次 `render()` 都查询 DB（`promptTemplateService.getActiveByCode`）

**修复方案**:
```java
@Cacheable(value = "prompt-templates", key = "#code", unless = "#result == null")
public PromptTemplate getActiveByCode(String code) {
    return promptTemplateService.getActiveByCode(code);
}

// 配置 TTL
@Bean
public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofMinutes(5));
    return RedisCacheManager.builder(connectionFactory)
        .withCacheConfiguration("prompt-templates", config)
        .build();
}
```

---

### 3.2 LLM 自校正优先级调整

**位置**: `llm/LlmGateway.java:224-279`

**问题**: JSON 解析失败时直接触发自我修复（再调一次 LLM），成本高

**修复方案**:
```java
// 当前逻辑
try {
    return objectMapper.readTree(cleaned);
} catch (JsonProcessingException e) {
    // 直接触发自校正
    String corrected = selfCorrect(model, prompt, cleaned);
    return objectMapper.readTree(corrected);
}

// 优化逻辑
try {
    return objectMapper.readTree(cleaned);
} catch (JsonProcessingException e) {
    // 先尝试本地修复
    String repaired = repairTruncatedJson(cleaned);
    if (repaired != null) {
        try {
            return objectMapper.readTree(repaired);
        } catch (JsonProcessingException ignored) {
            // 本地修复失败，再触发自校正
        }
    }
    String corrected = selfCorrect(model, prompt, cleaned);
    return objectMapper.readTree(corrected);
}
```

---

### 3.3 LLM 缓存 Key 区分模型版本

**位置**: `task/AiScanOrchestrator.java:1362`

**问题**: `cachedExtract` 按内容哈希缓存，未区分模型版本

**修复方案**:
```java
// 当前
String cacheKey = DigestUtils.sha256Hex(content);

// 优化
String cacheKey = modelId + ":" + DigestUtils.sha256Hex(content);
```

---

## 四、Token 使用效率

### 4.1 上下文截断策略

**位置**: `qa/EnhancedQaAgent.java:254-259`

**问题**: 上下文拼接可能产生超长 prompt（向量文档 + 图谱节点 + GraphRAG + 变更影响 + 列举补充）

**修复方案**:
```java
private String buildRetrievalContext(QueryContext ctx) {
    StringBuilder sb = new StringBuilder();
    int tokenBudget = 8000;  // 预留 8K tokens
    
    // 按优先级拼接
    sb.append(truncateToTokens(ctx.getVectorDocs(), tokenBudget * 0.4));  // 40%
    sb.append(truncateToTokens(ctx.getGraphNodes(), tokenBudget * 0.3));  // 30%
    sb.append(truncateToTokens(ctx.getGraphRagContext(), tokenBudget * 0.2));  // 20%
    sb.append(truncateToTokens(ctx.getChangeImpact(), tokenBudget * 0.1));  // 10%
    
    return sb.toString();
}

private String truncateToTokens(String text, int maxTokens) {
    int estimatedTokens = text.length() / 4;  // 粗略估算
    if (estimatedTokens <= maxTokens) return text;
    return text.substring(0, maxTokens * 4);
}
```

---

## 五、模型降级与流式优化

### 5.1 模型降级策略

**位置**: `llm/ReasoningModelClient.java` / `llm/LlmGateway.java`

**问题**: 当前主模型（如 deepseek-reasoner）超时或 429 限流时，只有重试（§2.1），没有降级模型。连续失败导致 QA 完全不可用。

**修复方案**:
```java
@Component
public class LlmGateway {
    // 主模型和降级模型映射
    private static final Map<String, String> FALLBACK_MODELS = Map.of(
        "deepseek-reasoner", "deepseek-chat",   // 推理模型 → 聊天模型
        "deepseek-chat", "qwen-turbo"            // 聊天模型 → 轻量模型
    );
    
    private final CircuitBreaker llmCircuitBreaker = CircuitBreaker.of("llm-api",
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slidingWindowSize(10)
            .build()
    );
    
    public String callWithFallback(String model, String prompt, int maxTokens) {
        return Try.ofSupplier(() -> llmCircuitBreaker.decorateSupplier(
            () -> doCall(model, prompt, maxTokens)
        ).get())
        .recover(throwable -> {
            String fallback = FALLBACK_MODELS.get(model);
            if (fallback != null) {
                log.warn("Model {} degraded, fallback to {}", model, fallback);
                return doCall(fallback, prompt, maxTokens);
            }
            throw new RuntimeException("No fallback for " + model, throwable);
        })
        .get();
    }
}
```

**注意**: 需要引入 `resilience4j-circuitbreaker` 依赖（Spring Boot 3 原生支持，只需加 starter）。

---

### 5.2 中间结果流式推送

**位置**: `qa/EnhancedQaAgent.java:120-240`（answerStream）

**问题**: 虽然最终回答用了 SSE 流式输出，但"意图识别 → 查询改写 → HyDE → 规划 → 召回"阶段的中间等待对用户不可见，体感延迟高。

**修复方案**: 在 `answerStream()` 中推送中间状态事件：
```java
public Flux<QaStreamEvent> answerStream(String query, QaContext ctx) {
    return Flux.create(sink -> {
        sink.next(QaStreamEvent.status("正在分析问题意图..."));
        IntentClassification intent = intentClassifier.classify(query);
        sink.next(QaStreamEvent.status("意图识别完成：" + intent.getType()));
        
        sink.next(QaStreamEvent.status("正在优化查询..."));
        // ... HyDE + 改写（并发化）
        
        sink.next(QaStreamEvent.status("正在检索相关知识..."));
        // ... 召回
        
        sink.next(QaStreamEvent.status("正在生成回答..."));
        // ... LLM 生成
    });
}

// QaStreamEvent 类型
public record QaStreamEvent(
    EventType type,       // STATUS, TOKEN, REFERENCE, DONE, ERROR
    String content,
    Map<String, Object> metadata
) {}
```

**前端配合**: 在 QA 面板显示 `[思考中...]` 状态条，提升交互体感。

---

### 5.3 Token 用量监控与告警

**位置**: 新增 `llm/LlmMetricsCollector.java`

**问题**: 当前无 Token 用量统计，无法评估优化效果和成本。

**修复方案**:
```java
@Component
public class LlmMetricsCollector {
    private final MeterRegistry meterRegistry;
    
    public void recordCall(String model, String agent, int promptTokens, int completionTokens, long latencyMs) {
        // Token 用量
        meterRegistry.counter("llm.tokens.total",
            "model", model,
            "agent", agent,
            "type", "prompt"
        ).increment(promptTokens);
        
        meterRegistry.counter("llm.tokens.total",
            "model", model,
            "agent", agent,
            "type", "completion"
        ).increment(completionTokens);
        
        // 调用延迟
        meterRegistry.timer("llm.call.latency",
            "model", model,
            "agent", agent
        ).record(latencyMs, TimeUnit.MILLISECONDS);
        
        // 调用次数
        meterRegistry.counter("llm.call.count",
            "model", model,
            "agent", agent
        ).increment();
    }
}
```

**告警规则建议**:
| 指标 | 阈值 | 说明 |
|------|------|------|
| `llm.call.latency` P95 | > 10s | LLM 响应异常慢 |
| `llm.call.count` 失败率 | > 10% | API 故障 |
| `llm.tokens.total` 日消耗 | 自定义预算 | 成本控制 |

---

### 5.4 PromptTemplateLoader TTL 修正

**位置**: `llm/PromptTemplateLoader.java:35-46`（对应原文 §3.1）

**补充说明**: 原文建议 TTL 为 5 分钟。考虑到 Prompt 模板变更低频（通常只在版本发布时修改），建议改为：

```java
@Bean
public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofMinutes(30));  // 30 分钟（原建议 5 分钟过短）
    return RedisCacheManager.builder(connectionFactory)
        .withCacheConfiguration("prompt-templates", config)
        .build();
}
```

**配合主动失效**:
```java
// PromptTemplateService 更新模板后触发
@CacheEvict(value = "prompt-templates", key = "#code")
public void updateTemplate(String code, String content) {
    // 更新 DB
}
```

---

## 六、验证清单

- [ ] QA 场景压测，对比优化前后 P95 延迟
- [ ] 模拟 API 抖动，验证重试机制生效
- [ ] 模拟主模型持续 5xx，验证熔断 + 降级模型生效
- [ ] QA 面板验证中间状态流式展示（前端配合）
- [ ] 检查 Prompt 缓存命中率
- [ ] 监控 Token 使用量，确认无超长 prompt
- [ ] 配置 Token 用量告警（Prometheus/Grafana）
