# Tasks

## 阶段一（P0）：解决 #21 回归根因（速度+准确性，独立可并行）

- [x] Task 1: 修复 ADAPTER_SCAN 增量扫描 0 变更退化为全量 BUG
  - [x] SubTask 1.1: 修改 `ProjectScanner.java:1971`，增量模式下 `changedPaths` 非空（含 0 个变更）时走过滤逻辑，空集合时设 `assetsToExtract = List.of()` 并 log "0 files changed, skipping adapter extraction"
  - [x] SubTask 1.2: 编写单元测试验证 0 变更时 assetsToExtract 为空、非增量模式不受影响（ProjectScannerIncrementalScanTest 3 个测试 passed）

- [x] Task 2: embedding 批量化（Feature→Code 映射）
  - [x] SubTask 2.1: 在 `BusinessGraphBuilder.mapFeaturesToCode` 中，预收集所有唯一名称（Feature + Page + API normalized），一次性 `embeddingModel.embed(List)` 批量获取填入共享 cache
  - [x] SubTask 2.2: `mergeCrossLanguageFeatures` 逐条 `embedOne` 改为批量 `embeddingModel.embed(List)`，保留逐条作为降级 fallback
  - [x] SubTask 2.3: 批量调用失败时 fallback 到现有逐条 lazy embed 逻辑（保持降级能力）
  - [x] SubTask 2.4: 编写单元测试验证批量 embed 被调用且 cache 填充正确（BusinessGraphBuilderTest.testMapFeaturesToCode_BatchEmbed_FillsCache_NoSingleEmbed passed）

- [x] Task 3: Feature→Code 映射 top-N 筛选
  - [x] SubTask 3.1: 在 `BusinessGraphBuilder.mapFeaturesToCode` 中，将直接 `candidateEdges.add` 改为先收集每个 feature 的候选边到 `featEdges`
  - [x] SubTask 3.2: 每个 feature 的候选边按 score 降序，只保留 top-3 + score > 阈值的边加入 `candidateEdges`
  - [x] SubTask 3.3: 编写单元测试验证一个 Feature 匹配 5 个 API 时只保留 top-3（testMapFeaturesToCode_TopNFilter_KeepsOnlyTop3 passed）

- [x] Task 4: Feature 语义去重（向量去重）
  - [x] SubTask 4.1: 在 `DocExtractStep` 中新增 `semanticDeduplicateFeatures` 实例方法，批量 embed 所有 kept Feature，cosine > 0.90 的合并
  - [x] SubTask 4.2: embeddingModel 不可用时 fallback 到现有逻辑（保持降级）
  - [x] SubTask 4.3: 编写单元测试验证 "入金查询" 和 "查询入金" 被合并、embeddingModel 为 null 时降级（3 个测试 passed）

## 阶段二（P1）：速度优化

- [x] Task 5: DocExtractStep + CodeExtractStep 并行编排
  - [x] SubTask 5.1: 修改 `AiScanOrchestrator.orchestrate`，对 order<=2 的步骤用 Thread 并行执行，等待全部完成后继续 order>=3 串行
  - [x] SubTask 5.2: 并行执行前仍需检查 `isCancelled`，并行组内任一步骤异常不中断另一步骤（try-catch 隔离）
  - [x] SubTask 5.3: 编写单元测试验证两个步骤并行执行、异常隔离（AiScanOrchestratorTest 3 个新测试 passed）

- [x] Task 6: 大文档分段受限并行
  - [x] SubTask 6.1: 修改 `DocExtractStep.extractFromChunks`，串行 for 循环改为最多 2 路并发的 CompletableFuture，配合 Semaphore(2) 背压
  - [x] SubTask 6.2: 每段提交前检查 `isMemoryHealthy()`，OOM 时提前终止保留已抽到的段
  - [x] SubTask 6.3: 编写单元测试验证 2 路并发、内存不足时提前终止（2 个测试 passed）

- [x] Task 7: 向量化 chunk size 提升
  - [x] SubTask 7.1: 修改 `AiScanStepSupport.java`，`VECTOR_CHUNK_SIZE` 1200→2000，`VECTOR_OVERLAP` 120→200，`LARGE_DOC_CHUNK_SIZE` 800→1500
  - [x] SubTask 7.2: 编写单元测试验证新 chunk size 生效（AiScanStepSupportTest 3 个测试 passed）

- [x] Task 8: embedding cache 跨调用复用
  - [x] SubTask 8.1: 在 `BusinessGraphBuilder` 增加 `versionId → Map<String, float[]>` 实例级缓存，`mapFeaturesToCode` 和 `mergeCrossLanguageFeatures` 共用
  - [x] SubTask 8.2: `FeatureCodeMappingStep` 执行结束后清理对应 versionId 的 cache（finally 块调用 clearEmbeddingCache）
  - [x] SubTask 8.3: 编写单元测试验证两次调用共用 cache、清理后 cache 为空（testEmbeddingCache_ReuseAcrossCallsAndClear passed）

- [x] Task 9: ADAPTER_SCAN 文件 I/O 缓存
  - [x] SubTask 9.1: `SourceAsset` 增加 `transient String cachedContent` 字段及 getter/setter
  - [x] SubTask 9.2: `AdapterExecutionService.executeDiscoveredAssets` 在提交任务前对每个 asset 预读 `Files.readString()` 一次填入 `cachedContent`
  - [x] SubTask 9.3: `JavaCodeAdapter.supports()`、`JavaStructureExtractor.extractFromFile()`、`ServiceCallExtractor.extractFromFile()` 新增重载方法优先用 `cachedContent`，原方法保留 fallback
  - [x] SubTask 9.4: 编写单元测试验证文件只读一次、缓存为 null 时降级正常（AssetContentCacheTest 6 个测试 passed）

## 阶段三（P2）：准确性微调（低风险）

- [x] Task 10: Page 匹配阈值对齐
  - [x] SubTask 10.1: 修改 `BusinessGraphBuilder.java`，`PAGE_SCORE_THRESHOLD` 0.55→0.60
  - [x] SubTask 10.2: 编写单元测试验证 0.55-0.60 区间的边被过滤（testMapFeaturesToCode_PageThreshold_FiltersScoreBetweenOldAndNew passed）

## 阶段四：全局验证

- [x] Task 11: 运行 mvn clean test 全局测试验证
  - [x] SubTask 11.1: 1589 tests, 1 failure（预存 flaky：ProjectOverviewServiceTest H2 跨测试污染，单独运行通过），22 skipped
  - [x] SubTask 11.2: 新增测试全部通过（共新增约 21 个测试方法）

# Task Dependencies
- [Task 2] 和 [Task 3] 都改 `BusinessGraphBuilder.mapFeaturesToCode`，建议串行（Task 2 先做批量 embed，Task 3 再做 top-N 筛选，top-N 依赖 cache 已填充）
- [Task 4] 改 `DocExtractStep.deduplicateFeatures`，与 Task 2/3 无依赖，可并行
- [Task 1] 改 `ProjectScanner`，与 Task 2-4 无依赖，可并行
- [Task 5] 改 `AiScanOrchestrator`，与 Task 1-4 无依赖，可并行
- [Task 6] 改 `DocExtractStep.extractFromChunks`，与 Task 4 同文件但不同方法，建议 Task 4 先做
- [Task 7] 改 `AiScanStepSupport`，与 Task 2-6 无依赖，可并行
- [Task 8] 改 `BusinessGraphBuilder`，建议在 Task 2 之后（批量 embed 逻辑先就位）
- [Task 9] 改 `AdapterExecutionService`/`SourceAsset`/adapter，与 Task 1-8 无依赖，可并行
- [Task 10] 改 `BusinessGraphBuilder`，建议在 Task 3 之后
- [Task 11] 依赖所有 Task 完成

# Parallelizable Work
- 阶段一：Task 1 / Task 2+3（串行）/ Task 4 可三路并行
- 阶段二：Task 5 / Task 6（依赖 Task 4）/ Task 7 / Task 8（依赖 Task 2）/ Task 9 可多路并行
- 阶段三：Task 10 依赖 Task 3
