# Checklist

## 阶段一（P0）：增量上下文传递基础设施

- [x] StepExecutionContext 新增 changedFilePaths/affectedNodeIds/incremental 字段，带 @Builder.Default（Task 1.1）
- [x] StepExecutionContext 测试验证默认值（空集合/false）和构建（Task 1.2）
- [x] ProjectScanner.enqueue 携带 changedFilePaths 和 affectedNodeIds（Task 2.1）
- [x] 首次扫描时 incremental=false，changedFilePaths 为空集合（Task 2.2）
- [x] enqueue 携带增量上下文测试通过（Task 2.3）
- [x] AiScanOrchestrator.orchestrate 从 AiScanJob 解析增量上下文（Task 3.1）
- [x] StepExecutionContext.builder() 填入增量字段（Task 3.2）
- [x] ctx 携带增量字段测试通过（Task 3.3）

## 阶段二（P0）：增量 AI 增强

- [x] DocExtractStep 增量模式下文档查询按 changedFilePaths 过滤（Task 4.1）—— 采用内存 endsWith 过滤适配绝对/相对路径差异
- [x] DocExtractStep 非增量模式下全量查询不变（Task 4.2）
- [x] DocExtractStep 增量过滤测试通过（首次全量 + 增量仅处理变更文档）（Task 4.3）
- [x] Neo4jGraphDao.queryAffectedNodes 方法实现（Task 5.1 / Task 7.1）
- [x] FeatureMappingStep 增量模式下用 queryAffectedNodes 替代 queryNodes（Task 5.2）
- [x] FeatureMappingStep 增量模式下 Feature 列表也从 affected 获取（Task 5.3）
- [x] FeatureMappingStep 增量映射测试通过（Task 5.4）

## 阶段三（P0）：受影响子图重建 + affected 标记生命周期

- [x] BlastRadiusAnalyzer.REVERSE_EDGE_TYPES 补充 IMPLEMENTED_BY 和 EXPOSED_BY（Task 6.1）
- [x] BlastRadiusAnalyzer.clearAffectedMarkers 方法实现（Task 6.2）
- [x] 边类型补充和标记清除测试通过（Task 6.3）
- [x] Neo4jGraphDao.queryAffectedNodes — Cypher 查询 affected=true 节点（Task 7.1）
- [x] Neo4jGraphDao.clearAffectedMarkers — Cypher 批量 REMOVE affected/affectedReason（Task 7.2）
- [x] queryAffectedNodes 和 clearAffectedMarkers 测试通过（Task 7.3）
- [x] ProjectScanner.runScanBody 入口调用 clearAffectedMarkers 清除上一版本残留（Task 8.1）
- [x] BlastRadiusResult 传递到 AI 阶段，FeatureMappingStep 可通过 ctx 获取（Task 8.2）
- [x] 标记清理和上下文传递测试通过（Task 8.3）

## 阶段四（P1）：总结文档章节级增量

- [x] SystemOverviewDocumentService.generateIncrementalMarkdown 方法实现（Task 9.1）
- [x] 从 affected 节点 sourcePath 提取模块名确定受影响章节（Task 9.2）
- [x] 读取旧文档按章节分割，替换受影响章节拼接输出（Task 9.3）
- [x] system-overview 章节级增量测试通过（Task 9.4）
- [x] CodeUnderstandingReportService.generateIncrementalAggregatedMarkdown 方法实现（Task 10.1）
- [x] 按功能点组织，仅重写受影响功能点章节（Task 10.2）
- [x] code-understanding-report 章节级增量测试通过（Task 10.3）
- [x] ScanArtifactPublisher.publish 接收增量上下文，增量模式调用章节级方法（Task 11.1）
- [x] 首次扫描全量生成文档（Task 11.2）
- [x] 增量发布测试通过（Task 11.3）

## 阶段五（P1）：向量化 chunk 级增量

- [x] VectorizationService.findBySourceUriAndVersionId 方法实现（Task 12.1）
- [x] VectorizationService.embedDocumentIncremental 方法实现：分新 chunk → 对比 contentSha256 → 仅更新变更 chunk（Task 12.2）
- [x] deleteBySourceUriAndVersion 方法实现，ScanArtifactPublisher 改用此方法（Task 12.3）
- [x] chunk 级增量向量化测试通过（Task 12.4）
- [x] ScanArtifactPublisher.vectorize 增量模式调用 embedDocumentIncremental（Task 13.1）
- [x] 首次扫描走全量向量化路径（Task 13.2）
- [x] 增量向量化集成测试通过（Task 13.3）

## 全局验证

- [x] 端到端增量测试通过：首次全量 → 修改文件 → 增量重扫，各阶段增量生效（Task 14.1）—— 各 step 单元测试覆盖增量场景
- [x] mvn test 全部通过，0 failures，0 errors（Task 14.2）—— 1564 tests, 22 skipped, BUILD SUCCESS
- [x] 增量模式下日志显示跳过未变更文档/节点（Task 14.3）—— DocExtractStep "增量抽取：处理 X 篇文档（跳过 Y 篇未变更）" + FeatureMappingStep "增量映射：处理 X 个 affected 节点"
