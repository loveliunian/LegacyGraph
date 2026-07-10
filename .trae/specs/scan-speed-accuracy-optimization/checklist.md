# Checklist

## 阶段一（P0）验证检查点

### Task 1: 增量扫描 BUG 修复
- [x] `ProjectScanner.java:1971` 条件已修改，增量模式下 `changedPaths` 空集合时 `assetsToExtract = List.of()`
- [x] 非增量模式（`!plan.isIncremental()` 或 `changedPaths == null`）行为不变，仍全量执行
- [x] 单元测试验证 0 变更时跳过 adapter、非增量模式全量执行（ProjectScannerIncrementalScanTest 3 个测试 passed）

### Task 2: embedding 批量化
- [x] `BusinessGraphBuilder.mapFeaturesToCode` 预收集唯一名称并批量 `embed(List)` 调用
- [x] `mergeCrossLanguageFeatures` 逐条 `embedOne` 改为批量（保留逐条作为降级 fallback）
- [x] 批量调用失败时 fallback 到逐条 lazy embed（降级能力保留）
- [x] 单元测试验证批量 embed 被调用、cache 填充、降级正常（testMapFeaturesToCode_BatchEmbed_FillsCache_NoSingleEmbed passed）

### Task 3: top-N 筛选
- [x] `mapFeaturesToCode` 每个 Feature 的候选边按 score 降序只保留 top-3
- [x] top-3 仍需满足 score > 阈值（不能把低于阈值的边也保留）
- [x] 单元测试验证 5 个匹配只保留 top-3（testMapFeaturesToCode_TopNFilter_KeepsOnlyTop3 passed）

### Task 4: Feature 语义去重
- [x] `deduplicateFeatures` 在归一化+子串去重后增加向量语义去重（cosine > 0.90）
- [x] embeddingModel 为 null 时降级到现有逻辑
- [x] 单元测试验证语序不同但语义相同的 Feature 被合并（semanticDeduplicateFeatures_语义相同Feature被合并 passed）

## 阶段二（P1）验证检查点

### Task 5: 步骤并行编排
- [x] `AiScanOrchestrator.orchestrate` 中 order<=2 的步骤通过 Thread 并行
- [x] 并行组内任一步骤异常不中断另一步骤（try-catch 隔离）
- [x] order>=3 的步骤在并行组完成后串行执行
- [x] 单元测试验证并行执行、异常隔离（testParallelSteps_OverlapInExecution + testParallelStep_ExceptionDoesNotBlockOther passed）

### Task 6: 大文档分段并行
- [x] `extractFromChunks` 串行改最多 2 路并发
- [x] 内存不足时提前终止，保留已抽到的段
- [x] 单元测试验证并发数、提前终止逻辑（extractFromChunks_多chunk两路并行后合并结果 + extractFromChunks_OOM时chunk返回null被过滤 passed）

### Task 7: 向量化 chunk size
- [x] `VECTOR_CHUNK_SIZE` = 2000，`VECTOR_OVERLAP` = 200，`LARGE_DOC_CHUNK_SIZE` = 1500
- [x] 单元测试验证新值生效（AiScanStepSupportTest 3 个测试 passed）

### Task 8: embedding cache 跨调用复用
- [x] `BusinessGraphBuilder` 有 versionId 级实例缓存（embeddingCacheByVersion ConcurrentHashMap）
- [x] `mapFeaturesToCode` 和 `mergeCrossLanguageFeatures` 共用同一 cache
- [x] `FeatureCodeMappingStep` finally 块清理对应 versionId cache
- [x] 单元测试验证两次调用共用 cache、清理后为空（testEmbeddingCache_ReuseAcrossCallsAndClear passed）

### Task 9: 文件 I/O 缓存
- [x] `SourceAsset` 有 `cachedContent` 字段（transient + getter/setter）
- [x] `AdapterExecutionService` 预读文件内容填入 `cachedContent`
- [x] `JavaCodeAdapter.supports()` / `JavaStructureExtractor` / `ServiceCallExtractor` 新增重载方法优先用缓存
- [x] 缓存为 null 时 fallback 读文件（原单参方法保留）
- [x] 单元测试验证文件只读一次（AssetContentCacheTest 6 个测试 passed）

## 阶段三（P2）验证检查点

### Task 10: Page 阈值
- [x] `PAGE_SCORE_THRESHOLD` = 0.60
- [x] 单元测试验证 0.55-0.60 区间边被过滤（testMapFeaturesToCode_PageThreshold_FiltersScoreBetweenOldAndNew passed）

## 全局验证

### Task 11: 全局测试
- [x] `mvn clean test` 1589 tests, 1 failure（预存 flaky：ProjectOverviewServiceTest H2 跨测试污染，单独运行 0 failures）, 22 skipped
- [x] 新增测试全部通过（共新增约 21 个测试方法）
