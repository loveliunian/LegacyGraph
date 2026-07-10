# 资料扫描到图谱构建与 QA 问答全流程闭环 Spec

## Why

当前 LegacyGraph 的扫描流水线、图谱构建和 QA 问答各自能跑，但缺少一条从"上传需求资料"到"输出可落地方案"的纵向闭环。根本原因：(1) 没有 GraphRelease 发布门禁，QA 可能查到扫描中间状态；(2) 大文档被字符数截断，引用无法落到章节或页码；(3) 需求不是一等实体，无法追踪到代码/数据/测试节点；(4) 检索结果只是 LinkedHashMap 去重，没有 RRF 融合和证据验证；(5) 没有 Solution Package 生成与验证能力。本 Spec 以不可变扫描版本和 GraphRelease 为一致性边界，打通"资料解析→需求结构化→影响分析→方案生成→验证评审"全链路，服务于两个战略目标：建立图谱加速从需求到编码的过程、自动分析需求给出可落地方案。

## What Changes

### 扫描收口与发布门禁
- 新增 `GraphRelease` 持久化实体和状态机（DRAFT→VALIDATING→PUBLISHED|FAILED）
- 新增 `GraphQualityGate` 门禁，评估边/节点比、孤立率、约束违反、证据率，不通过则阻止发布
- 新增 `ScanFinalizationService` 统一收口：约定提取→可复用标记→质量评估→边补全→社区检测→产物发布→GraphRelease 发布→缓存失效
- 拆分 `ScanArtifactPublisher`，新增 `publishArtifactsOnly` 只负责报告和向量化

### 文档结构化解析
- 新增 `DocumentElement` 实体，保留章节路径（headingPath）、坐标（bbox）
- 新增 `DocumentPartitionService`：Markdown 按标题层级、Word 按样式、Excel 按 Sheet 切分（PDF 解析暂不实现，后续按需补充）
- 新增 `StructureAwareChunkService`：不跨一级标题合并、TABLE/CODE_BLOCK 单独成块、超长按句子边界切分
- 取消大文档 100KB 截断，保留尾部验收条件

### 需求模型与影响分析
- 新增 `Requirement`/`RequirementItem`/`AcceptanceCriterion`/`Constraint` 实体和图谱节点
- 新增 `RequirementExtractionService`：LLM 结构化抽取（含 few-shot，不补造信息，缺失写入 openQuestions）
- 新增 `RequirementLinkingService`：确定性优先链接（显式引用→术语映射→向量相似度>0.80），精确匹配 CONFIRMED，语义候选 PENDING_CONFIRM
- 新增 `RequirementGraphBuilder`：构建需求→条目→验收条件→受影响节点的影响子图

### 检索融合与证据验证
- 新增 `ReciprocalRankFusionService`：多路检索结果按 1/(K+rank) 加权融合排序
- `VectorDocument` 新增 `graphReleaseId`/`aclPrincipals`/`documentStatus` 字段
- 向量检索 SQL 层增加 GraphRelease 和 ACL 过滤
- 新增 `EvidenceVerifier`：校验证据存在性、归属、可访问性、来源位置、答案声明匹配
- 新增 `ConfidenceScorer`：基于证据覆盖率、可靠度、检索一致性、路径置信度、时效性动态计算

### 版本化语义缓存
- `SemanticCache` 新增 GraphRelease 和 ACL hash 维度，跨版本不命中旧缓存
- 缓存命中返回完整证据 JSON，不再丢失证据

### 方案生成与验证
- 新增 `Solution`/`SolutionStep` 实体
- 新增 `SolutionPlanner`：基于需求+影响+约定+可复用组件生成文件级实施步骤
- 新增 `SolutionVerifier`：确定性校验文件存在、符号存在、高风险覆盖、测试覆盖、证据有效、阻塞问题
- 方案通过校验才设为 `READY_FOR_REVIEW`，否则 `NEEDS_INPUT`

### QA 评测与门禁
- 新增 `QaEvaluationService` 正式服务：entityRecall、evidencePrecision、requiredKeywordCoverage、abstentionAccuracy
- `GraphRelease` 发布前执行 smoke 评测，低于阈值（recall<0.85/precision<0.90/abstain<0.95）阻止发布
- 录入至少 30 条黄金集用例

### 前端需求分析与方案评审
- 新增 `RequirementAnalysis.vue`：显示需求条目、验收条件、待确认问题、受影响节点
- 新增 `SolutionReview.vue`：显示推荐/备选方案、文件级步骤、测试、回滚、证据、校验错误
- 未解决 openQuestion 时禁用"生成方案"按钮

## Impact
- Affected specs: scan-qa-optimization（图谱连通性基础）、qa-enhancement-plan（QA 意图基础）、scan-speed-accuracy-optimization（扫描性能基础）、incremental-update-expansion（增量上下文基础）
- Affected code:
  - `task/ProjectScanner.java`、`task/AiScanJobWorker.java` — 接入 ScanFinalizationService
  - `task/step/DocExtractStep.java` — 替换为结构化解析+切块
  - `service/scan/ScanArtifactPublisher.java` — 拆分 publishArtifactsOnly
  - `service/qa/HybridRetrievalService.java` — 接入 RRF
  - `service/qa/SemanticCache.java` — 版本化改造
  - `agent/EnhancedQaAgent.java` — 集成证据验证和置信度
  - `dao/Neo4jGraphDao.java` — 新增批量存在性查询
  - `common/NodeType.java`、`common/EdgeType.java` — 新增需求/方案类型
  - `controller/EnhancedQaController.java` — AccessContext 解析
  - `frontend/src/router/index.ts`、`frontend/src/components/AppLayout.vue` — 新路由和菜单

## ADDED Requirements

### Requirement: GraphRelease 发布门禁
系统 SHALL 在扫描完成后通过 `ScanFinalizationService` 统一收口，创建 GraphRelease 并执行质量门禁。门禁不通过的版本状态为 FAILED，QA 不可查询。

#### Scenario: 质量门禁通过
- **WHEN** 扫描完成且 GraphQualityGate 评估通过（边/节点比≥1.0、孤立率≤10%、约束违反=0、证据率≥95%）
- **THEN** GraphRelease 状态变为 PUBLISHED，向量绑定 releaseId，语义缓存按项目失效

#### Scenario: 质量门禁不通过
- **WHEN** GraphQualityGate 评估不通过
- **THEN** GraphRelease 状态变为 FAILED，记录失败原因，QA 返回"该版本未通过质量门禁"

#### Scenario: 重复调用幂等
- **WHEN** 同一 project+version 重复调用 startValidation
- **THEN** 返回已有 GraphRelease 记录，不创建第二条

### Requirement: 文档结构化解析与无截断抽取
系统 SHALL 对 Markdown/Word/Excel 文档按结构分区为 DocumentElement，并保留章节路径和来源位置。系统 SHALL NOT 对超过 100KB 的文档做截断。

#### Scenario: 大文档尾部可召回
- **WHEN** 120KB 的 Markdown 文档最后一段是验收条件
- **THEN** 该验收条件作为独立 chunk 被向量化，QA 可通过语义检索召回

### Requirement: 需求结构化抽取与图谱链接
系统 SHALL 从非结构化需求文本中提取结构化需求条目、验收条件、约束和待确认问题，并与已有图谱节点建立链接。

#### Scenario: 结构化抽取
- **WHEN** 用户提交需求文本"新增结算单导出功能，支持 Excel 格式，需要财务权限"
- **THEN** 系统返回 goal、items（含 acceptanceCriteria 和 constraints）、openQuestions

#### Scenario: 确定性优先链接
- **WHEN** 需求文本包含显式 schema.table.column 引用
- **THEN** 系统通过精确匹配创建 CONFIRMED 状态的 AFFECTS 边

#### Scenario: 语义候选低置信度不创建边
- **WHEN** 向量相似度低于 0.80
- **THEN** 系统不创建 AFFECTS 边

#### Scenario: 待确认问题阻塞方案
- **WHEN** openQuestions 列表非空
- **THEN** 前端禁用"生成方案"按钮

### Requirement: RRF 混合检索
系统 SHALL 对多路检索结果使用 RRF（K=60）加权融合排序，所有检索路统一加 GraphRelease 和 ACL 过滤。

#### Scenario: 多路命中优先
- **WHEN** 同一文档同时被向量检索和关键词检索召回
- **THEN** 该文档在 RRF 融合后排名高于只被一路召回的文档

#### Scenario: 跨版本不命中
- **WHEN** 用户查询时 GraphRelease 为 r2，但向量文档绑定的是 r1
- **THEN** 该文档不进入检索结果

### Requirement: 证据验证与动态置信度
系统 SHALL 在生成答案后验证证据存在性、归属和可访问性，并基于证据覆盖率、可靠度、检索一致性、路径置信度和时效性动态计算置信度。

#### Scenario: 低覆盖率警告
- **WHEN** 答案声明的证据覆盖率低于 0.6
- **THEN** 系统标记 LOW_CONFIDENCE，前端显示警告

#### Scenario: 高风险拒答
- **WHEN** CHANGE_IMPACT 意图的置信度低于 0.5
- **THEN** 系统返回拒答消息，建议补充信息后重试

### Requirement: 版本化语义缓存
系统 SHALL 按 projectId + graphReleaseId + aclHash 维度缓存 QA 结果，命中时返回完整证据 JSON。

#### Scenario: 新版本不命中旧缓存
- **WHEN** GraphRelease 从 r1 切换到 r2
- **THEN** r1 时期的缓存不被 r2 的查询命中

#### Scenario: 不同 ACL 不互相命中
- **WHEN** 用户 A（PUBLIC）和用户 B（INTERNAL）问同一问题
- **THEN** 各自命中各自的缓存，不互相串

### Requirement: Solution Package 生成与验证
系统 SHALL 基于需求分析、影响路径、项目约定和可复用组件生成文件级实施步骤（含测试和回滚），并通过确定性校验验证可落地性。

#### Scenario: 符号不存在阻止方案
- **WHEN** 方案步骤引用的 symbolKey 在当前版本图谱中不存在
- **THEN** 验证返回 SYMBOL_NOT_FOUND 错误，方案状态为 NEEDS_INPUT

#### Scenario: 方案通过验证
- **WHEN** 所有文件路径存在、符号存在、高风险被覆盖、每步有测试、证据有效、无阻塞问题
- **THEN** 方案状态设为 READY_FOR_REVIEW

### Requirement: QA 评测门禁
系统 SHALL 在 GraphRelease 发布前执行 smoke 评测，低于阈值的版本被阻止发布。

#### Scenario: smoke 评测通过
- **WHEN** entityRecall≥0.85 且 evidencePrecision≥0.90 且 abstentionAccuracy≥0.95
- **THEN** GraphRelease 可进入 PUBLISHED

#### Scenario: smoke 评测不通过
- **WHEN** 任一指标低于阈值
- **THEN** GraphRelease 状态为 FAILED，记录失败指标

## MODIFIED Requirements

### Requirement: 扫描收口流程
扫描完成后 SHALL 通过 ScanFinalizationService 统一编排后置步骤，不再由 ProjectScanner 和 AiScanJobWorker 分散调用。GraphRelease 开关默认关闭（`legacygraph.graph-release.enabled=false`），生产升级前走旧路径。

### Requirement: HybridRetrievalService 检索合并
`HybridRetrievalService` SHALL 在 RRF 开启时（`legacygraph.qa.rrf-enabled=true`）使用 RRF 融合排序，关闭时保留原 LinkedHashMap 去重，便于灰度和回滚。

### Requirement: EnhancedQaAgent 置信度计算
`EnhancedQaAgent` SHALL 删除固定 confidence=1.0/0.8，改为生成完成后先验证证据并计算动态置信度，再保存消息和缓存。

## REMOVED Requirements

### Requirement: 大文档 100KB 截断
**Reason**: 截断导致尾部验收条件丢失，结构化解析后无需截断
**Migration**: 使用 StructureAwareChunkService 按结构切块，大文档分级 chunk size（>50KB 用 2500，>20KB 用 1800）
