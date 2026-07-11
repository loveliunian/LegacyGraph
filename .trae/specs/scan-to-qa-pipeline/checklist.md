# Checklist

## GraphRelease 发布门禁
- [x] GraphRelease 实体包含 id/projectId/scanVersionId/graphVersionTag/status/createdAt/publishedAt/failureReasons 字段
- [x] 状态机 DRAFT→VALIDATING→PUBLISHED|FAILED 实现正确
- [x] 同 project+version 重复调用 startValidation 返回已有记录（幂等）
- [x] GraphQualityGate 评估规则覆盖：边/节点比≥1.0、孤立率≤10%、约束违反=0、证据率≥95%
- [x] 门禁不通过时 GraphRelease 状态为 FAILED 且记录失败原因
- [x] 门禁不通过时 QA 返回"该版本未通过质量门禁"
- [x] ScanFinalizationService 编排顺序正确：约定提取→可复用标记→质量评估→边补全→社区检测→产物发布→门禁→发布→缓存失效
- [x] ScanArtifactPublisher 拆分出 publishArtifactsOnly 方法
- [x] `legacygraph.graph-release.enabled=false` 时全流程走旧路径无破坏
- [x] GraphRelease 发布后调用 SemanticCache.invalidateByProject

## 文档结构化解析
- [x] DocumentElement 实体包含 type/text/headingPath/bbox/sourceLocation 字段
- [x] MarkdownPartitioner 按 #/##/### 标题层级生成 TITLE 并维护 headingPath
- [x] MarkdownPartitioner 代码块生成 CODE_BLOCK、表格生成 TABLE
- [x] WordPartitioner 按段落样式（Heading1/Normal）生成对应元素
- [x] ExcelPartitioner 按 Sheet+行范围生成 TABLE，sourceLocation 格式为 file#sheet:rowStart-rowEnd
- [x] StructureAwareChunkService 不跨一级标题合并
- [x] StructureAwareChunkService TABLE 和 CODE_BLOCK 单独成块
- [x] StructureAwareChunkService 超长段落按句子边界切分
- [x] 120KB Markdown 文档尾部验收条件可被语义检索召回
- [x] 100KB 截断逻辑已取消，大文档分级 chunk size（>50KB 用 2500，>20KB 用 1800）

## 需求结构化抽取与图谱链接
- [x] NodeType 新增 Requirement/RequirementItem/AcceptanceCriterion/Constraint/Assumption/OpenQuestion
- [x] EdgeType 新增 HAS_ITEM/HAS_ACCEPTANCE_CRITERION/HAS_CONSTRAINT/HAS_ASSUMPTION/RAISES_QUESTION/AFFECTS/SATISFIES/DERIVED_FROM/VERIFIES
- [x] RequirementExtractionService prompt 约束不补造信息，缺失写入 openQuestions
- [x] requirement-analysis.txt 包含 3 条 few-shot（完整匹配/无按钮/未匹配场景）
- [x] RequirementGraphBuilder 正确构建 Requirement→RequirementItem→AcceptanceCriterion 节点和边
- [x] RequirementController POST /analyze 接口可用
- [x] RequirementLinkingService 确定性优先：显式引用→术语映射→向量相似度>0.80
- [x] 精确匹配标记 CONFIRMED，语义匹配标记 PENDING_CONFIRM
- [x] 向量相似度低于 0.80 不创建 AFFECTS 边
- [x] ImpactSubgraphService 从链接节点 BFS 提取影响路径
- [x] RequirementController POST /impact 接口可用

## RRF 混合检索
- [x] ReciprocalRankFusionService 实现加权 RRF（K=60）
- [x] VectorDocument 新增 graphReleaseId/aclPrincipals/documentStatus 字段
- [x] VectorRetrievalService SQL 增加 GraphRelease 和 ACL 过滤
- [x] HybridRetrievalService 在 rrf-enabled=true 时使用 RRF 融合
- [x] HybridRetrievalService 在 rrf-enabled=false 时保留 LinkedHashMap 去重
- [x] 同一文档同时被多路召回时 RRF 融合后排名更高
- [x] 跨版本（r1→r2）的向量文档不进入 r2 查询结果

## 证据验证与动态置信度
- [x] EvidenceVerifier 校验证据存在性、归属、ACL 可访问性、sourceLocation 非空、答案声明匹配
- [x] ConfidenceScorer 权重：coverage 0.30 + reliability 0.25 + retrievalAgreement 0.20 + pathConfidence 0.15 + freshness 0.10
- [x] 高风险意图权重：pathConfidence 0.35 + coverage 0.20
- [x] EnhancedQaAgent 删除固定 confidence=1.0/0.8
- [x] EnhancedQaAgent 生成后先验证→计算置信度→保存消息和缓存
- [x] 置信度<0.6 标记 LOW_CONFIDENCE，前端显示警告
- [x] CHANGE_IMPACT 意图置信度<0.5 返回拒答消息
- [x] SemanticCache 新增 graphReleaseId 和 aclHash 维度
- [x] 缓存命中返回完整证据 JSON
- [x] 新版本（r1→r2）不命中旧缓存
- [x] 不同 ACL 用户不互相命中
- [x] EnhancedQaController 从请求上下文解析 AccessContext

## Solution Package 生成与验证
- [x] NodeType 新增 Solution/SolutionStep
- [x] EdgeType 新增 STEP_OF/VALIDATED_BY/REVISED_BY
- [x] SolutionPlanner 生成文件级步骤（含文件路径+符号+证据 ID）
- [x] solution-planning.txt 约束遵循分层规范、优先复用组件
- [x] SolutionVerifier 校验：文件存在、符号存在、高风险覆盖、测试覆盖、证据有效、阻塞问题
- [x] 方案引用的 symbolKey 不存在时返回 SYMBOL_NOT_FOUND 且状态为 NEEDS_INPUT
- [x] 全部校验通过时状态为 READY_FOR_REVIEW
- [x] SolutionController POST /generate、GET /{id}、POST /{id}/verify 接口可用

## QA 评测门禁
- [x] QaEvaluationService 实现 entityRecall/evidencePrecision/requiredKeywordCoverage/abstentionAccuracy 指标
- [x] 黄金集测试用例 ≥ 30 条，覆盖 7 种 QueryIntent
- [x] ScanFinalizationService 在发布前调用 runSmoke
- [x] smoke 评测任一指标低于阈值时阻止发布（recall<0.85/precision<0.90/abstain<0.95）
- [x] 评测报告写入 docs/legacygraph/qa-evaluation-{versionId}.json

## 前端需求分析与方案评审
- [x] RequirementAnalysis.vue 展示结构化条目、验收条件、约束、待确认问题、受影响节点
- [x] openQuestions 非空时禁用"生成方案"按钮
- [x] SolutionReview.vue 展示方案步骤、测试、回滚、证据卡片、校验错误
- [x] 证据卡片点击展示详情（来源文件、行号、nodeKey）
- [x] 路由 /projects/:projectId/requirements 和 /projects/:projectId/solutions/:solutionId 可访问
- [x] AppLayout.vue 侧边栏新增"需求分析"和"方案评审"菜单项

## 集成验证
- [x] 扫描链路集成测试通过（前半段）：输入资料→适配器选择→抽取→Fact 落库→图谱构建（FullPipelineIntegrationTest）
- [x] 需求→方案→验证链路集成测试通过（RequirementToSolutionIntegrationTest）
- [ ] 全链路集成测试（完整）：扫描→GraphRelease 发布→需求分析→方案生成→验证→QA 问答（需完整中间件环境，暂未实现）
- [x] GraphRelease 关闭时全流程走旧路径，无破坏
- [x] RRF 关闭时检索走 LinkedHashMap 去重，无破坏
- [x] GraphQualityGate 门禁规则单元测试通过
- [x] RRF 融合算法单元测试通过
- [x] EvidenceVerifier 验证逻辑单元测试通过
- [x] ConfidenceScorer 评分逻辑单元测试通过
- [x] RequirementLinkingService 链接策略单元测试通过
- [x] RRF 融合延迟 < 50ms
- [x] 结构化解析 120KB 文档 < 5s
