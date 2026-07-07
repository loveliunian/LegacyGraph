# EnhancedQaAgent 使用指南

## 概述

`EnhancedQaAgent` 是 LegacyGraph 的核心问答智能体，支持：
- **流式输出**（SSE）
- **多轮对话**（自动维护上下文）
- **意图分类**（自动识别问题类型）
- **语义缓存**（相似问题直接返回缓存答案）
- **GraphRAG**（复杂问题自动规划图谱检索）
- **变更影响分析**（自动识别"修改表结构"类问题）

---

## 调用方式

### 1. 通过 Controller 调用（推荐）

**接口**: `POST /qa/ask/stream`

**请求体**:
```json
{
  "projectId": "your-project-id",      // 必填
  "versionId": "your-version-id",      // 可选
  "question": "这个系统有哪些微服务？",   // 必填
  "conversationId": "xxx-xxx-xxx"      // 可选，首次问答传 null
}
```

**响应**: Server-Sent Events (SSE) 流式输出

**curl 示例**:
```bash
curl -X POST http://localhost:8080/qa/ask/stream \
  -H "Content-Type: application/json" \
  -d '{
    "projectId": "my-project",
    "versionId": "v1.0",
    "question": "用户模块的表结构是什么？",
    "conversationId": null
  }' \
  --no-buffer
```

---

### 2. 直接调用 Agent（测试/集成）

```java
@Autowired
private EnhancedQaAgent enhancedQaAgent;

SseEmitter emitter = new SseEmitter(120_000L);

enhancedQaAgent.answerStream(
    "project-123",        // projectId
    "version-456",        // versionId
    "这个系统的架构是怎样的？",  // question
    null,                 // conversationId（首次为 null）
    emitter               // SSE 发射器
);
```

---

## SSE 事件流格式

客户端会依次收到以下事件：

| 事件名 | 触发时机 | 数据结构 |
|--------|----------|----------|
| `thinking` | 各阶段开始时 | `{"stage": "cache_check\|understanding\|classifying\|rewriting\|retrieving\|expanding_graph\|generating\|..."}` |
| `token` | LLM 生成每个 token | `{"text": "token内容"}` |
| `evidence` | 检索完成后 | `{"items": [EvidenceItem, ...]}` |
| `impact` | 变更影响分析完成 | `{"changeKind": "ADD_COLUMN", "tableName": "xxx", "severity": "HIGH", ...}` |
| `complete` | 生成完毕 | `{"conversationId": "xxx", "messageId": "yyy", "confidence": 0.8, "evidences": [...], "answer": "完整答案", "latencyMs": 1234, "stageTimings": {...}, "fromCache": true/false}` |
| `error` | 发生错误 | `{"message": "错误信息"}` |

### 前端处理示例（JavaScript）

```javascript
const eventSource = new EventSource('/qa/ask/stream', {
  // 注意：EventSource 只支持 GET，实际需要用 fetch + ReadableStream
});

// 推荐使用 fetch + SSE 解析
fetch('/qa/ask/stream', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ projectId, versionId, question, conversationId })
}).then(response => {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    const text = decoder.decode(value);
    // 解析 SSE 事件
    const events = parseSSE(text);

    for (const event of events) {
      switch (event.event) {
        case 'thinking':
          console.log('阶段:', event.data.stage);
          break;
        case 'token':
          displayAnswer(event.data.text);  // 逐字显示
          break;
        case 'evidence':
          showEvidence(event.data.items);
          break;
        case 'complete':
          saveConversationId(event.data.conversationId);
          break;
        case 'error':
          showError(event.data.message);
          break;
      }
    }
  }
});
```

---

## 完整处理流程

```
用户提问
  ↓
[0] 初始化对话（getOrCreateConversation）
  ↓
[1] 语义缓存检查 → 命中则直接返回缓存答案（fromCache=true）
  ↓
[2] 意图分类（QueryIntentClassifier）
  ↓
[3] 变更影响分析（如果是 CHANGE_IMPACT 类问题）
  - 解析变更类型（ADD_COLUMN/DROP_TABLE/...）
  - 查找受影响节点
  - 调用 ChangeImpactAgent 分析
  ↓
[4] 查询改写（QueryRewriter + HyDE）
  ↓
[5] GraphRAG 规划（如果是复杂问题）
  - 加载相关 KnowledgeClaim
  - 生成检索计划
  - 执行 GraphRAG 查询
  ↓
[6] 多路召回（HybridRetrievalService）
  ↓
[7] Re-ranking（ReRankingService）
  ↓
[8] 图谱扩展（expandGraph）
  ↓
[9] 构建上下文 + 证据列表
  ↓
[10] LLM 流式生成答案
  ↓
[11] 保存助手消息 + 更新语义缓存
  ↓
[12] 返回 complete 事件
```

---

## 多轮对话使用

### 首次问答
```json
{
  "projectId": "proj-1",
  "question": "用户表有哪些字段？",
  "conversationId": null
}
```

**响应** `complete` 事件返回 `conversationId: "abc-123"`

### 后续追问
```json
{
  "projectId": "proj-1",
  "question": "这个表的主键是什么？",
  "conversationId": "abc-123"  // 使用上次返回的 ID
}
```

Agent 会自动加载历史对话上下文（最近 10 条消息），实现连贯的多轮问答。

---

## 对话管理接口

### 列出对话
```bash
GET /qa/conversations?projectId=proj-1
```

### 获取消息历史
```bash
GET /qa/conversations/{conversationId}/messages
```

### 删除对话
```bash
DELETE /qa/conversations/{conversationId}
```

---

## 配置参数

| 参数 | 位置 | 默认值 | 说明 |
|------|------|--------|------|
| `MAX_CONTEXT_MESSAGES` | ConversationContextManager | 10 | 对话上下文最大消息数 |
| `RECENT_THRESHOLD` | ConversationContextManager | 4 | 早期消息压缩为摘要的阈值 |
| SSE 超时 | EnhancedQaController | 120s | 流式连接超时时间 |

---

## 常见问题

### Q: 为什么返回 `projectId 不能为空`？
A: 请求体中的 `projectId` 字段为 null 或空字符串。这是必填字段。

### Q: 为什么第二次问答没有上下文？
A: 检查是否将第一次返回的 `conversationId` 正确传递到第二次请求中。

### Q: 语义缓存什么时候生效？
A: 当新问题与历史问题的语义相似度超过阈值时，会直接返回缓存答案（`fromCache: true`），跳过 LLM 调用。

### Q: GraphRAG 什么时候触发？
A: 当意图分类器判定问题为"复杂类型"（如跨模块查询、架构分析）时，会自动触发 GraphRAG 规划器。

### Q: 变更影响分析支持哪些变更类型？
A: 目前支持：`ADD_COLUMN`、`DROP_COLUMN`、`MODIFY_COLUMN`、`DROP_TABLE`、`RENAME_TABLE` 等。

---

## 性能指标

典型端到端延迟（参考值）：
- 缓存命中：< 100ms
- 简单问题（无 GraphRAG）：1-3s
- 复杂问题（含 GraphRAG）：5-10s
- 变更影响分析：3-8s

各阶段耗时会在 `complete` 事件的 `stageTimings` 字段中返回。

---

## 依赖组件

| 组件 | 作用 |
|------|------|
| `ConversationContextManager` | 对话管理（创建/保存/检索） |
| `QueryIntentClassifier` | 意图分类 |
| `QueryRewriter` | 查询改写 |
| `HyDEGenerator` | 假设文档生成 |
| `HybridRetrievalService` | 多路召回（向量+关键词） |
| `ReRankingService` | 重排序 |
| `VectorRetrievalService` | 向量检索 |
| `Neo4jGraphDao` | 图谱查询 |
| `LlmGateway` | LLM 调用（支持流式） |
| `GraphRagPlannerAgent` | GraphRAG 规划 |
| `GraphRagPlanExecutor` | GraphRAG 执行 |
| `SemanticCache` | 语义缓存 |
| `ImpactSubgraphService` | 影响子图提取 |
| `ChangeImpactAgent` | 变更影响分析 |
| `ChangeImpactQuestionParser` | 变更请求解析 |

---

## 测试

单元测试位置：
```
backend/src/test/java/io/github/legacygraph/agent/EnhancedQaAgentTest.java
```

运行测试：
```bash
cd backend
mvn test -Dtest=EnhancedQaAgentTest
```

---

## 更新日志

- 2026-07-07: 修复 `projectId` 为空导致的插入失败问题，增加 Controller 层参数校验
