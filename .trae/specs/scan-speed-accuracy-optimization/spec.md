# 扫描任务速度与准确性优化 Spec

## Why
#21 扫描（scan-20260710-163610）总耗时 9,318s，相比 #20 的 1,475s 回归 6.3x。根因为级联效应：大文档分块过细（124 chunks）→ Feature 膨胀（570→1346）→ 映射边爆炸（2724→17143）。虽然 OOM 已修复（0 次 OOM），但速度严重回归且图谱质量未同步提升（17,143 条边大部分为低质量噪声）。

本优化目标：**总耗时降至 ~2,500s 以内，映射边噪声减少 75%+**，在保证 0 OOM 的前提下同时提升速度和准确性。

## What Changes

### 速度优化
- **修复 ADAPTER_SCAN 增量扫描 0 变更退化为全量 BUG**：`ProjectScanner.java:1971` 的 `!changedPaths.isEmpty()` 条件导致空集合时全量执行
- **embedding 批量化**：3 处单条 `embed(String)` 调用改为批量 `embed(List<String>)`，减少 Ollama HTTP 往返
- **DocExtractStep + CodeExtractStep 并行执行**：两步骤独立线程池、独立数据源，编排器改为并行
- **大文档分段受限并行**：`extractFromChunks` 串行改 2 路受限并行（配合内存水位背压）
- **向量化 chunk size 提升**：1200/800 → 2000/1500，减少 embedding 调用次数
- **embedding cache 跨调用复用**：`mapFeaturesToCode` 和 `mergeCrossLanguageFeatures` 共用 versionId 级缓存
- **ADAPTER_SCAN 文件 I/O 缓存**：同一文件被读取 3-5 次，改为预读一次缓存到 SourceAsset

### 准确性优化
- **Feature→Code 映射 top-N 筛选**：每个 Feature 只保留 top-3 + score > 阈值的边，过滤低分冗余
- **Feature 语义去重**：`deduplicateFeatures` 增加向量语义去重（cosine > 0.90 合并）
- **Page 匹配阈值对齐**：0.55 → 0.60，减少低质量 EXPOSED_BY 边

## Impact
- Affected specs: scan-qa-optimization（Task 8 ADAPTER_SCAN 优化相关）、graph-report-accuracy-optimization（图谱质量评估相关）
- Affected code:
  - `ProjectScanner.java` — 增量扫描 BUG 修复
  - `AiScanOrchestrator.java` — 步骤并行编排
  - `DocExtractStep.java` — 大文档分段并行、Feature 语义去重
  - `BusinessGraphBuilder.java` — embedding 批量化、top-N 筛选、cache 复用、Page 阈值
  - `AiScanStepSupport.java` — 向量化 chunk size
  - `AdapterExecutionService.java` / `SourceAsset.java` — 文件 I/O 缓存

## ADDED Requirements

### Requirement: 增量扫描零变更跳过
The system SHALL skip adapter extraction when incremental scan detects 0 changed files, instead of falling back to full scan.

#### Scenario: 增量扫描 0 变更
- **WHEN** 增量扫描模式启用且 `changedPaths` 为空集合（非 null）
- **THEN** `assetsToExtract` 设为空列表，跳过 adapter 执行，仅执行资产发现和快照持久化

### Requirement: embedding 批量调用
The system SHALL use batch embedding API (`embed(List<String>)`) instead of single embedding (`embed(String)`) to reduce HTTP round-trips to Ollama.

#### Scenario: Feature→Code 映射预批量 embed
- **WHEN** `mapFeaturesToCode` 开始执行
- **THEN** 预收集所有唯一名称（Feature + Page + API），一次性批量 embed 填入 cache，后续 `lazyMaxTokenVector` 全部命中 cache

### Requirement: 步骤级并行编排
The system SHALL execute DocExtractStep (order=1) and CodeExtractStep (order=2) in parallel when both have independent thread pools and data sources.

#### Scenario: AI 编排并行启动
- **WHEN** AI 编排执行到 order<=2 的步骤
- **THEN** 两个步骤通过 CompletableFuture 并行执行，等待全部完成后继续 order>=3 串行执行

### Requirement: 大文档分段受限并行
The system SHALL extract chunks from large documents with bounded parallelism (max 2 concurrent chunks per document) instead of serial extraction.

#### Scenario: 大文档分段并行抽取
- **WHEN** 文档内容 > 16000 字符触发分段
- **THEN** 各 chunk 以最多 2 路并发提交到 docExtractExecutor，配合 `isMemoryHealthy()` 背压，OOM 时提前终止

### Requirement: Feature→Code 映射 top-N 筛选
The system SHALL retain only top-3 highest-scoring edges per Feature (plus edges above threshold) to filter low-quality redundant mappings.

#### Scenario: Feature 匹配多个 API
- **WHEN** 一个 Feature 匹配到 5 个 API（score 0.66/0.68/0.72/0.75/0.80）
- **THEN** 仅保留 top-3（0.80/0.75/0.72）的 IMPLEMENTED_BY 边，过滤 0.68/0.66 低分冗余边

### Requirement: Feature 语义去重
The system SHALL deduplicate Features by vector semantic similarity (cosine > 0.90) in addition to normalization and substring deduplication.

#### Scenario: 语序不同但语义相同
- **WHEN** chunk 抽取产生 "入金查询" 和 "查询入金"
- **THEN** 向量余弦相似度 > 0.90，合并为一个 Feature，减少映射膨胀源头

## MODIFIED Requirements

### Requirement: 向量化分片大小
向量化分片大小从 1200/800 提升到 2000/1500，减少大文档 embedding 调用次数。
- `VECTOR_CHUNK_SIZE`: 1200 → 2000
- `VECTOR_OVERLAP`: 120 → 200
- `LARGE_DOC_CHUNK_SIZE`: 800 → 1500

### Requirement: Page 匹配阈值
Feature→Page 边质量门控从 0.55 提升到 0.60，与 API 阈值（0.65）差距缩小，减少低质量 EXPOSED_BY 边。

### Requirement: embedding cache 生命周期
embedding cache 从方法局部变量改为 versionId 级实例缓存，`mapFeaturesToCode` 和 `mergeCrossLanguageFeatures` 共用，扫描完成后清理。

### Requirement: ADAPTER_SCAN 文件 I/O
`SourceAsset` 增加 `cachedContent` 字段，`AdapterExecutionService` 预读文件内容一次，各 adapter 从 asset 取内容而非重复读文件。

## REMOVED Requirements
（无移除项）
