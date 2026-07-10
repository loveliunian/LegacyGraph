# 从资料到图谱和总结文档的准确性优化 Spec

## Why
当前系统已能"从资料产出图谱和报告"，但准确性闭环未真正形成：证据链断裂（Claim 的 evidenceIds 写文件路径而非真实 ID）、大文档静默丢内容（100KB 截断致后半永久丢失）、总结阶段补造事实（按表名猜读写、按类名猜业务域并冒充 CONFIRMED）、报告真值口径不统一（PENDING_CONFIRM 混入正文）、准确率指标失真（只查端点非空，空图谱得 100%）、产物发布未接通主流程（ScanArtifactPublisher 零调用，Markdown 生成返回 null）。

## What Changes
- **P0-1 修复证据链断裂**：DocUnderstandingAgent.toClaimDrafts() 传入 evidenceRefs 创建真实 Evidence 记录；EvidenceGraphWriter.upsertNode() 移除 created() 限制以追加多来源证据；KnowledgeClaim 填充 supporting/contradictingClaimIds
- **P0-2 禁止大文档静默丢内容**：删除 readDocContent() 中 100KB→50KB 前置截断，改为全量分块抽取；修复 mergeByKey() 置信度取值（实际保留首个，注释声称取最高）；splitContent() 返回带 chunkIndex/charStart/charEnd 元数据的 chunk
- **P0-3 停止补造事实**：SystemOverviewIngestService.toClaims() 中推断的 READS/WRITES 和业务域关系标记为 AI_INFERENCE + confidence < 0.85，lineage 字段记录推导规则
- **P0-4 统一报告真值口径**：新建 ReportTruthPolicy 策略类；SystemOverviewService 拆分正文（仅 CONFIRMED）/附录（PENDING_CONFIRM/INFERRED）；截断时显示覆盖率提示
- **P0-5 重做准确率指标**：GraphQualityAssessor.assessAccuracy() 拆为结构完整性、三元组准确率、证据覆盖率、置信度校准 4 个独立指标（实体消歧/总结准确率留阶段二三）
- **P0-6 调整发布顺序并接通主流程**：ScanArtifactPublisher.publish() 调整为边补全→社区检测→质量评估→总结报告；ProjectScanner 和 AiScanJobWorker 注入并调用 publish()；实现 SystemOverviewDocumentService.generateMarkdownContent() 移除 null 存根

## Impact
- Affected specs: scan-qa-optimization（扫描产物发布流程）、qa-enhancement-plan（QA 引用的 Claim 真值口径）
- Affected code:
  - `backend/src/main/java/io/github/legacygraph/agent/DocUnderstandingAgent.java` — 证据链创建
  - `backend/src/main/java/io/github/legacygraph/builder/EvidenceGraphWriter.java` — 多来源证据追加
  - `backend/src/main/java/io/github/legacygraph/task/step/DocExtractStep.java` — 截断移除、mergeByKey 修复、分块元数据
  - `backend/src/main/java/io/github/legacygraph/service/systemoverview/SystemOverviewIngestService.java` — 推断降级
  - `backend/src/main/java/io/github/legacygraph/service/systemoverview/SystemOverviewService.java` — 真值口径分区
  - `backend/src/main/java/io/github/legacygraph/service/scan/GraphQualityAssessor.java` — 准确率指标重做
  - `backend/src/main/java/io/github/legacygraph/service/scan/ScanArtifactPublisher.java` — 发布顺序调整
  - `backend/src/main/java/io/github/legacygraph/task/ProjectScanner.java` — 接通发布
  - `backend/src/main/java/io/github/legacygraph/task/AiScanJobWorker.java` — 接通发布
  - `backend/src/main/java/io/github/legacygraph/service/systemoverview/SystemOverviewDocumentService.java` — 实现 Markdown 生成
  - `backend/src/main/java/io/github/legacygraph/dao/Neo4jGraphDao.java` — 新增悬空边/重复节点/带证据采样查询
  - `backend/src/main/java/io/github/legacygraph/dao/Neo4jProjectionRepository.java` — 截断返回 totalCount

## ADDED Requirements

### Requirement: 真实证据 ID 关联
Claim 的 evidenceIds 字段 SHALL 存储真实 Evidence UUID，而非文件路径。每条 Evidence SHALL 包含 contentExcerpt、sourcePath、chunkId 及 metadata（含 extractor、model、promptVersion、contentHash）。

#### Scenario: LLM 返回 EvidenceRef 时创建真实证据
- **WHEN** DocUnderstandingAgent.toClaimDrafts() 处理含 evidence 列表的 BusinessFactExtraction
- **THEN** 为每个 EvidenceRef 创建 Evidence 记录并使用其 UUID 填充 Claim.evidenceIds

#### Scenario: 已存在节点追加多来源证据
- **WHEN** EvidenceGraphWriter.upsertNode() 命中已存在节点（upsert.created() == false）
- **AND** 该节点有新的 sourcePath 或 AI 来源
- **THEN** 系统仍创建证据记录并通过 contentHash 去重，lg_node_evidence 中存在多条关联记录

### Requirement: 大文档全量分块抽取
文档 SHALL NOT 在进入分块抽取前被前置截断。splitContent() 返回的每个 chunk SHALL 携带 chunkIndex、charStart、charEnd 元数据，写入 Evidence.metadata。

#### Scenario: 150KB 文档末尾内容进入图谱
- **WHEN** 文档内容为 150KB
- **THEN** 末尾内容出现在抽取结果中（不再被截断丢弃）
- **AND** 分块抽取产生的 Evidence 含 chunkIndex、charStart、charEnd 元数据

#### Scenario: mergeByKey 同名项取较高置信度
- **WHEN** 多段抽取结果合并时出现同名业务对象
- **THEN** 保留置信度较高的项（而非首个）

### Requirement: 推断关系降级为 PENDING_CONFIRM
无法从 AST、SQL、运行轨迹或文档证据直接证明的派生关系（按表名猜读写、按类名猜业务域）SHALL 标记为 sourceType=AI_INFERENCE 且 confidence < 0.85，确保不会被 computeStatus() 判为 CONFIRMED。推断 Claim 的 lineage 字段 SHALL 记录推导规则。

#### Scenario: 表名推断的 WRITES 不冒充 CONFIRMED
- **WHEN** SystemOverviewIngestService.toClaims() 对非 _log/_snapshot 表生成 WRITES 关系
- **AND** 该读写关系未经 AST/SQL/运行轨迹证明
- **THEN** Claim 的 sourceType 为 AI_INFERENCE，confidence 为 0.6，状态为 PENDING_CONFIRM
- **AND** lineage 字段记录 {"rule":"TABLE_NAME_HEURISTIC","description":"非 _log/_snapshot 表推断为 WRITES"}

### Requirement: 报告真值口径分区
报告正文 SHALL 仅包含 CONFIRMED 状态的 Claim。PENDING_CONFIRM/INFERRED Claim SHALL 出现在附录区并有明确标识。REJECTED/STALE/CONFLICTED SHALL NOT 出现在报告中。截断时 SHALL 显示"覆盖 X/Y，尚有 N 条未展示"。

#### Scenario: 报告正文不含待确认事实
- **WHEN** SystemOverviewService 生成 Markdown 报告
- **THEN** 正文区仅含 CONFIRMED Claim
- **AND** PENDING_CONFIRM/INFERRED Claim 在附录区且标注"待确认/推断"

#### Scenario: 截断显示覆盖率
- **WHEN** Claim 总数超过 500 条限制
- **THEN** 报告显示"覆盖 500/N，尚有 N-500 条未展示"

### Requirement: 多维度质量指标
GraphQualityAssessor SHALL 输出结构完整性（悬空边、重复节点、约束违反）、三元组准确率（抽样边是否有证据支撑）、证据覆盖率（有证据节点/边占比）、置信度校准（按来源分桶 Precision）4 个独立指标。空图谱 SHALL NOT 得 100% 准确率。

#### Scenario: 空图谱不得满分
- **WHEN** 图谱无节点无边
- **THEN** 各指标显示 0 或 N/A，准确率非 100%

#### Scenario: 悬空边被检测
- **WHEN** 图谱存在 from/to 节点不存在的边
- **THEN** 结构完整性指标中 danglingEdges > 0 并在报告中标红

### Requirement: 产物发布接通主流程且顺序正确
ScanArtifactPublisher SHALL 在扫描完成（基础扫描或 AI 编排）后被调用。发布顺序 SHALL 为：边补全 → 社区检测 → 质量评估 → 总结报告。SystemOverviewDocumentService.generateMarkdownContent() SHALL 返回非 null 内容。发布失败 SHALL NOT 阻塞扫描主流程。

#### Scenario: 基础扫描完成后发布产物
- **WHEN** ProjectScanner 完成扫描且 AI 未启用
- **THEN** 调用 ScanArtifactPublisher.publish() 生成 docs/legacygraph/ 下全部产物
- **AND** system-overview.md 内容非空

#### Scenario: AI 编排完成后发布产物
- **WHEN** AiScanJobWorker 完成 AI 编排全部步骤
- **THEN** 调用 ScanArtifactPublisher.publish() 发布产物

#### Scenario: 发布顺序正确
- **WHEN** ScanArtifactPublisher.publish() 执行
- **THEN** 先执行 completeEdges，再 detectCommunities，再 publishGraphQualityReport，最后 publishSystemOverview

## MODIFIED Requirements

### Requirement: EvidenceGraphWriter 证据创建条件
原条件：仅在 upsert.created() == true 时创建证据。修改为：只要存在 sourcePath 或 AI 来源即创建证据，通过 contentHash 和 NodeEvidence 唯一约束去重。

### Requirement: DocExtractStep 大文档处理
原逻辑：100KB 以上文档前置截断至 50KB。修改为：返回完整内容，由 extractFromChunks() 全量分块处理。

### Requirement: SystemOverviewIngestService 推断关系置信度
原逻辑：推断关系标为 CODE + 0.85（自动 CONFIRMED）。修改为：推断关系标为 AI_INFERENCE + 0.6（PENDING_CONFIRM）。

### Requirement: GraphQualityAssessor 准确率评估
原逻辑：assessAccuracy() 仅检查端点非空。修改为：拆为结构完整性、三元组准确率、证据覆盖率、置信度校准 4 个独立指标。

### Requirement: ScanArtifactPublisher 发布顺序
原顺序：总结报告 → 边补全 → 社区检测。修改为：边补全 → 社区检测 → 质量评估 → 总结报告。
