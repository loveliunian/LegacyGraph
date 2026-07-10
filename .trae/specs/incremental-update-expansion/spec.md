# 增量更新全链路覆盖 Spec

## Why

当前增量扫描仅在 ADAPTER_SCAN 阶段生效（FileChangeDetector SHA-256 检测变更文件 + BlastRadiusAnalyzer 标记 affected 节点），但变更上下文是 ProjectScanner.runScanBody 的局部变量，**从未传递给 AI 编排阶段**。根因是 `StepExecutionContext` 只有 4 个字段（projectId/versionId/config/cancellationChecker），不携带变更文件路径和受影响节点 ID。

后果：AI_ORCHESTRATION 阶段（DocExtractStep / FeatureMappingStep）全量查询所有文档和节点重跑 LLM，是扫描耗时倍增的主因（411s→1231s→2460s）。总结文档和向量化也每次全量重写。

目标：打通增量上下文传递链路，让 AI 增强、总结文档、向量化都能按变更范围增量执行。

## What Changes

### 增量上下文传递（P0 基础）
- StepExecutionContext 新增 changedFilePaths / affectedNodeIds / incremental 字段
- ProjectScanner.enqueue 传入增量上下文（变更文件路径 + BlastRadiusResult）
- AiScanOrchestrator.orchestrate 构建 ctx 时携带增量字段

### 增量 AI 增强（P0）
- DocExtractStep：增量模式下仅查询 filePath ∈ changedFilePaths 的文档做 LLM 抽取
- FeatureMappingStep：增量模式下仅查询 affected=true 的 ApiEndpoint/Feature 节点做映射
- 未变更节点的 AI 增强属性保留不动

### 受影响子图重建 + affected 标记生命周期（P0）
- 启用 BlastRadiusAnalyzer.getAffectedSubgraph()，AI 阶段仅处理受影响子图
- 全量扫描开始前或版本切换时清除残留 affected/affectedReason 标记
- 反向传播边类型白名单补充 IMPLEMENTED_BY/EXPOSED_BY（FeatureMapping 产出的边）

### 总结文档章节级增量（P1）
- ScanArtifactPublisher 增量模式下仅重写受影响章节
- system-overview.md：按模块/包组织，仅重写变更模块章节
- code-understanding-report.md：按功能点组织，仅重写受影响功能点章节

### 向量化 chunk 级增量（P1）
- VectorizationService 新增 embedDocumentIncremental()，基于 contentSha256 对比 chunk
- 仅删除并重写变更 chunk，保留未变更 chunk
- 修复 deleteBySourceUri 跨版本删除风险（加 versionId 维度）

## Impact

- **Affected specs**: `scan-qa-optimization`（已有 FileChangeDetector 和 BlastRadiusAnalyzer 基础）
- **Affected code**:
  - `task/step/StepExecutionContext.java` — 新增 3 个增量字段（核心阻塞点）
  - `task/ProjectScanner.java` — enqueue 传入增量上下文，runScanBody 中 blastResult 上传
  - `task/step/AiScanStepExecutor.java`（接口） — execute(ctx) 无需改签名，ctx 携带新字段
  - `task/AiScanOrchestrator.java` — ctx.build() 携带增量字段
  - `task/step/DocExtractStep.java:97` — docs 查询按 changedFilePaths 过滤
  - `task/step/FeatureMappingStep.java:89` — queryNodes 按 affected=true 过滤
  - `service/scan/BlastRadiusAnalyzer.java` — getAffectedSubgraph 启用 + REVERSE_EDGE_TYPES 补充 + clearAffectedMarkers
  - `dao/Neo4jGraphDao.java` — 新增 queryAffectedNodes / clearAffectedMarkers 方法
  - `service/scan/ScanArtifactPublisher.java` — 增量章节生成
  - `service/SystemOverviewDocumentService.java` — 按模块增量生成
  - `service/CodeUnderstandingReportService.java` — 按功能点增量生成
  - `service/qa/VectorizationService.java` — embedDocumentIncremental + deleteBySourceUriAndVersion
- **BREAKING**: 无。增量能力通过 `legacygraph.scan.incremental.enabled`（默认 true）控制，首次扫描全量执行

## ADDED Requirements

### Requirement: 增量上下文传递
系统 SHALL 通过 StepExecutionContext 携带变更文件路径集合和受影响节点 ID 集合，使所有 AI 编排阶段的 step 实现能从上下文读取增量范围。

#### Scenario: 增量上下文从 ProjectScanner 传递到 AI 阶段
- **GIVEN** 增量扫描检测到 5 个变更文件，BlastRadiusAnalyzer 标记 20 个受影响节点
- **WHEN** ProjectScanner 调用 aiScanOrchestrator.enqueue
- **THEN** AiScanJob 携带 changedFilePaths（5 个路径）和 affectedNodeIds（20 个节点 ID）
- **AND** AiScanOrchestrator.orchestrate 构建 StepExecutionContext 时填入这些字段
- **AND** 各 step 的 execute(ctx) 可通过 ctx.getChangedFilePaths() / ctx.getAffectedNodeIds() 读取

#### Scenario: 首次扫描无增量上下文
- **GIVEN** 项目从未扫描过，无 FileSnapshot 历史
- **WHEN** 执行首次扫描
- **THEN** StepExecutionContext.incremental = false
- **AND** changedFilePaths 和 affectedNodeIds 为空集合（非 null）
- **AND** 各 step 执行全量逻辑

### Requirement: 增量 AI 增强
系统 SHALL 在增量扫描模式下，仅对变更文件关联的文档和受影响节点执行 AI 增强（LLM 抽取和 Feature 映射），跳过未变更部分。

#### Scenario: DocExtractStep 增量抽取
- **GIVEN** 项目有 50 篇文档，增量模式下 3 个文档变更
- **WHEN** DocExtractStep.execute(ctx) 执行
- **THEN** 仅查询 filePath ∈ ctx.changedFilePaths 的 3 篇文档
- **AND** 仅对这 3 篇文档执行 LLM 抽取
- **AND** 其余 47 篇文档的 BusinessFact 保留不动

#### Scenario: FeatureMappingStep 增量映射
- **GIVEN** 项目有 100 个 ApiEndpoint，增量模式下 15 个标记 affected=true
- **WHEN** FeatureMappingStep.execute(ctx) 执行
- **THEN** 仅查询 affected=true 的 15 个 ApiEndpoint 和 Feature 节点
- **AND** 仅对这 15 个节点重新做 Feature 映射
- **AND** 未变更节点的 IMPLEMENTED_BY/EXPOSED_BY 边保留不动

#### Scenario: 首次扫描全量 AI 增强
- **GIVEN** 项目首次扫描，ctx.incremental = false
- **WHEN** DocExtractStep 和 FeatureMappingStep 执行
- **THEN** 查询所有文档和节点，全量执行 AI 增强
- **AND** 不因增量逻辑跳过任何文档或节点

### Requirement: 受影响子图重建与 affected 标记生命周期
系统 SHALL 在增量扫描后启用受影响子图重建，并在适当时机清除 affected 标记，避免标记残留污染后续扫描。

#### Scenario: getAffectedSubgraph 被调用
- **GIVEN** 增量扫描完成 ADAPTER_SCAN，BlastRadiusAnalyzer 已标记 affected 节点
- **WHEN** AI 编排阶段开始
- **THEN** getAffectedSubgraph 被调用获取受影响子图
- **AND** FeatureMappingStep 仅处理受影响子图中的节点

#### Scenario: affected 标记在版本切换时清除
- **GIVEN** 上一次增量扫描标记了 50 个 affected=true 节点
- **WHEN** 新扫描版本开始（runScanBody 入口）
- **THEN** clearAffectedMarkers(projectId, oldVersionId) 被调用
- **AND** 所有节点的 affected 和 affectedReason 属性被清除
- **AND** 全量扫描时也清除残留标记

#### Scenario: 反向传播覆盖 Feature 边
- **GIVEN** FeatureMapping 产出的 IMPLEMENTED_BY 边连接 ApiEndpoint 和 Feature
- **WHEN** ApiEndpoint 所在文件变更
- **THEN** BlastRadiusAnalyzer 反向遍历包含 IMPLEMENTED_BY 和 EXPOSED_BY 边
- **AND** Feature 节点被标记为 affected

### Requirement: 总结文档章节级增量
系统 SHALL 在增量扫描模式下，仅重写受变更影响的文档章节，保留未受影响章节。

#### Scenario: system-overview.md 按模块增量
- **GIVEN** system-overview.md 按 10 个模块组织，仅 module-A 的代码变更
- **WHEN** 增量扫描完成，ScanArtifactPublisher.publish 执行
- **THEN** SystemOverviewDocumentService 仅重新生成 module-A 章节
- **AND** 其余 9 个模块章节内容保留旧版本
- **AND** 文档拼接后整体写入

#### Scenario: 首次扫描全量生成文档
- **GIVEN** 项目首次扫描
- **WHEN** ScanArtifactPublisher.publish 执行
- **THEN** 全量生成所有章节

### Requirement: 向量化 chunk 级增量
系统 SHALL 在文档更新时，基于 contentSha256 对比新旧 chunk，仅删除并重写变更 chunk 的向量。

#### Scenario: chunk 级增量向量化
- **GIVEN** system-overview.md 已向量化为 20 个 chunk，增量更新后 4 个 chunk 内容变化
- **WHEN** embedDocumentIncremental 执行
- **THEN** 查询旧 chunk 的 contentSha256 列表
- **AND** 仅删除 4 个变更 chunk 的向量记录
- **AND** 仅重新生成 4 个变更 chunk 的向量
- **AND** 其余 16 个 chunk 保留不动

#### Scenario: deleteBySourceUri 加版本维度
- **GIVEN** sourceUri="docs/legacygraph/system-overview.md" 存在多个版本的向量
- **WHEN** 删除指定版本的向量
- **THEN** 仅删除匹配 versionId 的记录
- **AND** 其他版本的同 sourceUri 记录保留

## MODIFIED Requirements

### Requirement: 增量扫描流程编排
ProjectScanner 的 runScanBody SHALL 将变更文件路径集合和 BlastRadiusResult 传递给 AI 编排阶段，并在版本开始时清除上一版本的 affected 标记。

### Requirement: Neo4jGraphDao 节点查询
Neo4jGraphDao SHALL 新增 queryAffectedNodes(projectId, versionId, nodeType) 方法，返回 affected=true 的指定类型节点；新增 clearAffectedMarkers(projectId, versionId) 方法清除 affected 和 affectedReason 属性。

### Requirement: VectorizationService 向量化
VectorizationService SHALL 新增 embedDocumentIncremental 方法支持 chunk 级 diff，并将 deleteBySourceUri 改为按 versionId 过滤删除。
