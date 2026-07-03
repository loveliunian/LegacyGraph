# QA 问答系统优化方案

> 文档版本：v1.0  
> 创建日期：2026-07-03  
> 适用范围：LegacyGraph 知识图谱问答模块

---

## 一、现状分析

### 1.1 架构现状

当前 QA 系统采用基础 RAG 架构：

```
用户提问 → 向量召回(pgvector) → 图谱一跳扩展 → 上下文拼装 → LLM 生成
```

**核心组件：**
- `GraphQaController` - REST API 入口
- `QaAgent` - 问答主逻辑（向量检索 + 图谱扩展 + LLM 生成）
- `VectorRetrievalService` - 向量检索服务
- `GraphRagPlannerAgent` - 查询规划器（已实现但未集成）
- `GraphRagPlanExecutor` - 计划执行器（已实现但未集成）

### 1.2 问题诊断

| 维度 | 问题 | 影响 |
|------|------|------|
| **召回质量** | 单一向量检索，无语义增强 | 召回不精准，遗漏关键信息 |
| **查询理解** | 无意图分类、无查询改写 | 复杂问题处理失败 |
| **上下文管理** | 无多轮对话历史 | 无法理解追问、指代 |
| **响应体验** | 同步阻塞，无流式输出 | 用户等待时间长（5-15s） |
| **证据展示** | 纯文本列表，无跳转链接 | 用户无法验证答案来源 |
| **质量闭环** | 无反馈机制、无评估指标 | 无法持续优化 |
| **规划能力** | GraphRagPlannerAgent 未集成 | 复杂多跳问题无法处理 |

### 1.3 代码问题

```java
// QaAgent.java:42 - 相似节点阈值为 0，形同虚设
private static final double NODE_SIM_THRESHOLD = 0.0;

// QaAgent.java:57-62 - 召回策略过于简单
List<VectorDocument> docs = safeSemanticSearch(...);
List<GraphNode> nodes = safeFindSimilarNodes(...);
GraphNeighborhood neighborhood = safeExpandGraphNeighborhood(...);

// 无 Query 改写、无 Re-ranking、无意图分类
```

---

## 二、优化目标

### 2.1 核心指标

| 指标 | 当前 | 目标 | 衡量方式 |
|------|------|------|----------|
| 召回准确率 | ~40% | >75% | 人工评测集 Top-5 命中率 |
| 回答准确率 | ~50% | >80% | 用户反馈 + 自动评估 |
| 响应延迟 | 5-15s | <3s（首 token） | SSE 流式 |
| 多轮支持 | 无 | 支持 5 轮追问 | 上下文窗口管理 |
| 证据可追溯 | 弱 | 100% | 节点/文档跳转链接 |

### 2.2 用户体验

- ✅ 流式输出，逐字显示
- ✅ 证据可点击跳转到图谱/文档
- ✅ 支持追问和上下文理解
- ✅ 提供"有用/无用"反馈按钮
- ✅ 示例问题动态推荐

---

## 三、优化方案

### 3.1 架构升级

```
┌─────────────────────────────────────────────────────────────┐
│                      用户交互层                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  SSE 流式响应  │  │  多轮对话管理  │  │  反馈收集器   │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                      查询理解层（新增）                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  意图分类器   │  │  查询改写器   │  │  HyDE 生成器  │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                      检索增强层（升级）                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  多路召回     │  │  图谱多跳遍历  │  │  Re-ranking   │      │
│  │ (向量+关键词) │  │ (1-3 跳)     │  │ (交叉编码器)  │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                      生成与验证层（升级）                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  LLM 流式生成 │  │  事实一致性校验 │  │  引用映射     │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                      质量闭环层（新增）                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  用户反馈收集  │  │  自动评估指标  │  │  持续学习     │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 查询理解层（新增）

#### 3.2.1 意图分类器

将用户问题分类为不同查询类型，路由到最优检索策略：

```java
public enum QueryIntent {
    FACT_LOOKUP,      // 事实查询："OrderService 有哪些方法？"
    STRUCTURAL,       // 结构查询："订单创建涉及哪些表？"
    RELATIONAL,       // 关系查询："A 和 B 有什么依赖关系？"
    COMPARATIVE,      // 对比查询："V1 和 V2 版本的差异？"
    TEMPORAL,         // 时序查询："这个接口是什么时候加的？"
    EXPLANATION       // 解释查询："为什么这样设计？"
}

@Service
public class QueryIntentClassifier {
    private final LlmGateway llmGateway;
    
    public QueryIntent classify(String question, List<Message> history) {
        // 使用轻量级 LLM 调用，few-shot prompt
        String prompt = renderIntentPrompt(question, history);
        return llmGateway.callWithTemplate(
            projectId, "intent-classifier", 
            Map.of("question", question), 
            QueryIntent.class
        );
    }
}
```

**Prompt 模板 (`intent-classifier.txt`)：**

```
你是一个查询意图分类器。根据用户问题和对话历史，判断查询类型。

## 分类规则
- FACT_LOOKUP: 查找具体事实（方法列表、表结构、接口参数）
- STRUCTURAL: 查找系统结构（模块关系、依赖链路）
- RELATIONAL: 查找实体间关系（调用关系、数据流）
- COMPARATIVE: 对比两个或多个实体
- TEMPORAL: 涉及时间、版本变更
- EXPLANATION: 需要解释原因、设计决策

## 用户问题
{question}

## 对话历史
{history}

输出 JSON：{"intent": "FACT_LOOKUP", "confidence": 0.9}
```

#### 3.2.2 查询改写器

将用户问题改写为更适合检索的形式：

```java
@Service
public class QueryRewriter {
    /**
     * 多查询改写：生成 2-3 个变体，提高召回覆盖率
     */
    public List<String> rewrite(String question, QueryIntent intent) {
        // 示例：
        // 原始："订单创建涉及哪些表？"
        // 改写：
        //   1. "订单创建 API 的实现"
        //   2. "订单相关的数据表"
        //   3. "createOrder 方法调用的 Service"
        
        Map<String, String> vars = Map.of(
            "question", question,
            "intent", intent.name()
        );
        return llmGateway.callWithTemplate(
            projectId, "query-rewriter", vars, List.class
        );
    }
}
```

#### 3.2.3 HyDE（Hypothetical Document Embeddings）

生成假设性文档，用文档的向量表示检索：

```java
@Service
public class HyDEGenerator {
    /**
     * 生成假设性答案文档，用其 embedding 检索
     * 原理：文档与文档的相似度 > 问题与文档的相似度
     */
    public String generateHypotheticalDoc(String question) {
        String prompt = """
            请根据问题生成一个假设性的答案段落（不需要准确，只需格式和语义相关）：
            
            问题：%s
            
            假设性答案：
            """.formatted(question);
        
        return llmGateway.callRaw(projectId, prompt);
    }
}
```

### 3.3 检索增强层（升级）

#### 3.3.1 多路召回

```java
@Service
public class HybridRetriever {
    private final VectorRetrievalService vectorService;
    private final KeywordSearchService keywordService;
    private final GraphTraversalService graphService;
    
    /**
     * 多路召回：向量 + 关键词 + 图谱
     */
    public RetrievalResult retrieve(String projectId, String versionId, 
                                     String query, QueryIntent intent) {
        CompletableFuture<List<VectorDocument>> vectorFuture = 
            CompletableFuture.supplyAsync(() -> 
                vectorService.semanticSearch(projectId, versionId, query, 20, null));
        
        CompletableFuture<List<VectorDocument>> keywordFuture = 
            CompletableFuture.supplyAsync(() -> 
                keywordService.search(projectId, versionId, query, 10));
        
        CompletableFuture<List<GraphNode>> graphFuture = 
            CompletableFuture.supplyAsync(() -> 
                graphService.findRelevantNodes(projectId, versionId, query, intent));
        
        // 合并结果
        List<VectorDocument> docs = mergeDocs(
            vectorFuture.join(), keywordFuture.join()
        );
        List<GraphNode> nodes = graphFuture.join();
        
        return new RetrievalResult(docs, nodes);
    }
}
```

#### 3.3.2 图谱多跳遍历

```java
@Service
public class GraphTraversalService {
    private final Neo4jGraphDao neo4jDao;
    
    /**
     * 根据意图类型执行不同深度的图遍历
     */
    public List<GraphNode> findRelevantNodes(String projectId, String versionId,
                                              String query, QueryIntent intent) {
        int maxDepth = switch (intent) {
            case FACT_LOOKUP -> 1;
            case STRUCTURAL -> 2;
            case RELATIONAL -> 3;
            default -> 2;
        };
        
        // 种子节点：向量检索命中的节点
        List<GraphNode> seeds = findSeedNodes(projectId, versionId, query);
        
        // 多跳遍历
        Set<String> visited = new HashSet<>();
        List<GraphNode> result = new ArrayList<>(seeds);
        visited.addAll(seeds.stream().map(GraphNode::getId).toList());
        
        for (int depth = 1; depth <= maxDepth; depth++) {
            List<GraphNode> neighbors = expandNeighborhood(
                projectId, versionId, seeds, depth
            );
            for (GraphNode node : neighbors) {
                if (visited.add(node.getId())) {
                    result.add(node);
                }
            }
        }
        
        return result;
    }
}
```

#### 3.3.3 Re-ranking（交叉编码器重排序）

```java
@Service
public class ReRankingService {
    // 可选：使用本地 cross-encoder 或远程 API
    private final CrossEncoderModel crossEncoder;
    
    /**
     * 对召回结果进行重排序
     * 输入：query + candidates
     * 输出：按相关性重新排序的 candidates
     */
    public <T> List<T> reRank(String query, List<T> candidates, 
                               Function<T, String> textExtractor, int topK) {
        if (crossEncoder == null || candidates.isEmpty()) {
            return candidates.stream().limit(topK).toList();
        }
        
        List<ScoredCandidate<T>> scored = candidates.stream()
            .map(c -> {
                String text = textExtractor.apply(c);
                double score = crossEncoder.score(query, text);
                return new ScoredCandidate<>(c, score);
            })
            .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
            .limit(topK)
            .toList();
        
        return scored.stream().map(ScoredCandidate::candidate).toList();
    }
}
```

### 3.4 多轮对话管理（新增）

#### 3.4.1 对话历史存储

```java
@Entity
@Table(name = "lg_qa_conversation")
public class QaConversation {
    @Id
    private String id;
    private String projectId;
    private String sessionId;  // 浏览器 session
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

@Entity
@Table(name = "lg_qa_message")
public class QaMessage {
    @Id
    private String id;
    private String conversationId;
    private String role;  // USER / ASSISTANT
    private String content;
    @Type(JsonType.class)
    private List<EvidenceItem> evidences;
    private Double confidence;
    private Integer tokenCount;
    private LocalDateTime createdAt;
}
```

#### 3.4.2 上下文窗口管理

```java
@Service
public class ConversationContextManager {
    private static final int MAX_CONTEXT_TOKENS = 4000;
    
    /**
     * 构建上下文窗口：最近 N 轮 + 摘要
     */
    public String buildContext(String conversationId, String currentQuery) {
        List<QaMessage> history = messageRepository.findByConversationId(
            conversationId, PageRequest.of(0, 10, Sort.by("createdAt").descending())
        );
        
        // 策略：最近 3 轮完整 + 更早的摘要
        StringBuilder context = new StringBuilder();
        
        if (history.size() > 3) {
            // 摘要更早的对话
            String summary = summarizeHistory(history.subList(3, history.size()));
            context.append("## 对话摘要\n").append(summary).append("\n\n");
        }
        
        // 添加最近 3 轮
        context.append("## 最近对话\n");
        for (QaMessage msg : history.stream().limit(3).toList()) {
            context.append(msg.getRole()).append(": ")
                   .append(msg.getContent()).append("\n");
        }
        
        return context.toString();
    }
}
```

### 3.5 流式响应（升级）

#### 3.5.1 后端 SSE 实现

```java
@RestController
@RequestMapping("/qa")
public class GraphQaController {
    
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody QaRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);
        
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 发送思考中状态
                emitter.send(SseEmitter.event()
                    .name("thinking")
                    .data("{\"stage\": \"understanding\"}"));
                
                // 2. 流式生成
                qaAgent.answerStream(
                    request.getProjectId(),
                    request.getVersionId(),
                    request.getQuestion(),
                    new StreamCallback() {
                        @Override
                        public void onToken(String token) {
                            emitter.send(SseEmitter.event()
                                .name("token")
                                .data("{\"text\": \"" + escapeJson(token) + "\"}"));
                        }
                        
                        @Override
                        public void onEvidence(List<EvidenceItem> evidences) {
                            emitter.send(SseEmitter.event()
                                .name("evidence")
                                .data(objectMapper.writeValueAsString(evidences)));
                        }
                        
                        @Override
                        public void onComplete(QaAnswer answer) {
                            emitter.send(SseEmitter.event()
                                .name("complete")
                                .data(objectMapper.writeValueAsString(answer)));
                            emitter.complete();
                        }
                    }
                );
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        
        return emitter;
    }
}
```

#### 3.5.2 前端流式接收

```typescript
// qa.api.ts
export const qaApi = {
  askStream: (
    data: { question: string; projectId: string; versionId?: string },
    callbacks: {
      onToken: (text: string) => void
      onEvidence: (evidences: Evidence[]) => void
      onComplete: (answer: QaAnswer) => void
      onError: (error: Error) => void
    }
  ) => {
    const eventSource = new EventSource('/qa/ask/stream')
    
    eventSource.addEventListener('token', (e) => {
      const { text } = JSON.parse(e.data)
      callbacks.onToken(text)
    })
    
    eventSource.addEventListener('evidence', (e) => {
      const evidences = JSON.parse(e.data)
      callbacks.onEvidence(evidences)
    })
    
    eventSource.addEventListener('complete', (e) => {
      const answer = JSON.parse(e.data)
      callbacks.onComplete(answer)
      eventSource.close()
    })
    
    eventSource.onerror = (e) => {
      callbacks.onError(new Error('Stream error'))
      eventSource.close()
    }
    
    return () => eventSource.close()
  }
}
```

### 3.6 证据增强（升级）

#### 3.6.1 证据链接化

```java
@Data
public class EvidenceItem {
    private String sourceKind;  // GRAPH_NODE / DOC_CHUNK / KNOWLEDGE_CLAIM
    private String ref;
    private String title;
    private String excerpt;
    
    // 新增：可点击的跳转链接
    private String jumpUrl;      // 如 "/graph?node=xxx" 或 "/doc/xxx#chunk-3"
    private String nodeType;     // 节点类型，用于图标展示
    private String sourceFile;   // 源文件路径
    
    // 新增：置信度与来源说明
    private Double relevanceScore;
    private String retrievalMethod;  // VECTOR / KEYWORD / GRAPH_TRAVERSAL
}
```

#### 3.6.2 前端证据卡片

```vue
<!-- EvidenceCard.vue -->
<template>
  <el-card class="evidence-card" shadow="hover" @click="jumpToSource">
    <div class="evidence-header">
      <el-icon :color="typeColor">
        <component :is="typeIcon" />
      </el-icon>
      <span class="evidence-type">{{ typeLabel }}</span>
      <el-tag size="small" :type="methodTagType">{{ retrievalMethod }}</el-tag>
    </div>
    <div class="evidence-title">{{ title }}</div>
    <div class="evidence-excerpt">{{ excerpt }}</div>
    <div class="evidence-meta">
      <span>相关性: {{ (relevanceScore * 100).toFixed(0) }}%</span>
      <span v-if="sourceFile">{{ sourceFile }}</span>
    </div>
  </el-card>
</template>

<script setup lang="ts">
const jumpToSource = () => {
  if (props.evidence.jumpUrl) {
    router.push(props.evidence.jumpUrl)
  }
}
</script>
```

### 3.7 反馈闭环（新增）

#### 3.7.1 反馈数据模型

```java
@Entity
@Table(name = "lg_qa_feedback")
public class QaFeedback {
    @Id
    private String id;
    private String messageId;      // 关联 QaMessage
    private String conversationId;
    private String projectId;
    
    private Boolean helpful;       // 有用/无用
    private String feedbackText;   // 用户补充说明（可选）
    
    // 用于持续优化
    @Type(JsonType.class)
    private List<String> usedEvidenceIds;  // 实际使用的证据
    private String question;
    private String answer;
    
    private LocalDateTime createdAt;
}
```

#### 3.7.2 反馈收集 API

```java
@RestController
@RequestMapping("/qa/feedback")
public class QaFeedbackController {
    
    @PostMapping
    public Result<Void> submitFeedback(@RequestBody FeedbackRequest request) {
        QaFeedback feedback = new QaFeedback();
        feedback.setId(UUID.randomUUID().toString());
        feedback.setMessageId(request.getMessageId());
        feedback.setHelpful(request.getHelpful());
        feedback.setFeedbackText(request.getFeedbackText());
        // ... 其他字段
        feedbackRepository.insert(feedback);
        
        // 异步：负面反馈触发质量分析
        if (!request.getHelpful()) {
            qualityAnalyzer.analyzeAsync(feedback);
        }
        
        return Result.ok();
    }
}
```

### 3.8 集成 GraphRagPlannerAgent

当前 `GraphRagPlannerAgent` 已实现但未集成，需要将其接入主流程：

```java
@Service
public class QaAgent {
    private final GraphRagPlannerAgent plannerAgent;
    private final GraphRagPlanExecutor planExecutor;
    
    public QaAnswer answer(String projectId, String versionId, String question) {
        // 1. 意图分类
        QueryIntent intent = intentClassifier.classify(question, history);
        
        // 2. 复杂问题使用 Planner
        if (intent == QueryIntent.STRUCTURAL || intent == QueryIntent.RELATIONAL) {
            // 获取相关 Claims
            List<KnowledgeClaim> claims = claimService.findRelevant(projectId, question);
            
            // 生成查询计划
            GraphRagPlan plan = plannerAgent.plan(projectId, question, claims);
            
            // 执行计划
            GraphRagExecutionResult result = planExecutor.execute(
                projectId, versionId, 
                extractSubjectKeys(plan), 
                extractPathFrom(plan), 
                extractPathTo(plan)
            );
            
            // 基于执行结果生成答案
            return generateFromExecutionResult(question, result);
        }
        
        // 3. 简单问题走原有 RAG 流程
        return simpleRagAnswer(projectId, versionId, question, intent);
    }
}
```

### 3.9 性能优化

#### 3.9.1 异步并行检索

```java
public QaAnswer answerStream(String projectId, String versionId, 
                              String question, StreamCallback callback) {
    // 并行执行所有检索操作
    CompletableFuture<List<VectorDocument>> docsFuture = 
        CompletableFuture.supplyAsync(() -> retriever.retrieveDocs(...));
    
    CompletableFuture<List<GraphNode>> nodesFuture = 
        CompletableFuture.supplyAsync(() -> retriever.retrieveNodes(...));
    
    CompletableFuture<List<KnowledgeClaim>> claimsFuture = 
        CompletableFuture.supplyAsync(() -> claimService.findRelevant(...));
    
    // 等待所有检索完成
    CompletableFuture.allOf(docsFuture, nodesFuture, claimsFuture).join();
    
    // 合并结果并生成答案
    String context = buildContext(docsFuture.join(), nodesFuture.join(), claimsFuture.join());
    
    // 流式调用 LLM
    return llmGateway.callStream(projectId, "qa-answer", vars, callback);
}
```

#### 3.9.2 语义缓存

```java
@Service
public class SemanticCache {
    private final EmbeddingModel embeddingModel;
    private final CacheService cacheService;
    
    private static final double SIMILARITY_THRESHOLD = 0.95;
    
    /**
     * 语义缓存：相似问题复用答案
     */
    public Optional<QaAnswer> get(String question) {
        float[] embedding = embeddingModel.embed(question);
        
        // 在缓存中查找相似问题
        List<CachedQa> candidates = cacheService.findSimilar(
            "qa:cache", embedding, 5
        );
        
        for (CachedQa cached : candidates) {
            double similarity = cosineSimilarity(embedding, cached.getEmbedding());
            if (similarity >= SIMILARITY_THRESHOLD) {
                return Optional.of(cached.getAnswer());
            }
        }
        
        return Optional.empty();
    }
    
    public void put(String question, QaAnswer answer) {
        float[] embedding = embeddingModel.embed(question);
        CachedQa cached = new CachedQa(question, embedding, answer);
        cacheService.putWithEmbedding("qa:cache", cached);
    }
}
```

---

## 四、实施计划

### 4.1 阶段划分

| 阶段 | 周期 | 内容 | 优先级 |
|------|------|------|--------|
| **P0 - 基础可用** | 1 周 | 流式响应 + 多轮对话 + 证据链接 | 🔴 高 |
| **P1 - 质量提升** | 2 周 | 查询理解 + 多路召回 + Re-ranking | 🔴 高 |
| **P2 - 智能增强** | 2 周 | 集成 GraphRagPlanner + HyDE | 🟡 中 |
| **P3 - 闭环优化** | 1 周 | 反馈收集 + 评估指标 + 持续学习 | 🟡 中 |
| **P4 - 性能优化** | 1 周 | 语义缓存 + 异步并行 | 🟢 低 |

### 4.2 P0 详细任务

```
Week 1:
├── Day 1-2: 后端 SSE 流式接口
│   ├── GraphQaController.askStream()
│   ├── QaAgent.answerStream()
│   └── LlmGateway.callStream()
│
├── Day 3: 前端流式接收
│   ├── qa.api.ts 添加 askStream
│   └── GraphQa.vue 改造为流式渲染
│
├── Day 4: 多轮对话后端
│   ├── lg_qa_conversation 表
│   ├── lg_qa_message 表
│   ├── ConversationContextManager
│   └── 对话历史 API
│
└── Day 5: 多轮对话前端 + 证据链接
    ├── 会话管理 UI
    ├── 上下文传递
    └── EvidenceItem.jumpUrl 实现
```

### 4.3 验收标准

**P0 验收：**
- [ ] 用户提问后 <1s 开始显示流式输出
- [ ] 支持 5 轮追问，上下文连贯
- [ ] 证据卡片可点击跳转到对应节点/文档
- [ ] 对话历史持久化，刷新不丢失

**P1 验收：**
- [ ] 召回准确率 >70%（人工评测集）
- [ ] 复杂结构问题正确率 >60%
- [ ] 响应时间 <5s（端到端）

---

## 五、技术选型参考

### 5.1 市场主流方案对比

| 方案 | 特点 | 适用场景 | 复杂度 |
|------|------|----------|--------|
| **基础 RAG** | 向量检索 + LLM | 简单问答 | ⭐ |
| **GraphRAG** | 图谱增强 + 多跳遍历 | 结构化知识 | ⭐⭐⭐ |
| **HyDE** | 假设文档 embedding | 提升召回 | ⭐⭐ |
| **Self-RAG** | LLM 自判断是否需要检索 | 减少无效检索 | ⭐⭐⭐ |
| **CRAG** | 检索结果质量评估 | 提高可靠性 | ⭐⭐⭐ |
| **Agentic RAG** | Agent 动态规划检索 | 复杂多跳问题 | ⭐⭐⭐⭐ |

### 5.2 推荐技术栈

| 组件 | 推荐方案 | 理由 |
|------|----------|------|
| 向量检索 | pgvector (已有) | 项目已集成，无需迁移 |
| 关键词检索 | PostgreSQL FTS | 复用现有数据库 |
| Re-ranking | bge-reranker-large | 开源、效果好 |
| 流式输出 | SSE (Spring WebFlux) | 简单、浏览器原生支持 |
| 对话存储 | PostgreSQL | 复用现有基础设施 |
| 缓存 | Redis (已有) | 项目已集成 |

---

## 六、风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| LLM 幻觉 | 给出错误信息 | 强制引用证据、事实一致性校验 |
| 响应延迟 | 用户体验差 | 流式输出、语义缓存、异步并行 |
| 召回不准 | 答案不完整 | 多路召回、Re-ranking、HyDE |
| 上下文超限 | 多轮对话失败 | 滑动窗口 + 摘要压缩 |
| 图谱不完整 | 无法回答结构问题 | 降级到文档检索、标记 GapTask |

---

## 七、总结

当前 QA 系统处于"有框架无能力"的状态，核心问题是：

1. **检索策略过于简单**：单一向量检索，无 Query 理解、无 Re-ranking
2. **缺乏多轮能力**：无对话历史管理，无法处理追问
3. **体验不完整**：同步阻塞、证据不可跳转、无反馈机制
4. **规划能力闲置**：GraphRagPlannerAgent 已实现但未集成

**建议优先级：**
1. 先做 P0（流式 + 多轮 + 证据链接），让系统"可用"
2. 再做 P1（查询理解 + 多路召回），让系统"好用"
3. 最后做 P2-P4（智能增强 + 闭环优化），让系统"智能"

预计 6 周完成全部优化，届时 QA 系统将成为 LegacyGraph 的核心差异化能力。
