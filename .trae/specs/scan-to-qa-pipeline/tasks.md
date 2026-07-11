# Tasks

> 对应实施计划 T1-T13。执行顺序按阶段编号，同阶段内可并行。GraphRelease/RRF/Solution 相关变更默认 Feature Flag 关闭，支持灰度回滚。

## 阶段一：扫描收口与发布门禁

- [x] Task 1: GraphRelease 持久化与状态机
  - [ ] 1.1 创建 `GraphRelease` 实体（id, projectId, scanVersionId, graphVersionTag, status, createdAt, publishedAt, failureReasons）
  - [ ] 1.2 创建 `GraphReleaseRepository`（findByProjectAndVersion 唯一约束）
  - [ ] 1.3 实现状态机：`DRAFT → VALIDATING → PUBLISHED | FAILED`（含幂等校验：同 project+version 重复调用返回已有记录）
  - [ ] 1.4 新增配置开关 `legacygraph.graph-release.enabled`（默认 false）
  - [ ] 1.5 新增数据库迁移脚本：`lg_graph_release` 表 + 唯一索引 `(project_id, scan_version_id)`

- [x] Task 2: GraphQualityGate 质量门禁
  - [ ] 2.1 创建 `GraphQualityGate` 接口和 `DefaultGraphQualityGate` 实现
  - [ ] 2.2 实现 `GraphQualitySnapshot` 数据采集：调用 Neo4jGraphDao 统计边/节点比、孤立率、约束违反、证据率
  - [ ] 2.3 实现门禁规则：EDGE_NODE_RATIO_BELOW_1、ISOLATED_RATE_ABOVE_10_PERCENT、CONSTRAINT_VIOLATIONS、EVIDENCE_RATE_BELOW_95_PERCENT
  - [ ] 2.4 返回 `GraphQualityGate.Decision(passed, reasons)`，不通过返回具体原因

- [x] Task 3: ScanFinalizationService 统一收口
  - [ ] 3.1 创建 `ScanFinalizationService`，编排收口流程：约定提取→可复用标记→质量评估→边补全→社区检测→产物发布→质量门禁→GraphRelease 发布→缓存失效
  - [ ] 3.2 拆分 `ScanArtifactPublisher`，新增 `publishArtifactsOnly` 方法只负责报告和向量化
  - [ ] 3.3 修改 `ProjectScanner.runPostScanConventionIngest` 和 `AiScanJobWorker`，在 `legacygraph.graph-release.enabled=true` 时调用 `ScanFinalizationService.finalize`，false 时保留旧路径
  - [ ] 3.4 GraphRelease 发布后调用 `SemanticCache.invalidateByProject(projectId)` 失效缓存

## 阶段二：文档结构化解析

- [x] Task 4: DocumentElement 与结构化切块
  - [ ] 4.1 创建 `DocumentElement` 实体（id, docId, type, text, headingPath, bbox, sourceLocation）
  - [ ] 4.2 创建 `DocumentPartitionService` 接口和 `DefaultDocumentPartitionService` 实现
  - [ ] 4.3 实现 `MarkdownPartitioner`：按 `#/##/###` 标题层级生成 TITLE 元素，维护 headingPath，代码块生成 CODE_BLOCK，表格生成 TABLE
  - [ ] 4.4 实现 `WordPartitioner`：Apache POI 按段落样式（Heading1/Normal）生成对应元素
  - [ ] 4.5 实现 `ExcelPartitioner`：按 Sheet 名 + 行范围生成 TABLE 元素，sourceLocation = `file#sheet:rowStart-rowEnd`
  - [ ] 4.6 实现 `PlainTextPartitioner`：按空行分段

- [x] Task 5: StructureAwareChunkService
  - [ ] 5.1 创建 `StructureAwareChunkService`，输入 DocumentElement 列表，输出 DocumentChunk 列表
  - [ ] 5.2 实现切块规则：不跨一级标题合并、TABLE 单独成块、CODE_BLOCK 单独成块、超长段落按句子边界切分
  - [ ] 5.3 每块前缀包含 headingPath，携带 sourceLocation
  - [ ] 5.4 修改 `DocExtractStep`：替换 DocumentContentService 为 DocumentPartitionService + StructureAwareChunkService
  - [ ] 5.5 取消 100KB 截断逻辑，大文档分级 chunk size（>50KB 用 2500，>20KB 用 1800）

## 阶段三：需求模型与影响分析

- [x] Task 6: 需求结构化抽取与图谱构建
  - [ ] 6.1 新增 NodeType：Requirement, RequirementItem, AcceptanceCriterion, Constraint, Assumption, OpenQuestion
  - [ ] 6.2 新增 EdgeType：HAS_ITEM, HAS_ACCEPTANCE_CRITERION, HAS_CONSTRAINT, HAS_ASSUMPTION, RAISES_QUESTION, AFFECTS, SATISFIES, DERIVED_FROM, VERIFIES
  - [ ] 6.3 创建 `RequirementExtractionService`：LLM 结构化抽取（system prompt 约束不补造信息，缺失写入 openQuestions），输出 RequirementAnalysis DTO
  - [ ] 6.4 创建 `requirement-analysis.txt` prompt 模板（含 3 条 few-shot：完整匹配/无按钮/未匹配场景）
  - [ ] 6.5 创建 `RequirementGraphBuilder`：构建 Requirement→RequirementItem→AcceptanceCriterion/Constraint 节点和边
  - [ ] 6.6 创建 `RequirementController`：POST `/lg/projects/{projectId}/requirements/analyze` 提交需求文本，返回结构化分析
  - [ ] 6.7 新增数据库迁移：`lg_requirement`、`lg_requirement_item`、`lg_acceptance_criterion` 表

- [x] Task 7: 需求-图谱链接与影响子图
  - [ ] 7.1 创建 `RequirementLinkingService`：确定性优先链接（schema.table.column / FQN / URL / 文件路径精确匹配→术语映射→向量相似度>0.80 语义匹配）
  - [ ] 7.2 精确匹配标记 CONFIRMED，语义匹配标记 PENDING_CONFIRM，低于阈值不创建边
  - [ ] 7.3 创建 `ImpactSubgraphService`：从链接节点出发，沿图谱边 BFS 提取影响路径
  - [ ] 7.4 返回影响子图结构化数据（受影响节点列表 + 路径）
  - [ ] 7.5 RequirementController 增加 POST `/lg/projects/{projectId}/requirements/{requirementId}/impact` 返回影响分析

## 阶段四：检索融合与证据验证

- [x] Task 8: RRF 混合检索
  - [ ] 8.1 创建 `ReciprocalRankFusionService`（K=60，加权 RRF）
  - [ ] 8.2 `VectorDocument` 新增字段：graphReleaseId, aclPrincipals(JSON), documentStatus
  - [ ] 8.3 修改 `VectorRetrievalService` SQL：新增 GraphRelease 和 ACL 过滤条件
  - [ ] 8.4 修改 `HybridRetrievalService`：RRF 开启时（`legacygraph.qa.rrf-enabled=true`）使用 RRF 融合，关闭时保留 LinkedHashMap 去重
  - [ ] 8.5 新增数据库迁移：`lg_vector_document` 表新增列 + 索引

- [x] Task 9: 证据验证与版本化缓存
  - [ ] 9.1 创建 `EvidenceVerifier`：校验证据存在性、归属当前 project+graphRelease、ACL 可访问、sourceLocation 非空、答案声明匹配
  - [ ] 9.2 创建 `ConfidenceScorer`：基于证据覆盖率(0.30)、可靠度(0.25)、检索一致性(0.20)、路径置信度(0.15)、时效性(0.10) 动态计算
  - [ ] 9.3 高风险意图（CHANGE_IMPACT）权重调整：pathConfidence 0.35、coverage 0.20
  - [ ] 9.4 修改 `EnhancedQaAgent`：删除固定 confidence=1.0/0.8，改为生成后验证→计算置信度→保存消息和缓存
  - [ ] 9.5 低覆盖率(<0.6)标记 LOW_CONFIDENCE，高风险意图置信度(<0.5)拒答
  - [ ] 9.6 `SemanticCache` 新增 graphReleaseId 和 aclHash 维度，缓存命中返回完整证据 JSON
  - [ ] 9.7 修改 `EnhancedQaController`：从 `RequestContextHolder`/SecurityContext 解析 AccessContext（principals + team）
  - [ ] 9.8 `SemanticCacheEntry` 实体新增 graphReleaseId/aclHash/intent/confidence 字段
  - [ ] 9.9 新增数据库迁移：`lg_semantic_cache_entry` 表新增列 + 索引

## 阶段五：方案生成与验证

- [x] Task 10: Solution Package 生成与验证
  - [ ] 10.1 新增 NodeType：Solution, SolutionStep；EdgeType：STEP_OF, VALIDATED_BY, REVISED_BY
  - [ ] 10.2 创建 `Solution`/`SolutionStep` 实体和 Repository
  - [ ] 10.3 创建 `SolutionPlanner`：基于 RequirementAnalysis + ImpactResult + 项目约定 + 可复用组件，LLM 生成文件级实施步骤（含测试和回滚）
  - [ ] 10.4 创建 `solution-planning.txt` prompt 模板（约束每步含文件路径+符号+证据 ID，遵循分层规范，优先复用组件）
  - [ ] 10.5 创建 `SolutionVerifier`：确定性校验（文件存在、符号存在、高风险覆盖、测试覆盖、证据有效、阻塞问题）
  - [ ] 10.6 校验通过设为 READY_FOR_REVIEW，失败设为 NEEDS_INPUT 并记录错误
  - [ ] 10.7 创建 `SolutionController`：POST `/lg/projects/{projectId}/solutions/generate`、GET `/lg/solutions/{solutionId}`、POST `/lg/solutions/{solutionId}/verify`
  - [ ] 10.8 新增数据库迁移：`lg_solution`、`lg_solution_step` 表

## 阶段六：QA 评测与门禁

- [x] Task 11: QA 评测门禁
  - [ ] 11.1 创建 `QaEvaluationService` 接口和 `DefaultQaEvaluationService` 实现
  - [ ] 11.2 实现评估指标：entityRecall、evidencePrecision、requiredKeywordCoverage、abstentionAccuracy
  - [ ] 11.3 创建 `QaTestCase` 实体（question, expectedEntities, expectedKeywords, shouldAbstain）
  - [ ] 11.4 录入至少 30 条黄金集测试用例（覆盖 7 种 QueryIntent）
  - [ ] 11.5 `ScanFinalizationService` 在 GraphRelease 发布前调用 `QaEvaluationService.runSmoke`，低于阈值（recall<0.85/precision<0.90/abstain<0.95）阻止发布
  - [ ] 11.6 评测报告写入 `docs/legacygraph/qa-evaluation-{versionId}.json`
  - [ ] 11.7 新增数据库迁移：`lg_qa_test_case` 表

## 阶段七：前端需求分析与方案评审

- [ ] Task 12: 前端需求分析与方案评审页面
  - [ ] 12.1 新增 `RequirementAnalysis.vue`：提交需求文本→展示结构化条目、验收条件、约束、待确认问题、受影响节点列表
  - [ ] 12.2 openQuestions 非空时禁用"生成方案"按钮，显示待确认问题清单
  - [ ] 12.3 新增 `SolutionReview.vue`：展示推荐方案/备选方案、文件级步骤、测试、回滚、证据卡片、校验错误
  - [ ] 12.4 证据卡片点击展示详情（来源文件、行号、nodeKey）
  - [ ] 12.5 `frontend/src/router/index.ts` 新增路由 `/projects/:projectId/requirements` 和 `/projects/:projectId/solutions/:solutionId`
  - [ ] 12.6 `AppLayout.vue` 侧边栏新增"需求分析"和"方案评审"菜单项
  - [ ] 12.7 `frontend/src/api/` 新增 `requirement.ts` 和 `solution.ts` API 封装

## 阶段八：集成验证

- [ ] Task 13: 集成验证与回归测试
  - [ ] 13.1 集成测试：上传需求文档→扫描→GraphRelease 发布→需求分析→方案生成→验证→QA 问答全链路
  - [ ] 13.2 回归测试：GraphRelease 关闭时全流程走旧路径，验证无破坏
  - [ ] 13.3 回归测试：RRF 关闭时检索走 LinkedHashMap 去重，验证无破坏
  - [ ] 13.4 单元测试：GraphQualityGate 门禁规则、RRF 融合算法、EvidenceVerifier 验证逻辑、ConfidenceScorer 评分逻辑、RequirementLinkingService 链接策略
  - [ ] 13.5 性能测试：RRF 融合延迟 < 50ms，结构化解析 120KB 文档 < 5s

# Task Dependencies
- [Task 2] 依赖 [Task 1]（门禁评估结果写入 GraphRelease）
- [Task 3] 依赖 [Task 1, Task 2]（收口流程编排 GraphRelease 和门禁）
- [Task 5] 依赖 [Task 4]（StructureAwareChunkService 消费 DocumentElement）
- [Task 7] 依赖 [Task 6]（影响分析基于需求图谱）
- [Task 8] 依赖 [Task 3]（向量检索需要 GraphRelease 过滤）
- [Task 9] 依赖 [Task 8]（证据验证和缓存版本化依赖 GraphRelease 和 ACL）
- [Task 10] 依赖 [Task 7, Task 9]（方案生成基于需求分析和证据验证）
- [Task 11] 依赖 [Task 3, Task 9]（评测门禁在发布前执行，依赖 QA 链路完整）
- [Task 12] 依赖 [Task 6, Task 10]（前端页面依赖后端 API）
- [Task 13] 依赖 [Task 1-12]（集成验证依赖全部完成）

# Parallelizable Work
- 阶段一（Task 1-3）与阶段二（Task 4-5）可并行
- 阶段三（Task 6-7）与阶段四前半（Task 8）可并行
- Task 11（QA 评测）的用例录入（11.3-11.4）可与 Task 9 并行
