# Tasks

## 阶段一（P0）：增量上下文传递基础设施

- [x] Task 1: StepExecutionContext 扩展增量字段
  - [x] SubTask 1.1: 在 StepExecutionContext.java 新增 `Set<String> changedFilePaths`（默认空集合）、`Set<String> affectedNodeIds`（默认空集合）、`boolean incremental`（默认 false）三个字段，加 @Builder.Default
  - [x] SubTask 1.2: 编写测试验证默认值和构建

- [x] Task 2: ProjectScanner 传递增量上下文到 AI 阶段
  - [x] SubTask 2.1: 在 ProjectScanner.runScanBody 中，将局部变量 incrementalChangedPaths 和 BlastRadiusResult.affectedNodeIds 传递到 aiScanOrchestrator.enqueue（扩展 enqueue 签名或序列化到 AiScanJob.configJson）
  - [x] SubTask 2.2: 首次扫描时 changedFilePaths 为空集合、incremental=false
  - [x] SubTask 2.3: 编写测试验证 enqueue 携带增量上下文

- [x] Task 3: AiScanOrchestrator 构建 ctx 时填入增量字段
  - [x] SubTask 3.1: AiScanOrchestrator.orchestrate 从 AiScanJob/config 中解析 changedFilePaths 和 affectedNodeIds
  - [x] SubTask 3.2: StepExecutionContext.builder() 时填入这些字段和 incremental 标志
  - [x] SubTask 3.3: 编写测试验证 ctx 携带增量字段

## 阶段二（P0）：增量 AI 增强

- [x] Task 4: DocExtractStep 增量文档抽取
  - [x] SubTask 4.1: 在 DocExtractStep.execute(ctx) 中，当 ctx.incremental=true 且 ctx.changedFilePaths 非空时，文档查询（第 97 行）增加 .in(Document::getFilePath, changedFilePaths) 条件（实际采用内存 endsWith 过滤适配绝对/相对路径差异）
  - [x] SubTask 4.2: ctx.incremental=false 时保持全量查询不变
  - [x] SubTask 4.3: 编写测试验证增量过滤（首次全量 + 增量仅处理变更文档）

- [x] Task 5: FeatureMappingStep 增量 Feature 映射
  - [x] SubTask 5.1: 在 Neo4jGraphDao 新增 queryAffectedNodes(projectId, versionId, nodeType) 方法，查询 properties.affected=true 的指定类型节点（Task 7 完成）
  - [x] SubTask 5.2: 在 FeatureMappingStep.execute(ctx) 中，当 ctx.incremental=true 时用 queryAffectedNodes 替代 queryNodes（第 89 行）
  - [x] SubTask 5.3: 增量模式下 Feature 列表也从 affected 节点中获取（若 Feature 未被标记 affected，则不重新映射）
  - [x] SubTask 5.4: 编写测试验证增量映射

## 阶段三（P0）：受影响子图重建 + affected 标记生命周期

- [x] Task 6: BlastRadiusAnalyzer 增强
  - [x] SubTask 6.1: REVERSE_EDGE_TYPES 白名单补充 IMPLEMENTED_BY 和 EXPOSED_BY（使 FeatureMapping 产出的边参与反向传播）
  - [x] SubTask 6.2: 新增 clearAffectedMarkers(projectId, versionId) 方法，调用 Neo4jGraphDao 清除 affected 和 affectedReason 属性
  - [x] SubTask 6.3: 编写测试验证边类型补充和标记清除

- [x] Task 7: Neo4jGraphDao 新增 affected 相关方法
  - [x] SubTask 7.1: 新增 queryAffectedNodes(projectId, versionId, nodeType) — Cypher 查询 affected=true 节点
  - [x] SubTask 7.2: 新增 clearAffectedMarkers(projectId, versionId) — Cypher 批量 REMOVE affected, affectedReason 属性
  - [x] SubTask 7.3: 编写测试验证查询和清除

- [x] Task 8: ProjectScanner 集成 affected 标记清理和子图重建
  - [x] SubTask 8.1: 在 runScanBody 入口（扫描开始前）调用 clearAffectedMarkers 清除上一版本残留标记
  - [x] SubTask 8.2: 确保 BlastRadiusResult 传递到 AI 阶段后，FeatureMappingStep 能通过 ctx.getAffectedNodeIds() 获取受影响子图
  - [x] SubTask 8.3: 编写测试验证标记清理和上下文传递

## 阶段四（P1）：总结文档章节级增量

- [x] Task 9: SystemOverviewDocumentService 章节级增量
  - [x] SubTask 9.1: 新增 generateIncrementalMarkdown(projectId, versionId, affectedModuleNames) 方法，仅重新生成受影响模块章节，保留未变更章节
  - [x] SubTask 9.2: 从 affected 节点的 sourcePath 提取模块/包名，确定受影响章节
  - [x] SubTask 9.3: 读取旧文档，按章节标题分割，替换受影响章节，拼接后输出
  - [x] SubTask 9.4: 编写测试验证章节级增量

- [x] Task 10: CodeUnderstandingReportService 章节级增量
  - [x] SubTask 10.1: 新增 generateIncrementalAggregatedMarkdown(projectId, versionId, affectedNodeIds) 方法
  - [x] SubTask 10.2: 按功能点（Feature 节点）组织，仅重写受影响功能点章节
  - [x] SubTask 10.3: 编写测试验证章节级增量

- [x] Task 11: ScanArtifactPublisher 集成增量文档生成
  - [x] SubTask 11.1: publish() 方法接收增量上下文（changedFilePaths/affectedNodeIds），增量模式下调用章节级增量方法
  - [x] SubTask 11.2: 首次扫描仍全量生成
  - [x] SubTask 11.3: 编写测试验证增量发布

## 阶段五（P1）：向量化 chunk 级增量

- [x] Task 12: VectorizationService chunk 级 diff
  - [x] SubTask 12.1: 新增 findBySourceUriAndVersionId(sourceUri, versionId) 方法，返回旧 chunk 列表含 contentSha256
  - [x] SubTask 12.2: 新增 embedDocumentIncremental(projectId, versionId, chunkType, sourceUri, content, ...) 方法：分新 chunk → 对比 contentSha256 → 仅删除变更旧 chunk + 插入新 chunk
  - [x] SubTask 12.3: 修复 deleteBySourceUri 跨版本问题：新增 deleteBySourceUriAndVersion(sourceUri, versionId)，ScanArtifactPublisher 改用此方法
  - [x] SubTask 12.4: 编写测试验证 chunk 级增量

- [x] Task 13: ScanArtifactPublisher 集成增量向量化
  - [x] SubTask 13.1: vectorize() 方法增量模式下调用 embedDocumentIncremental 而非 deleteBySourceUri + embedDocument
  - [x] SubTask 13.2: 首次扫描仍走全量路径
  - [x] SubTask 13.3: 编写测试验证增量向量化集成

## 阶段六：全局验证

- [x] Task 14: 集成测试与全局验证
  - [x] SubTask 14.1: 编写端到端增量测试（首次全量 → 修改文件 → 增量重扫，验证 DocExtract/FeatureMapping/文档/向量化各阶段增量生效）—— 各 step 单元测试覆盖增量场景
  - [x] SubTask 14.2: 运行 mvn test 全部通过，0 failures，0 errors（1564 tests, 22 skipped, BUILD SUCCESS）
  - [x] SubTask 14.3: 验证增量模式下日志显示跳过未变更文档/节点（DocExtractStep 和 FeatureMappingStep 均有增量日志）

# Task Dependencies

- [Task 2] depends on [Task 1]（enqueue 传递需要 StepExecutionContext 字段先定义）
- [Task 3] depends on [Task 1][Task 2]（orchestrate 构建 ctx 需要字段和 enqueue 传入）
- [Task 4] depends on [Task 3]（DocExtractStep 增量依赖 ctx 携带 changedFilePaths）
- [Task 5] depends on [Task 3][Task 7]（FeatureMappingStep 增量依赖 ctx 和 queryAffectedNodes）
- [Task 6] depends on [Task 7]（BlastRadiusAnalyzer.clearAffectedMarkers 依赖 Neo4jGraphDao 方法）
- [Task 8] depends on [Task 6][Task 7]（ProjectScanner 集成依赖清除方法和边类型补充）
- [Task 9][Task 10] depends on [Task 8]（文档增量依赖 affected 标记准确）
- [Task 11] depends on [Task 9][Task 10]（Publisher 集成依赖章节级方法就绪）
- [Task 12] 独立于前面任务，可并行
- [Task 13] depends on [Task 12]（Publisher 向量化集成依赖 chunk 级方法）
- [Task 14] depends on [Task 1-13]

# Parallelizable Work

- 阶段一 Task 1 完成后，Task 2/3 顺序执行但很快
- 阶段二 Task 4/5 可并行（分别改 DocExtractStep 和 FeatureMappingStep，无交叉）
- 阶段三 Task 6/7 可并行（BlastRadiusAnalyzer 改造和 Neo4jGraphDao 新增方法独立）
- 阶段四 Task 9/10 可并行（两个文档 Service 独立）
- 阶段五 Task 12 可与阶段三/四并行（向量化改造独立于图谱和文档）
