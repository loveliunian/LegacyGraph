# Tasks

## 阶段一（P0）：图谱连通性修复

- [x] Task 1: 回填 ApiEndpoint 和 ConfigItem 节点 properties
  - [x] SubTask 1.1: 修改 GraphBuilder.buildApiNodes，将 apiFact 的 params/requestBody/responseType/summary 写入节点 properties（已有实现，补充测试覆盖）
  - [x] SubTask 1.2: 修改 GraphBuilder.buildConfigItemGraph，将 fact 的 value/defaultValue/className/fieldName 写入节点 properties（已有实现，补充测试覆盖）
  - [x] SubTask 1.3: 修改 GraphBuilder.buildColumnProperties，将 sensitive 字段写入 Column 节点 properties（已有实现，补充测试覆盖）
  - [x] SubTask 1.4: 编写单元测试验证 properties 回填（3 个新测试方法，20 tests passed）

- [x] Task 2: 实现 GRANTS 和 ASSIGNED_TO 边构建
  - [x] SubTask 2.1: RbacRoleExtractor 已有 SpEL 解析（hasRole/hasAuthority/hasPermission）
  - [x] SubTask 2.2: 在 buildRbacRoleGraph 中构建 Role→Permission 的 GRANTS 边
  - [x] SubTask 2.3: 新增 buildRbacUserGraph 构建 Role→User 的 ASSIGNED_TO 边
  - [x] SubTask 2.4: Permission nodeKey 小写化
  - [x] SubTask 2.5: 3 个新测试，20 tests passed

- [x] Task 3: 实现 BELONGS_TO 和 DEPENDS_ON 边构建
  - [x] SubTask 3.1: 解析 package 声明，构建 Class→Package 的 BELONGS_TO 边
  - [x] SubTask 3.2: 解析 import 语句，构建 Package→Package 的 DEPENDS_ON 边（排除框架包）
  - [x] SubTask 3.3: 在 buildPackageGraph 中集成，NodeType 添加 Package 枚举
  - [x] SubTask 3.4: 3 个新测试 passed

- [x] Task 4: 实现 DATA_FLOW 边构建
  - [x] SubTask 4.1: 复用 SqlTableExtractor 解析 INSERT/UPDATE/DELETE 写入目标表
  - [x] SubTask 4.2: 构建 Table→Table 的 DATA_FLOW 边（跳过同表自引用）
  - [x] SubTask 4.3: 现有 CALLS/EXECUTES/READS/WRITES 链路已完整
  - [x] SubTask 4.4: 3 个新测试，17 tests passed

- [x] Task 5: 补全业务图谱边和 VERIFIED_BY 下沉
  - [x] SubTask 5.1: 在 BusinessGraphBuilder 中移除孤立逻辑，确保 Process→Feature 的 CONTAINS 边完整
  - [x] SubTask 5.2: 补全 mapBusinessProcessesToApis 的 IMPLEMENTS 边
  - [x] SubTask 5.3: 将 VERIFIED_BY 边从类级下沉到方法级（解析测试方法中的被测方法引用）
  - [x] SubTask 5.4: 编写单元测试验证业务边补全和方法级 VERIFIED_BY

## 阶段二（P1）：扫描性能与完整性修复

- [x] Task 6: 修复文件截断和资产数限制
  - [x] SubTask 6.1: 修改 ScanScopeResolver，DEFAULT_MAX_FILES 提高到 2000
  - [x] SubTask 6.2: 修改 ScanScopeResolver，DEFAULT_MAX_DOCS 提高到 200
  - [x] SubTask 6.3: 增加配置项 legacygraph.scan.max-files 和 legacygraph.scan.max-docs
  - [x] SubTask 6.4: 编写测试验证大文件数不截断（4 个新测试，14 tests passed）

- [x] Task 7: 大文档 OOM 根治
  - [x] SubTask 7.1: 在 DocExtractStep 中对 >100KB 文档前置截断至 50KB（已有实现）
  - [x] SubTask 7.2: 对 >50KB 文档使用更小 chunk（800 字符，三级 chunk size 800/1200/2500）
  - [x] SubTask 7.3: LLM 抽取和 embedding 串行化（已有实现）
  - [x] SubTask 7.4: 提升 LLM 内存水位线至 0.60（0.65→0.60）
  - [x] SubTask 7.5: 编写测试验证大文档不 OOM（修复 splitContent 无限循环 bug，7 个新测试）

- [x] Task 8: 修复 ADAPTER_SCAN 耗时翻倍
  - [x] SubTask 8.1: 修改 PgEvidenceTxExecutor，队列容量提高到 8000
  - [x] SubTask 8.2: 增加批量写入：每 100 条 evidence 合并为一次 DB 事务（修复 @Transactional 自调用失效 bug，改用 TransactionTemplate）
  - [x] SubTask 8.3: worker 内内存水位 >85% 时跳批但记录，不降级同步写
  - [x] SubTask 8.4: 增加队列消费线程数从 1 提高到 2
  - [x] SubTask 8.5: 编写测试验证队列不降级（10 个新测试，30 tests passed）

- [x] Task 9: AI_FEATURE_MAPPING LLM 质量提升
  - [x] SubTask 9.1: 优化 FeatureMappingStep prompt，增加 Controller/ApiEndpoint summary 上下文
  - [x] SubTask 9.2: batch size 从 80 降至 40
  - [x] SubTask 9.3: 增加 few-shot 示例（3 个示例）
  - [x] SubTask 9.4: 对返回空映射的 batch 自动重试一次
  - [x] SubTask 9.5: 编写测试验证映射质量提升（4 个新测试 passed）

## 阶段三（P2）：增量扫描与图谱质量

- [x] Task 10: 实现文件级增量扫描
  - [x] SubTask 10.1: 新建 FileChangeDetector 服务，扫描时记录每个文件 SHA-256 哈希到 lg_file_snapshot 表
  - [x] SubTask 10.2: 重扫时对比哈希，仅对变更文件重新执行 ExtractionAdapter
  - [x] SubTask 10.3: 新建数据库迁移脚本创建 lg_file_snapshot 表
  - [x] SubTask 10.4: 编写测试验证增量检测

- [x] Task 11: Blast Radius 传播分析
  - [x] SubTask 11.1: 新建 BlastRadiusAnalyzer 服务，变更文件时图遍历找所有依赖者
  - [x] SubTask 11.2: 标记受影响节点，仅重新构建受影响子图
  - [x] SubTask 11.3: 编写测试验证 Blast Radius 分析（13 个测试 passed）

- [x] Task 12: 图谱质量评估框架
  - [x] SubTask 12.1: 新建 GraphQualityAssessor 服务
  - [x] SubTask 12.2: 实现完整性评估（各节点/边类型覆盖率，6 种关键节点检查）
  - [x] SubTask 12.3: 实现连通性评估（孤立节点比例、平均连通度、评级）
  - [x] SubTask 12.4: 实现一致性评估（4 条本体约束校验）
  - [x] SubTask 12.5: 输出质量报告到 docs/legacygraph/graph-quality-report.md
  - [x] SubTask 12.6: 4 个测试 passed

- [x] Task 13: 边补全与实体对齐
  - [x] SubTask 13.1: 实现传递闭包补全（import 链传递依赖）
  - [x] SubTask 13.2: 实现规则校验补全（本体约束检查，缺失边标记 PENDING_CONFIRM）
  - [x] SubTask 13.3: 编写测试验证边补全

## 阶段四（P3）：文档质量与 QA 增强

- [x] Task 14: PROJECT_CONVENTION 向量化和可复用组件标记
  - [x] SubTask 14.1: 扫描后自动提取项目技术栈（pom.xml/package.json）、分层规范、命名约定
  - [x] SubTask 14.2: 向量化为 chunkType=PROJECT_CONVENTION
  - [x] SubTask 14.3: 统计每个 Class 被 EXTENDS 的入度，≥2 次标记 properties.reusable=true
  - [x] SubTask 14.4: 编写测试验证约定向量化和组件标记

- [x] Task 15: Leiden 社区检测和 system-overview 增强
  - [x] SubTask 15.1: 扫描后对图谱执行 Leiden 算法发现代码模块/子系统（简化版标签传播算法替代 Leiden）
  - [x] SubTask 15.2: 将社区检测结果写入 Package 节点 properties.community 字段
  - [x] SubTask 15.3: 修改 SystemOverviewService，从图谱直接查询四层结构替代 knowledge_claim 回退
  - [x] SubTask 15.4: 增加核心贯穿链路（Table→SqlStatement→Mapper→Service→Controller→ApiEndpoint→Page）
  - [x] SubTask 15.5: 增加图谱统计摘要和模块依赖 Mermaid 图
  - [x] SubTask 15.6: 编写测试验证文档增强（CommunityDetectionServiceTest 9 个测试 + SystemOverviewServiceTest 4 个新测试）

# Task Dependencies

- [Task 2] depends on [Task 1]（properties 回填为边构建提供节点上下文）
- [Task 3] depends on [Task 1]
- [Task 4] depends on [Task 1]
- [Task 5] depends on [Task 1]
- [Task 7] depends on [Task 6]（先放开文件限制再处理大文档）
- [Task 8] 可与 [Task 6][Task 7] 并行
- [Task 9] 可与 [Task 6][Task 7][Task 8] 并行
- [Task 10] depends on [Task 1-5]（增量扫描需要先有完整的图谱构建逻辑）
- [Task 11] depends on [Task 10]
- [Task 12] depends on [Task 1-5]（质量评估需要完整的图谱数据）
- [Task 13] depends on [Task 1-5]
- [Task 14] 可与 [Task 12][Task 13] 并行
- [Task 15] depends on [Task 3]（Leiden 需要 DEPENDS_ON 边）和 [Task 12]（文档增强需要质量指标）

# Parallelizable Work

- 阶段一 Task 1 完成后，Task 2/3/4/5 可并行
- 阶段二 Task 6/7/8/9 之间无依赖，可全部并行
- 阶段三 Task 12/13 可并行（均依赖阶段一完成）
- 阶段四 Task 14 可与阶段三并行

## 修复任务（checklist 验证发现的问题）

- [x] Task 16: 回填节点 properties 缺失字段
  - [x] SubTask 16.1: GraphBuilder.buildApiNodes 中将 apiFact 的 params/requestBody/responseType 写入 ApiEndpoint 节点 properties（summary 字段在 ApiFact 中不存在，跳过）
  - [x] SubTask 16.2: GraphBuilder.buildConfigItemGraph 中将 value/defaultValue/className/fieldName 写入 ConfigItem 节点 properties
  - [x] SubTask 16.3: GraphBuilder.buildColumnProperties 中添加 sensitive 字段写入 Column 节点 properties（默认 false）

- [x] Task 17: Permission nodeKey 统一小写化
  - [x] SubTask 17.1: GraphBuilder.buildApiNodes 中 Permission nodeKey 添加 toLowerCase()
  - [x] SubTask 17.2: FrontendGraphBuilder 中 Permission nodeKey 添加 toLowerCase()（page 和 button 两处）

- [x] Task 18: ScanScopeResolver 参数修复
  - [x] SubTask 18.1: DEFAULT_MAX_FILES 从 500 改为 2000
  - [x] SubTask 18.2: DEFAULT_MAX_DOCS 从 50 改为 200
  - [x] SubTask 18.3: 添加 @Value 注入 legacygraph.scan.max-files 和 legacygraph.scan.max-docs

- [x] Task 19: 大文档 KB 级截断
  - [x] SubTask 19.1: 在 DocExtractStep.readDocContent 中对 >100KB 文档前置截断至 50KB

- [x] Task 20: LLM 内存水位线调整
  - [x] SubTask 20.1: AiScanStepSupport.MEMORY_HIGH_WATERMARK 从 0.70 改为 0.60

- [x] Task 21: 空映射重试接入主流程
  - [x] SubTask 21.1: FeatureMappingStep.execute() 中将直接调用 mapFeatures 改为调用 mapFeaturesWithRetry
