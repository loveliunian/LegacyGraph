# 扫描任务 QA 支撑度优化 Spec

## Why

当前扫描任务产出的图谱综合 QA 支撑度仅约 27%。核心矛盾：数据模型已覆盖 40 种节点类型和 36 种边类型，但实际入库的关键边远少于设计预期——GRANTS、BELONGS_TO、DATA_FLOW、CONTAINS 等边缺失，节点成孤岛；ApiEndpoint/ConfigItem 的 properties 关键字段被 GraphBuilder 丢弃；837 文件仅扫 500 个（截断 40%）、大文档 OOM 跳过、ADAPTER_SCAN 耗时逐次翻倍。QA 系统虽有 16 种意图处理链路，却因图谱连通性不足和扫描不完整而无法回答多数类型的问题。

## What Changes

### 阶段一（P0）：图谱连通性修复
- 回填 ApiEndpoint 节点 properties（params/requestBody/responseType/summary）
- 回填 ConfigItem 节点 properties（value/defaultValue/className/fieldName）
- 回填 Column 节点 sensitive 属性
- 实现 GRANTS（Role→Permission）边构建：RbacRoleExtractor 解析 @PreAuthorize SpEL
- 实现 ASSIGNED_TO（Role→User）边构建
- 实现 BELONGS_TO（Class→Package）边构建：解析 package 声明
- 实现 DEPENDS_ON（Package→Package）边构建：解析 import 语句
- 实现 DATA_FLOW（Table→下游）边构建：JSqlParser 解析 INSERT/UPDATE/DELETE 写入目标
- 补全 BusinessProcess→Feature 的 CONTAINS 边和 BusinessProcess→ApiEndpoint 的 IMPLEMENTS 边
- VERIFIED_BY 边下沉到方法级

### 阶段二（P1）：扫描性能与完整性修复
- DEFAULT_MAX_FILES 从 500 提高到 2000，DEFAULT_MAX_DOCS 从 50 提高到 200
- 大文档（>100KB）前置截断至 50KB；LLM 抽取与 embedding 严格串行化
- **BREAKING**: LLM 内存水位线从 0.70 调整为 0.60
- Evidence 队列容量从 2000 提高到 8000，增加批量写入（100 条/事务），消费线程从 1 提高到 2
- AI_FEATURE_MAPPING prompt 优化：增加代码上下文、batch size 80→40、空映射自动重试

### 阶段三（P2）：增量扫描与图谱质量
- 新增 FileChangeDetector 服务：SHA-256 文件哈希增量检测
- Blast Radius 传播分析：变更文件图遍历找依赖者，仅重建受影响子图
- 新增 GraphQualityAssessor 服务：完整性/连通性/准确性/一致性评估
- 边补全：传递闭包补全 + 规则校验补全

### 阶段四（P3）：文档质量与 QA 增强
- PROJECT_CONVENTION 向量化：提取技术栈/分层规范/命名约定
- 可复用组件标记：统计 EXTENDS 入度，≥2 次标记 reusable=true
- Leiden 社区检测：自动发现代码模块/子系统
- system-overview.md 内容增强：从图谱直接查询四层结构

## Impact

- **Affected specs**: `qa-enhancement-plan`（QA 意图和链路已实现，但依赖的图谱数据不完整）、`external-tool-verification`（外部验证可补充缺失边）
- **Affected code**:
  - `builder/GraphBuilder.java` — 节点 properties 回填 + 5 个 buildXxxGraph 边构建增强
  - `builder/BusinessGraphBuilder.java` — CONTAINS/IMPLEMENTS 边补全
  - `extractor/RbacRoleExtractor.java` — SpEL 解析建 GRANTS 边
  - `extractor/PackageExtractor.java` — BELONGS_TO/DEPENDS_ON 边构建
  - `service/scan/ScanScopeResolver.java` — MAX_FILES/MAX_DOCS 提高
  - `task/step/DocExtractStep.java` — 大文档截断 + 串行化
  - `task/step/FeatureMappingStep.java` — prompt 优化
  - `builder/PgEvidenceTxExecutor.java` — 队列扩容 + 批量写入
  - 新建文件：`FileChangeDetector.java`、`GraphQualityAssessor.java`、`BlastRadiusAnalyzer.java`
- **BREAKING**: Column nodeKey 统一为 `{tableName.lower}.{column.lower}`，旧 schema 前缀 Column 节点变孤立，需重新扫描
- **BREAKING**: LLM 内存水位线从 0.70 降至 0.60，可能在大文档场景更频繁触发背压

## ADDED Requirements

### Requirement: 节点属性完整性
系统 SHALL 在 GraphBuilder 创建节点时，将 Extractor 抽取的所有业务属性写入节点 properties 字段，不得丢弃。

#### Scenario: ApiEndpoint 契约信息可查
- **GIVEN** 扫描完成，ApiEndpoint 节点已入库
- **WHEN** QA 系统查询接口契约
- **THEN** ApiEndpoint.properties 包含 params、requestBody、responseType、summary 字段
- **AND** QA 可回答"这个接口接收什么参数"

#### Scenario: ConfigItem 配置值可查
- **GIVEN** 扫描完成，ConfigItem 节点已入库
- **WHEN** QA 系统查询配置项
- **THEN** ConfigItem.properties 包含 value、defaultValue、className、fieldName 字段
- **AND** QA 可回答"配置项 xxx 的值是什么"

### Requirement: RBAC 权限链路连通
系统 SHALL 在扫描时构建 Role→Permission 的 GRANTS 边和 Role→User 的 ASSIGNED_TO 边，确保权限链路完整。

#### Scenario: 权限问题可回答
- **GIVEN** 代码中有 @PreAuthorize("hasRole('ADMIN')") 注解
- **WHEN** 扫描完成
- **THEN** 图谱中存在 Role(ADMIN) --GRANTS--> Permission 节点的边
- **AND** QA 可回答"调用这个接口需要什么权限"

### Requirement: 架构依赖链路连通
系统 SHALL 在扫描时构建 Class→Package 的 BELONGS_TO 边和 Package→Package 的 DEPENDS_ON 边。

#### Scenario: 模块依赖可查询
- **GIVEN** PackageA 中的类 import 了 PackageB 中的类
- **WHEN** 扫描完成
- **THEN** 图谱中存在 PackageA --DEPENDS_ON--> PackageB 的边
- **AND** QA 可回答"这个模块依赖哪些其他模块"

### Requirement: 数据血缘可追踪
系统 SHALL 在扫描时构建 Table→下游消费方的 DATA_FLOW 边，通过 SqlStatement 中间节点连接。

#### Scenario: 数据流向可查询
- **GIVEN** Mapper 中有 INSERT INTO table_b SELECT ... FROM table_a 的 SQL
- **WHEN** 扫描完成
- **THEN** 图谱中存在 table_a --DATA_FLOW--> table_b 的边（经 SqlStatement 中间节点）
- **AND** QA 可回答"table_a 的数据流向哪里"

### Requirement: 扫描文件完整性
系统 SHALL 扫描项目内所有匹配的源文件，不得因默认上限截断。

#### Scenario: 大项目全量扫描
- **GIVEN** 项目有 837 个源文件
- **WHEN** 执行扫描
- **THEN** 所有 837 个文件均被扫描
- **AND** 无文件因 maxFiles 限制被截断

### Requirement: 大文档容错处理
系统 SHALL 对超大文档（>100KB）前置截断至 50KB 后再进行 LLM 抽取，不得因 OOM 跳过。

#### Scenario: 134KB 文档不 OOM
- **GIVEN** 项目有一篇 134KB 的文档
- **WHEN** 执行 AI_DOC_EXTRACT
- **THEN** 文档被截断至 50KB 后正常抽取
- **AND** 不触发 OOM
- **AND** 至少产出部分业务事实

### Requirement: ADAPTER_SCAN 性能稳定
系统 SHALL 保证 ADAPTER_SCAN 耗时在不同扫描版本间保持稳定，不得逐次翻倍。

#### Scenario: 连续扫描耗时稳定
- **GIVEN** 同一项目连续执行 3 次扫描
- **WHEN** 对比 ADAPTER_SCAN 耗时
- **THEN** 第 3 次耗时不超过第 1 次的 1.5 倍
- **AND** Evidence queue full 降级次数为 0

### Requirement: 增量扫描
系统 SHALL 支持基于文件 SHA-256 哈希的增量扫描，仅对变更文件重新执行 ExtractionAdapter。

#### Scenario: 少量文件变更快速重扫
- **GIVEN** 项目已完成首次全量扫描
- **WHEN** 修改 5 个文件后重新扫描
- **THEN** 仅这 5 个文件及其依赖者被重新分析
- **AND** 重扫耗时不超过全量扫描的 10%

### Requirement: 图谱质量评估
系统 SHALL 在扫描完成后自动评估图谱质量，输出完整性、连通性、准确性、一致性指标。

#### Scenario: 质量报告自动生成
- **GIVEN** 扫描完成
- **WHEN** 质量评估执行
- **THEN** 生成 docs/legacygraph/graph-quality-report.md
- **AND** 报告包含各节点类型覆盖率、孤立节点比例、边准确性抽样结果

## MODIFIED Requirements

### Requirement: Evidence 队列写入
Evidence 写入队列容量从 2000 提高到 8000，增加批量写入（100 条/事务），消费线程从 1 提高到 2，worker 内内存水位 >85% 时跳批但不降级同步写。

### Requirement: AI_FEATURE_MAPPING LLM 映射
FeatureMappingStep 的 prompt 增加代码上下文（Controller/ApiEndpoint summary），batch size 从 80 降至 40，对返回空映射的 batch 自动重试一次。

### Requirement: system-overview 文档生成
system-overview.md 的数据源从依赖 lg_knowledge_claim 表改为直接从图谱查询四层结构节点和边，增加核心贯穿链路和图谱统计摘要。
