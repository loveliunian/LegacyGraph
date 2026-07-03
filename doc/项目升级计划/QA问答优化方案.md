# QA 问答系统优化方案与实施复核

> 文档版本：v2.0 合并版  
> 合并日期：2026-07-03  
> 合并来源：`doc/QA问答优化方案.md`、`doc/项目升级计划/QA问答优化方案.md`  
> 适用范围：LegacyGraph 知识图谱问答模块

---

## 一、合并结论

QA 模块的目标不是再新增一套孤立问答能力，而是把基础 RAG、GraphRAG、多轮上下文、证据链和反馈闭环整合为一条可追踪、可验证、可持续优化的主流程：

```text
用户提问
  -> 多轮对话上下文
  -> 语义缓存检查
  -> 意图分类
  -> 查询改写 + HyDE
  -> 混合召回(向量 + 关键词 + 图谱)
  -> Re-ranking
  -> 复杂意图 GraphRAG Planner + Executor
  -> 证据链接化
  -> LLM 流式生成
  -> 对话历史与用户反馈闭环
```

当前代码已经完成从“同步基础 RAG”到“增强型流式 QA”的第一轮闭环，但仍需要继续收口评测集、强重排、GraphRAG Claim 相关性筛选、负反馈自动分析和上下文摘要压缩。

---

## 二、现状与目标

### 2.1 原始问题

原 QA 系统采用基础 RAG：

```text
用户提问 -> 向量召回(pgvector) -> 图谱一跳扩展 -> 上下文拼装 -> LLM 生成
```

主要问题：

| 维度 | 原问题 | 影响 |
|------|--------|------|
| 召回质量 | 单一向量检索，无语义增强 | 召回不精准，容易遗漏关键信息 |
| 查询理解 | 无意图分类、无查询改写 | 复杂问题处理失败 |
| 上下文管理 | 无多轮对话历史 | 无法理解追问和指代 |
| 响应体验 | 同步阻塞，无流式输出 | 用户等待时间长 |
| 证据展示 | 证据不可跳转 | 用户难以验证答案来源 |
| 质量闭环 | 无反馈机制和评估指标 | 无法持续优化 |
| 规划能力 | GraphRAG Planner 未接入主流程 | 多跳结构问题无法稳定处理 |

### 2.2 目标能力

| 阶段 | 内容 | 验收口径 |
|------|------|----------|
| P0 | 流式响应 + 多轮对话 + 证据链接 | 首 token 流式输出；刷新后历史可恢复；证据可点击 |
| P1 | 查询理解 + 多路召回 + Re-ranking | 意图分类、查询改写进入主流程；召回结果可重排 |
| P2 | GraphRAG Planner + HyDE | 复杂意图使用 Planner；HyDE 结果进入检索 |
| P3 | 反馈闭环 | 反馈记录包含消息、问题、答案、使用证据 |
| P4 | 语义缓存 + 性能优化 | 相似问题复用答案，缓存命中仍保留会话历史 |

---

## 三、目标架构

```text
用户交互层
  - SSE 流式响应
  - 会话列表与历史消息
  - 证据卡片与反馈按钮

查询理解层
  - QueryIntentClassifier
  - QueryRewriter
  - HyDEGenerator

检索增强层
  - HybridRetrievalService
  - VectorRetrievalService
  - Neo4j 图谱扩展
  - ReRankingService

生成与验证层
  - qa-answer-enhanced prompt
  - LlmGateway.callStream
  - EvidenceItem 引用映射

质量闭环层
  - QaConversation / QaMessage
  - QaFeedback
  - usedEvidenceIds
  - 后续质量分析与评测回放
```

---

## 四、核心设计

### 4.1 查询理解

查询理解层负责把自然语言问题路由到合适检索策略。

当前意图类型：

| 类型 | 场景 | 图谱深度 | 是否使用 Planner |
|------|------|----------|------------------|
| `FACT_LOOKUP` | 查具体事实，如方法、接口、表结构 | 1 | 否 |
| `STRUCTURAL` | 查系统结构、模块关系 | 2 | 是 |
| `RELATIONAL` | 查调用关系、依赖关系、数据流 | 3 | 是 |
| `COMPARATIVE` | 对比多个实体或版本 | 2 | 否 |
| `TEMPORAL` | 时间、版本变更问题 | 1 | 否 |
| `EXPLANATION` | 设计原因、业务解释 | 2 | 否 |

设计原则：

- 简单事实查询不能无条件触发 GraphRAG Planner，避免延迟和噪声。
- 复杂结构/关系问题必须进入 Planner，并携带项目 Claim 作为规划上下文。
- 查询改写和 HyDE 都应进入实际召回，而不是只停留在类定义。

### 4.2 混合召回

当前混合召回由 `HybridRetrievalService` 承担：

```text
原始问题
  + QueryRewriter 生成的查询变体
  + HyDE 生成的假设答案文档
  -> 向量召回
  -> 关键词补充召回
  -> 合并去重
  -> ReRankingService
```

注意：

- 当前关键词召回是 PostgreSQL `LIKE` 风格的轻量实现，不是 FTS。
- 当前 Re-ranking 是关键词/TF 风格轻量排序，不是 cross-encoder。
- 这满足第一阶段接入闭环，但不是最终效果目标。

### 4.3 GraphRAG Planner

复杂问题路径：

```text
STRUCTURAL / RELATIONAL 问题
  -> KnowledgeClaimService.listClaims(projectId, versionId, ..., 50)
  -> GraphRagPlannerAgent.plan(projectId, question, claims)
  -> GraphRagPlanExecutor.execute(...)
  -> GraphRAG EvidenceCard
  -> 拼入 LLM 上下文和证据列表
```

本轮已修正 Planner 传空 Claim 的偏移。后续还需要把 Claim 从“按版本 Top 50”升级为“按 query 相关性筛选”。

### 4.4 多轮对话

数据模型：

| 表 | 用途 |
|----|------|
| `lg_qa_conversation` | 会话列表、标题、更新时间 |
| `lg_qa_message` | 用户/助手消息、证据、置信度 |
| `lg_qa_feedback` | 用户反馈、问题、答案、使用证据 |
| `lg_semantic_cache` | 问题 embedding、答案、证据缓存 |

多轮要求：

- 新问题没有 `conversationId` 时由后端创建会话。
- 前端必须在 stream complete 后回写后端返回的 `conversationId`。
- 缓存命中不能绕过消息保存，否则会导致追问上下文断裂。
- 历史消息要兼容后端角色 `USER/ASSISTANT` 与前端角色 `user/assistant`。

### 4.5 流式响应协议

后端 `/qa/ask/stream` 使用 POST + SSE：

| event | data |
|-------|------|
| `thinking` | 当前阶段，如 `cache_check`、`retrieving`、`generating` |
| `evidence` | `{ items: EvidenceItem[] }` |
| `token` | `{ text: string }` |
| `complete` | `conversationId`、`messageId`、`confidence`、`evidences` |
| `error` | 错误消息 |

前端使用 `fetch + ReadableStream` 解析 POST SSE。原生 `EventSource` 只支持 GET，不作为主实现。

### 4.6 证据链接

`EvidenceItem` 应包含：

| 字段 | 说明 |
|------|------|
| `sourceKind` | `GRAPH_NODE` / `DOC_CHUNK` / `KNOWLEDGE_CLAIM` |
| `ref` | 节点或文档片段引用 |
| `title` | 展示标题 |
| `excerpt` | 摘要 |
| `jumpUrl` | 跳转图谱或文档 |
| `nodeType` | 节点类型 |
| `sourceFile` | 来源文件 |
| `relevanceScore` | 相关性分数 |
| `retrievalMethod` | `VECTOR` / `KEYWORD` / `GRAPH_TRAVERSAL` |

### 4.7 反馈闭环

反馈必须保存：

- `messageId`
- `conversationId`
- `projectId`
- `helpful`
- `feedbackText`
- `usedEvidenceIds`
- `question`
- `answer`

`usedEvidenceIds` 是后续分析“哪些证据真正支撑答案”的关键字段，不能只保存有用/无用。

---

## 五、本轮代码核对与修复结果

### 5.1 已发现并修复的偏移

| 编号 | 偏移/遗漏 | 影响 | 处理结果 |
|------|-----------|------|----------|
| 1 | `HyDEGenerator` 存在但未注入 `EnhancedQaAgent` 主流程 | HyDE 只是孤岛实现，P2 实际未生效 | 已修复 |
| 2 | GraphRAG Planner 对所有意图都可能运行 | 简单事实查询额外触发规划，增加延迟和噪声 | 已修复为仅 `STRUCTURAL/RELATIONAL` |
| 3 | Planner 调用传入空 `KnowledgeClaim` 列表 | 复杂问题规划缺少项目知识断言输入 | 已接入 `KnowledgeClaimService.listClaims(...)` |
| 4 | 语义缓存命中时直接返回，不创建/保存会话消息 | 多轮上下文断裂，刷新后历史丢失 | 已修复 |
| 5 | 流式完成事件返回随机 `messageId` | 用户反馈无法关联真实 `lg_qa_message` | 已修复为返回保存后的消息 ID |
| 6 | 前端新会话发送后未回写后端 `conversationId` | 后续追问不会进入同一会话 | 已修复 |
| 7 | 历史消息前端读取 `evidencesJson`，后端实际字段为 `evidences` | 刷新后证据丢失 | 已新增兼容映射 |
| 8 | 后端角色为 `USER/ASSISTANT`，前端按小写判断 | 历史消息样式和反馈按钮异常 | 已新增角色归一化 |
| 9 | SSE `evidence` 事件前端未派发 | 生成完成前证据无法进入页面状态 | 已修复 |
| 10 | 会话列表缺少 `messageCount` | 侧边栏消息数量显示不准确 | 已补非持久字段和统计 |
| 11 | 反馈未保存 `usedEvidenceIds` | 质量闭环无法分析证据有效性 | 已修复 |

### 5.2 已修改代码

后端：

- `backend/src/main/java/io/github/legacygraph/agent/EnhancedQaAgent.java`
  - 注入 `HyDEGenerator` 与 `KnowledgeClaimService`
  - HyDE 结果加入混合检索 query variants
  - GraphRAG Planner 限定复杂意图触发
  - Planner 传入当前项目/版本的 `KnowledgeClaim`
  - 缓存命中也保存用户/助手消息并返回真实 `messageId`
- `backend/src/main/java/io/github/legacygraph/controller/EnhancedQaController.java`
  - 反馈请求支持 `usedEvidenceIds`
- `backend/src/main/java/io/github/legacygraph/entity/QaConversation.java`
  - 增加 `messageCount` 非持久字段
- `backend/src/main/java/io/github/legacygraph/service/ConversationContextManager.java`
  - 会话列表填充消息数量

前端：

- `frontend/src/api/qa.api.ts`
  - 补齐 QA 流式、多轮、证据、反馈类型
  - SSE parser 支持 `evidence` 事件
- `frontend/src/views/graph/GraphQa.vue`
  - complete 后回写 `conversationId`
  - 反馈提交带 `usedEvidenceIds`
  - 历史消息使用统一映射
- `frontend/src/views/graph/qaMessageMapper.ts`
  - 归一化后端角色
  - 兼容 `evidences/evidencesJson`

### 5.3 已补测试

| 测试文件 | 覆盖点 |
|----------|--------|
| `EnhancedQaAgentTest` | HyDE 进入检索；事实查询不触发 Planner；复杂意图携带 Claims；缓存命中保留历史并返回真实消息 ID |
| `ConversationContextManagerTest` | 会话列表填充 `messageCount` |
| `EnhancedQaControllerTest` | 反馈保存 `usedEvidenceIds` |
| `qa.api.test.ts` | 前端 SSE `evidence/token/complete` 解析 |
| `qaMessageMapper.test.ts` | 历史角色归一化与证据字段兼容 |
| `GraphQa.test.ts` | 页面结构在流式/会话布局后仍可渲染 |

---

## 六、当前实现状态

| 能力 | 状态 | 说明 |
|------|------|------|
| 后端 SSE 流式输出 | 已接入 | `/qa/ask/stream` 使用 `SseEmitter` |
| 前端流式接收 | 已接入 | 使用 fetch + ReadableStream 解析 POST SSE |
| 多轮对话持久化 | 已接入 | `lg_qa_conversation`、`lg_qa_message` |
| 证据链接化 | 已接入 | `EvidenceItem.jumpUrl` 已生成并展示 |
| 反馈收集 | 已接入 | 保存 message、conversation、question、answer、usedEvidenceIds |
| 意图分类 | 已接入 | `QueryIntentClassifier` |
| 查询改写 | 已接入 | `QueryRewriter` |
| HyDE | 已接入主流程 | 生成结果进入混合检索 |
| 混合召回 | 已接入 | 向量 + 查询变体 + LIKE 关键词检索 |
| Re-ranking | 已接入轻量版 | 当前是关键词/TF 风格排序，不是 cross-encoder |
| GraphRAG Planner | 已接入 | 仅复杂意图触发，携带 Claims |
| 语义缓存 | 已接入 | 命中后仍写会话历史 |

---

## 七、分阶段实施计划

### P0：基础可用

目标：让用户能稳定提问、看到流式答案、继续追问、验证证据来源。

任务：

- 后端 `/qa/ask/stream`
- `LlmGateway.callStream`
- `ConversationContextManager`
- 对话表与消息表迁移
- 前端 POST SSE 解析
- 会话列表、历史加载、证据跳转

当前状态：第一轮已接入并补测试。

### P1：质量提升

目标：提高召回和回答质量。

任务：

- 意图分类
- 查询改写
- 混合召回
- Re-ranking
- 基础评测集

当前状态：分类、改写、混合召回、轻量 Re-ranking 已接入；评测集未建立。

### P2：智能增强

目标：支持复杂结构和关系问题。

任务：

- HyDE 进入检索主流程
- GraphRAG Planner 仅对复杂意图触发
- GraphRAG Executor 证据卡拼入上下文
- Claim 相关性筛选

当前状态：HyDE 和 Planner 已接入；Claim 相关性筛选仍待实现。

### P3：闭环优化

目标：把用户反馈变成可分析数据。

任务：

- 反馈记录
- `usedEvidenceIds`
- 负反馈自动质量分析
- 评测回放任务

当前状态：反馈记录和 `usedEvidenceIds` 已接入；自动分析与回放未实现。

### P4：性能优化

目标：降低延迟并控制上下文成本。

任务：

- 语义缓存
- 并行检索
- 上下文摘要压缩
- 受控线程池/TaskExecutor
- 真实环境延迟监控

当前状态：语义缓存已接入；摘要压缩、受控线程池、真实压测未完成。

---

## 八、仍未完成/需后续收口

| 优先级 | 未完成项 | 原因/下一步 |
|--------|----------|-------------|
| P1 | ~~召回准确率、回答准确率评测集尚未建立~~ | ✅ 已建立评测集框架 `qa_test_set.json` + `QaEvaluationService` + `QaTestCase`/`QaEvaluationResult`，支持关键词覆盖率和响应时间评分 |
| P1 | ~~Re-ranking 仍是轻量关键词实现~~ | ✅ 已重构为可插拔架构：`DocumentReranker` 接口 + `KeywordReranker`（默认）+ `CrossEncoderReranker`（配置 `qa.reranker.type=cross-encoder` 启用） |
| P2 | ~~GraphRAG Planner 的 Claims 目前按版本取 Top 50，未做 query 相关性过滤~~ | ✅ 已在 `EnhancedQaAgent.loadRelevantClaims()` 实现：取 200 条后按 query 关键词对 subjectKey/objectKey/objectValue/predicate 打分排序，取 Top 50 |
| P3 | ~~负反馈后的自动质量分析未实现~~ | ✅ 已创建 `QaQualityAnalyzer`：自动分类问题类型（无答案/答案错误/不完整/答非所问/缺少证据），定时任务每周一凌晨 2 点生成报告 |
| P3 | ~~自动评估指标与持续学习未实现~~ | ✅ 已创建 `QaAutoEvaluationService`：自动统计回答率、平均回答长度、问题类别分布；支持历史问题回放评估；定时任务每周一凌晨 3 点执行 |
| P4 | ~~上下文窗口未做摘要压缩~~ | ✅ 已在 `ConversationContextManager.buildContextText()` 实现：早期消息（超过阈值）压缩为摘要（每条截取前 100 字符，总摘要限 500 字符），最近消息保留原文 |
| P4 | ~~后端 stream 使用裸线程~~ | ✅ 已替换为受控 `TaskExecutor`（虚拟线程），`@Primary` 标记为默认执行器 |
| P4 | ~~端到端响应延迟未实测~~ | ✅ 已在 `EnhancedQaAgent.answerStream()` 中加入各阶段计时（init/cache_check/intent_classify/query_rewrite/retrieval/rerank/llm_generate），complete 事件返回 `latencyMs` 和 `stageTimings` |

---

## 九、验证记录

已执行并通过：

```bash
rtk test mvn -Dtest=EnhancedQaAgentTest,ConversationContextManagerTest,EnhancedQaControllerTest test
rtk test mvn test
rtk test mvn test-compile
rtk test npm run type-check
rtk test npm run test -- --run src/api/__tests__/qa.api.test.ts src/views/graph/__tests__/qaMessageMapper.test.ts tests/unit/views/GraphQa.test.ts
```

已知未收口：

- 前端全量 `npm run test -- --run` 仍存在非 QA 页面/测试环境失败，例如 `RiskList.test.ts` 的 `@/utils/request` mock 缺少 `post`、部分页面缺少 i18n/Element Plus stub。
- 这些失败不在本轮 QA 主链路修复范围内，但后续做前端全量测试治理时需要统一处理。

---

## 十、后续建议

下一轮优先处理：

1. 建立 QA 人工评测集，至少覆盖事实查询、结构查询、关系查询、追问四类样本。
2. 为 `KnowledgeClaim` 增加 query 相关性筛选，避免 Planner 输入过宽。
3. 将 `ReRankingService` 替换或增强为可插拔 cross-encoder/reranker。
4. 把负反馈接入质量分析任务，生成可复盘的失败样本。
5. 用 `TaskExecutor` 替换 stream 裸线程，并记录首 token 延迟、总耗时、召回数量。
