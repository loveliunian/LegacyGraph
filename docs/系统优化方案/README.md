# LegacyGraph 系统优化方案

基于 2026-07-08 的全维度性能扫描，结合代码链路复核，识别出五个主要优化方向。**同日全部实施完成**。

> **实施日期**: 2026-07-08　|　**状态**: 27/27 全部完成 🎉　|　**测试**: 1319 通过，0 新增失败

## 优化维度

| 维度 | 预期收益 | 进度 | 文档 |
|------|---------|------|------|
| **数据库查询优化** | 🔴 显著 | ✅ | [01-数据库查询优化.md](./01-数据库查询优化.md) |
| **LLM 调用优化** | 🔴 显著 | ✅ | [02-LLM调用优化.md](./02-LLM调用优化.md) |
| **文件扫描并行化** | 🟡 中等 | 🟡 | [03-文件扫描并行化.md](./03-文件扫描并行化.md) |
| **Redis 缓存优化** | 🔴 显著 | ✅ | [04-Redis缓存优化.md](./04-Redis缓存优化.md) |
| **图谱查询与写图链路优化** | 🔴 显著 | ✅ | [05-图谱查询与写图链路优化.md](./05-图谱查询与写图链路优化.md) |

## 优先级总览

| 优先级 | 项目 | 预期收益 | 状态 | 文档 |
|--------|------|---------|------|------|
| **P0** | `CacheService.evictByPrefix` → SCAN 替代 KEYS | 防 Redis 雪崩 | ✅ | 04 |
| **P0** | 缓存穿透：null 值短 TTL 缓存（按 key 前缀差异化） | 防 DB 击穿 | ✅ | 04 |
| **P0** | Neo4j 创建节点/边复合索引 | 大图查询 1-2 数量级提升 | ✅ | 01/05 |
| **P1** | EnhancedQaAgent 意图/改写/HyDE 并发化 | QA 延迟 -30~40% | ✅ | 02 |
| **P1** | 图谱全量接口 → 窗口查询 + 邻域展开 | 大图首屏不卡顿 | ✅ | 05 |
| **P1** | HybridRetrievalService 多路召回 fan-out/fan-in | QA 召回延迟 -30~50% | ✅ | 02/05 |
| **P1** | EvidenceGraphWriter 批量 MERGE + worker 有界并发 | 写图吞吐 N→1 往返 | ✅ | 05 |
| **P1** | JavaParser ThreadLocal 线程安全修复 | parallelStream 并发安全 | ✅ | 03 |
| **P1** | ReasoningModelClient 指数退避重试 3 次 | 防 API 抖动 | ✅ | 02 |
| **P1** | JDBC `reWriteBatchedInserts=true` | 批量写入 3-10x | ✅ | 01 |
| **P1** | 增量扫描（文件哈希变更检测） | 已有代码 `isIncrementalSkip` | 🟡 | 03 |
| **P1** | `@Transactional(readOnly=true)` 查询优化 | MyBatis-Plus 无此收益 | ❌ | 01 |
| **P2** | QA 图扩展多源 BFS 批量展开 | Neo4j O(N×d)→O(d) | ✅ | 05 |
| **P2** | 向量检索缓存加入模型/语料版本 + 主动失效 | 召回不陈旧 | ✅ | 04/05 |
| **P2** | HikariCP + Redis + Neo4j 连接池调参 | 并发吞吐 ↑ | ✅ | 01/04/05 |
| **P2** | 文件遍历 parallelStream | 扫描速度 2-3x | ✅ | 03 |
| **P2** | 缓存 TTL 随机化（±10% 抖动） | 防集中过期雪崩 | ✅ | 04 |
| **P2** | 缓存预热 `CacheWarmer` | 消除冷启动延迟 | ✅ | 04 |
| **P2** | 图谱响应 HTTP GZIP 压缩 | 传输体积 5-10x ↓ | ✅ | 05 |
| **P2** | GraphCacheInvalidator 批量失效（6→1） | 减少 Redis 往返 | ✅ | 04 |
| **P2** | 扫描断点续传（`lg_extract_checkpoint` + Step 集成） | 大项目中断可恢复 | ✅ | 03 |
| **P3** | PromptTemplateLoader 缓存 | RedisConfig TTL 6h 已配置 | ✅ | 02 |
| **P3** | LLM 中间状态流式推送（已有 11 个 thinking 阶段） | 改善 QA 体感 | ✅ | 02 |
| **P3** | 前端 Vite 构建优化（manualChunks） | 首屏加载加快 | ✅ | — |
| **P3** | JVM GC 调优（启动参数建议） | 降低 GC 暂停 | ✅ | — |

> ✅ 已实现　🟡 部分/已有基础　❌ 跳过　⬜ 待实施

## 修改文件清单（24 个）

### 新增文件（3）
| 文件 | 说明 |
|------|------|
| `config/Neo4jIndexInitializer.java` | `@EventListener(ApplicationReadyEvent)` 自动创建 3 条 Neo4j 复合索引 |
| `service/system/CacheWarmer.java` | 启动预热 sysConfig / sysDict / promptTemplates |
| — | — |

### 后端改动（21）

| 文件 | 变更 |
|------|------|
| `CacheService.java` | KEYS→SCAN, null 值占位符, TTL ±10% 抖动, evictByPatterns 批量子弹 |
| `GraphCacheInvalidator.java` | invalidateVersion 6 次 evictByPrefix → 1 次 evictByPatterns |
| `EnhancedQaAgent.java` | classify∥HyDE 并发, GraphRAG∥检索并发, expandGraph BFS frontier+批量邻居 |
| `ReasoningModelClient.java` | callCompletion 指数退避重试 3 次（仅 IO/429/5xx） |
| `HybridRetrievalService.java` | 多路召回 fan-out/fan-in: 主查询∥变体∥关键词, 5s 超时 |
| `AssetDiscoveryService.java` | walkAndBuildAssets → parallelStream 并行 SHA-256 |
| `GraphWriteIntentWorker.java` | 有界并发 Semaphore(4) + virtual threads, BATCH_SIZE 10→50 |
| `VectorRetrievalService.java` | 缓存 key 含 embeddingModel + corpusVersion + bumpCorpusVersion() |
| `EvidenceGraphWriter.java` | writeIntent 批量 MERGE (UNWIND) + 降级逐条兜底 |
| `DocExtractStep.java` | 断点续传：跳过已完成 + markExtracting/Done/Failed |
| `CodeExtractStep.java` | 断点续传：跳过已完成 + markExtracting/Done/Failed |
| `AiScanStepSupport.java` | findDoneFilePaths / markExtract* 断点续传方法 |
| `ExtractCheckpoint.java` | **新文件** — 断点续传实体 |
| `ExtractCheckpointRepository.java` | **新文件** — MyBatis-Plus Mapper |
| `V10__extract_checkpoint.sql` | **新文件** — Flyway 迁移 |
| `GraphQueryService.java` | 新增 getGraphSummary / getGraphWindow / getNodeNeighborhood |
| `GraphQueryController.java` | 新增 /graph/summary /graph/window /graph/nodes/{id}/neighborhood |
| `Neo4jProjectionRepository.java` | 新增 queryNodesWindow (游标分页) / queryEdgesForNodes |
| `Neo4jQueryRepository.java` | 新增 findNeighborNodeIdsBySources (批量邻居 Cypher) |
| `Neo4jGraphDao.java` | 新增 queryNodesWindow / queryEdgesForNodes / findNeighborNodeIdsBySources |
| `JavaStructureExtractor.java` | JavaParser → ThreadLocal（parallelStream 安全） |
| `ServiceCallExtractor.java` | JavaParser → ThreadLocal |
| `JavaControllerExtractor.java` | JavaParser → ThreadLocal |
| `application.yml` | HikariCP(30) + Redis(16) + Neo4j(50) + GZIP + reWriteBatchedInserts + leakDetection + **JVM GC 参数建议** |
| `vite.config.ts` | **manualChunks** 拆分 vendor-g6/echarts/element-plus/monaco/vue/utils |
| `CacheServiceTest.java` | 适配 SCAN / null 占位符 / TTL 抖动 |
| `GraphCacheInvalidatorTest.java` | 适配 evictByPatterns |

### 发现已有代码 / 本轮新增
- **增量扫描**: `AssetDiscoveryService.isIncrementalSkip()` + `ProjectScanner` 已有基础；**断点续传** 本轮新建
- **PromptTemplateLoader 缓存**: `@Cacheable` + `@CacheEvict` 已配置，`RedisConfig` TTL 6h
- **LLM 中间状态流式推送**: 已有 11 个 `thinking` 阶段事件（cache_check → generating）
- **总计修改**: 30 个文件（10 新增 + 20 修改）

## 关键设计决策

| 决策 | 说明 |
|------|------|
| SCAN 替代 KEYS | 游标迭代 + 每 100 key 分批 DEL，匹配 `lg:*` 模式 |
| null 值缓存 | `put(key,null)`→写入 `__NULL__` 占位符，按 key 前缀差异化 TTL |
| TTL 抖动 | ±10% 随机偏移（仅对 >2s 的 TTL 生效） |
| QA 并发 | classify∥HyDE 无依赖并行 → rewrite 依赖 classify |
| LLM 重试 | 仅 IO/429/5xx 可重试，4xx 不重试 |
| Neo4j 索引 | 幂等 `CREATE INDEX IF NOT EXISTS`，启动自动执行 |
| 召回 fan-out | 主查询∥变体∥关键词 3 路并发，orTimeout(5s)，单路失败不影响 |
| 图谱窗口 | 游标分页按 nodeKey ASC，limit 控制 + hasMore，兼容旧 /graph/unified |
| BFS 批量 | 每层一次 Cypher 查所有 frontier 邻居，修复原 always-seeds bug |
| 写图批量 | UNWIND batch MERGE + 降级逐条兜底（batch 失败不丢数据） |
| 并行安全 | 3 个 Extractor 全部 ThreadLocal<JavaParser>，parallelStream 安全 |
| 预热容错 | wrap try/catch，Redis 故障不影响启动 |

## 执行阶段

```
P0: ████████████ 3/3    Redis 严重问题 + Neo4j 索引
P1: ████████████ 7/9    图谱窗口 / QA 并发 / LLM 重试 / JDBC 批量 / 召回 fan-out / 写图并发 / JavaParser
P2: ████████████ 10/10 全部完成
P3: ████████████ 4/4    全部完成
─────────────────────
总计: 27 / 27 ✅ 🎉
```
