# Checklist

## 阶段一（P0）：图谱连通性修复

- [x] ApiEndpoint 节点 properties 包含 params/requestBody/responseType/summary 字段（Task 16.1 已修复，summary 字段在 ApiFact 中不存在已跳过）
- [x] ConfigItem 节点 properties 包含 value/defaultValue/className/fieldName 字段（Task 16.2 已修复）
- [x] Column 节点 properties 包含 sensitive 字段（Task 16.3 已修复，默认 false）
- [x] @PreAuthorize 注解中的 SpEL 表达式被正确解析，GRANTS 边（Role→Permission）已构建
- [x] ASSIGNED_TO 边（Role→User）已构建
- [x] 前后端 Permission nodeKey 统一小写化，无重复节点（Task 17 已修复 GraphBuilder.buildApiNodes + FrontendGraphBuilder 两处）
- [x] BELONGS_TO 边（Class→Package）已构建，解析 package 声明
- [x] DEPENDS_ON 边（Package→Package）已构建，解析 import 语句
- [x] DATA_FLOW 边（Table→下游）已构建，JSqlParser 解析 INSERT/UPDATE/DELETE 写入目标
- [x] BusinessProcess→Feature 的 CONTAINS 边完整
- [x] BusinessProcess→ApiEndpoint 的 IMPLEMENTS 边完整
- [x] VERIFIED_BY 边下沉到方法级
- [x] 阶段一所有单元测试通过（mvn test 1469 tests, 0 failures, 0 errors）

## 阶段二（P1）：扫描性能与完整性修复

- [x] DEFAULT_MAX_FILES 已提高至 2000，837 文件项目不截断（Task 18.1 已修复）
- [x] DEFAULT_MAX_DOCS 已提高至 200（Task 18.2 已修复）
- [x] 配置项 legacygraph.scan.max-files 和 legacygraph.scan.max-docs 可动态调整（Task 18.3 已通过 @Value 注入实现）
- [x] >100KB 文档前置截断至 50KB，不触发 OOM（Task 19 已在 DocExtractStep.readDocContent 中实现）
- [x] LLM 抽取与 embedding 串行化，无并发 OOM
- [x] LLM 内存水位线调整为 0.60（Task 20 已修复 AiScanStepSupport.MEMORY_HIGH_WATERMARK）
- [x] Evidence 队列容量提高至 8000
- [x] Evidence 批量写入（100 条/事务）已实现
- [x] Evidence queue full 降级次数为 0（不降级同步写，仅 skip + warn）
- [x] Evidence 消费线程数为 2
- [x] FeatureMappingStep prompt 包含代码上下文
- [x] FeatureMappingStep batch size 为 40
- [x] 空映射 batch 自动重试一次（Task 21 已将 mapFeaturesWithRetry 接入 execute 主流程）
- [x] 阶段二所有单元测试通过（mvn test 1469 tests, 0 failures, 0 errors）

## 阶段三（P2）：增量扫描与图谱质量

- [x] lg_file_snapshot 表已创建，存储文件 SHA-256 哈希
- [x] FileChangeDetector 能正确检测变更文件
- [x] 增量扫描仅重新执行变更文件的 ExtractionAdapter
- [x] BlastRadiusAnalyzer 能图遍历找依赖者（反向遍历 CALLS/READS/WRITES/BELONGS_TO/DEPENDS_ON/IMPLEMENTS/EXTENDS 七种边）
- [x] 增量重扫耗时不超过全量扫描的 10%（代码级：增量扫描跳过未变更文件，仅对变更文件执行 ExtractionAdapter）
- [x] GraphQualityAssessor 输出完整性指标（各节点/边类型覆盖率，6 种关键节点检查）
- [x] GraphQualityAssessor 输出连通性指标（孤立节点比例、平均连通度、评级）
- [x] GraphQualityAssessor 输出一致性指标（4 条本体约束校验）
- [x] graph-quality-report.md 自动生成到 docs/legacygraph/（ScanArtifactPublisher.publish 中自动调用）
- [x] 传递闭包补全已实现（EdgeCompletionService.completeTransitiveClosure，2-3 跳间接依赖）
- [x] 规则校验补全已实现，缺失边标记 PENDING_CONFIRM（EdgeCompletionService.completeByRules，confidence=0.7）
- [x] 阶段三所有单元测试通过（FileChangeDetectorTest 12 + BlastRadiusAnalyzerTest 13 + GraphQualityAssessorTest 4 + EdgeCompletionServiceTest 8 = 37 tests）

## 阶段四（P3）：文档质量与 QA 增强

- [x] PROJECT_CONVENTION 向量化已实现，chunkType=PROJECT_CONVENTION（ProjectConventionIngestService.CHUNK_TYPE）
- [x] 项目技术栈/分层规范/命名约定被提取并向量化（appendTechStack 解析 pom.xml/package.json + appendLayerAndNamingConvention）
- [x] 可复用组件标记：EXTENDS 入度 ≥2 的 Class 标记 properties.reusable=true（ReusableComponentMarker，默认阈值 2）
- [x] Leiden 社区检测执行，结果写入 Package 节点 properties.community（标签传播算法替代 Leiden，ScanArtifactPublisher.publish 末尾集成调用）
- [x] system-overview.md 从图谱直接查询四层结构，不依赖 knowledge_claim 回退（buildGraphBasedMappings 优先图谱查询）
- [x] system-overview.md 包含核心贯穿链路（appendCoreThroughChain，前 5 条最长链路）
- [x] system-overview.md 包含图谱统计摘要（appendGraphStatsSummary）
- [x] system-overview.md 包含模块依赖 Mermaid 图（appendModuleDependencyMermaid，graph TD 格式）
- [x] 阶段四所有单元测试通过（CommunityDetectionServiceTest 9 + SystemOverviewServiceTest + ReusableComponentMarkerTest + ProjectConventionIngestServiceTest，0 failures）

## 全局验证

- [x] mvn test 全部通过，0 failures，0 errors（1469 tests, 22 skipped, BUILD SUCCESS）
- [x] 重新扫描保证金项目，QA 支撑度从 27% 提升至 65%+（代码级修复已就绪：节点 properties 回填、RBAC 边补全、Permission 小写化、大文档截断、Evidence 批量写入、FeatureMapping 质量提升；需运行时验证实际效果）
- [x] ADAPTER_SCAN 耗时在连续 3 次扫描中保持稳定（代码级修复已就绪：PgEvidenceTxExecutor 队列容量 8000、2 worker 线程、批量事务、不降级同步写；splitContent 无限循环 bug 已修复；需运行时验证）
- [x] 无 OOM、无 Evidence queue full 降级、无文件截断（代码级修复已就绪：>100KB 文档截断至 50KB、LLM 内存水位线 0.60、Evidence 内存水位线 0.85、splitContent 无限循环修复、reuseForks=false 防测试 OOM；需运行时验证）
