# Checklist

## GraphRelease 发布门禁
- [ ] GraphRelease 实体包含 id/projectId/scanVersionId/graphVersionTag/status/createdAt/publishedAt/failureReasons 字段
- [ ] 状态机 DRAFT→VALIDATING→PUBLISHED|FAILED 实现正确
- [ ] 同 project+version 重复调用 startValidation 返回已有记录（幂等）
- [ ] GraphQualityGate 评估规则覆盖：边/节点比≥1.0、孤立率≤10%、约束违反=0、证据率≥95%
- [ ] 门禁不通过时 GraphRelease 状态为 FAILED 且记录失败原因
- [ ] 门禁不通过时 QA 返回"该版本未通过质量门禁"
- [ ] ScanFinalizationService 编排顺序正确：约定提取→可复用标记→质量评估→边补全→社区检测→产物发布→门禁→发布→缓存失效
- [ ] ScanArtifactPublisher 拆分出 publishArtifactsOnly 方法
- [ ] `legacygraph.graph-release.enabled=false` 时全流程走旧路径无破坏
- [ ] GraphRelease 发布后调用 SemanticCache.invalidateByProject

## 文档结构化解析
- [ ] DocumentElement 实体包含 type/text/headingPath/bbox/sourceLocation 字段
- [ ] MarkdownPartitioner 按 #/##/### 标题层级生成 TITLE 并维护 headingPath
- [ ] MarkdownPartitioner 代码块生成 CODE_BLOCK、表格生成 TABLE
- [ ] WordPartitioner 按段落样式（Heading1/Normal）生成对应元素
- [ ] ExcelPartitioner 按 Sheet+行范围生成 TABLE，sourceLocation 格式为 file#sheet:rowStart-rowEnd
- [ ] StructureAwareChunkService 不跨一级标题合并
- [ ] StructureAwareChunkService TABLE 和 CODE_BLOCK 单独成块
- [ ] StructureAwareChunkService 超长段落按句子边界切分
- [ ] 120KB Markdown 文档尾部验收条件可被语义检索召回
- [ ] 100KB 截断逻辑已取消，大文档分级 chunk size（>50KB 用 2500，>20KB 用 1800）

## 需求结构化抽取与图谱链接
- [ ] NodeType 新增 Requirement/RequirementItem/AcceptanceCriterion/Constraint/Assumption/OpenQuestion
- [ ] EdgeType 新增 HAS_ITEM/HAS_ACCEPTANCE_CRITERION/HAS_CONSTRAINT/HAS_ASSUMPTION/RAISES_QUESTION/AFFECTS/SATISFIES/DERIVED_FROM/VERIFIES
- [ ] RequirementExtractionService prompt 约束不补造信息，缺失写入 openQuestions
- [ ] requirement-analysis.txt 包含 3 条 few-shot（完整匹配/无按钮/未匹配场景）
- [ ] RequirementGraphBuilder 正确构建 Requirement→RequirementItem→AcceptanceCriterion 节点和边
- [ ] RequirementController POST /analyze 接口可用
- [ ] RequirementLinkingService 确定性优先：显式引用→术语映射→向量相似度>0.80
- [ ] 精确匹配标记 CONFIRMED，语义匹配标记 PENDING_CONFIRM
- [ ] 向量相似度低于 0.80 不创建 AFFECTS 边
- [ ] ImpactSubgraphService 从链接节点 BFS 提取影响路径
- [ ] RequirementController POST /impact 接口可用

## RRF 混合检索
- [ ] ReciprocalRankFusionService 实现加权 RRF（K=60）
- [ ] VectorDocument 新增 graphReleaseId/aclPrincipals/documentStatus 字段
- [ ] VectorRetrievalService SQL 增加 GraphRelease 和 ACL 过滤
- [ ] HybridRetrievalService 在 rrf-enabled=true 时使用 RRF 融合
- [ ] HybridRetrievalService 在 rrf-enabled=false 时保留 LinkedHashMap 去重
- [ ] 同一文档同时被多路召回时 RRF 融合后排名更高
- [ ] 跨版本（r1→r2）的向量文档不进入 r2 查询结果

## 证据验证与动态置信度
- [ ] EvidenceVerifier 校验证据存在性、归属、ACL 可访问性、sourceLocation 非空、答案声明匹配
- [ ] ConfidenceScorer 权重：coverage 0.30 + reliability 0.25 + retrievalAgreement 0.20 + pathConfidence 0.15 + freshness 0.10
- [ ] 高风险意图权重：pathConfidence 0.35 + coverage 0.20
- [ ] EnhancedQaAgent 删除固定 confidence=1.0/0.8
- [ ] EnhancedQaAgent 生成后先验证→计算置信度→保存消息和缓存
- [ ] 置信度<0.6 标记 LOW_CONFIDENCE，前端显示警告
- [ ] CHANGE_IMPACT 意图置信度<0.5 返回拒答消息
- [ ] SemanticCache 新增 graphReleaseId 和 aclHash 维度
- [ ] 缓存命中返回完整证据 JSON
- [ ] 新版本（r1→r2）不命中旧缓存
- [ ] 不同 ACL 用户不互相命中
- [ ] EnhancedQaController 从请求上下文解析 AccessContext

## Solution Package 生成与验证
- [ ] NodeType 新增 Solution/SolutionStep
- [ ] EdgeType 新增 STEP_OF/VALIDATED_BY/REVISED_BY
- [ ] SolutionPlanner 生成文件级步骤（含文件路径+符号+证据 ID）
- [ ] solution-planning.txt 约束遵循分层规范、优先复用组件
- [ ] SolutionVerifier 校验：文件存在、符号存在、高风险覆盖、测试覆盖、证据有效、阻塞问题
- [ ] 方案引用的 symbolKey 不存在时返回 SYMBOL_NOT_FOUND 且状态为 NEEDS_INPUT
- [ ] 全部校验通过时状态为 READY_FOR_REVIEW
- [ ] SolutionController POST /generate、GET /{id}、POST /{id}/verify 接口可用

## QA 评测门禁
- [ ] QaEvaluationService 实现 entityRecall/evidencePrecision/requiredKeywordCoverage/abstentionAccuracy 指标
- [ ] 黄金集测试用例 ≥ 30 条，覆盖 7 种 QueryIntent
- [ ] ScanFinalizationService 在发布前调用 runSmoke
- [ ] smoke 评测任一指标低于阈值时阻止发布（recall<0.85/precision<0.90/abstain<0.95）
- [ ] 评测报告写入 docs/legacygraph/qa-evaluation-{versionId}.json

## 前端需求分析与方案评审
- [ ] RequirementAnalysis.vue 展示结构化条目、验收条件、约束、待确认问题、受影响节点
- [ ] openQuestions 非空时禁用"生成方案"按钮
- [ ] SolutionReview.vue 展示方案步骤、测试、回滚、证据卡片、校验错误
- [ ] 证据卡片点击展示详情（来源文件、行号、nodeKey）
- [ ] 路由 /projects/:projectId/requirements 和 /projects/:projectId/solutions/:solutionId 可访问
- [ ] AppLayout.vue 侧边栏新增"需求分析"和"方案评审"菜单项

## 集成验证
- [ ] 全链路集成测试通过：上传需求→扫描→GraphRelease 发布→需求分析→方案生成→验证→QA 问答
- [ ] GraphRelease 关闭时全流程走旧路径，无破坏
- [ ] RRF 关闭时检索走 LinkedHashMap 去重，无破坏
- [ ] GraphQualityGate 门禁规则单元测试通过
- [ ] RRF 融合算法单元测试通过
- [ ] EvidenceVerifier 验证逻辑单元测试通过
- [ ] ConfidenceScorer 评分逻辑单元测试通过
- [ ] RequirementLinkingService 链接策略单元测试通过
- [ ] RRF 融合延迟 < 50ms
- [ ] 结构化解析 120KB 文档 < 5s
