package io.github.legacygraph.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.EvidenceItem;
import io.github.legacygraph.dto.rag.GraphRagEvidenceCard;
import io.github.legacygraph.dto.rag.GraphRagExecutionResult;
import io.github.legacygraph.dto.rag.GraphRagPlan;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.service.qa.ConversationContextManager;
import io.github.legacygraph.service.graph.GraphRagPlanExecutor;
import io.github.legacygraph.service.qa.HybridRetrievalService;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.service.qa.ReRankingService;
import io.github.legacygraph.service.qa.SemanticCache;
import io.github.legacygraph.service.qa.VectorRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * QA Agent 增强版 - 支持流式输出、多轮对话、意图分类、语义缓存、GraphRAG
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnhancedQaAgent {

    private final ConversationContextManager conversationManager;
    private final QueryIntentClassifier intentClassifier;
    private final QueryRewriter queryRewriter;
    private final HyDEGenerator hydeGenerator;
    private final KnowledgeClaimService knowledgeClaimService;
    private final HybridRetrievalService hybridRetrievalService;
    private final ReRankingService reRankingService;
    private final VectorRetrievalService vectorRetrievalService;
    private final Neo4jGraphDao neo4jGraphDao;
    private final LlmGateway llmGateway;
    private final GraphRagPlannerAgent plannerAgent;
    private final GraphRagPlanExecutor planExecutor;
    private final SemanticCache semanticCache;
    private final ObjectMapper objectMapper;

    /**
     * 流式问答
     */
    public void answerStream(String projectId, String versionId, String question,
                             String conversationId, SseEmitter emitter) {
        long totalStart = System.currentTimeMillis();
        Map<String, Long> stageTimings = new LinkedHashMap<>();
        try {
            long stageStart = System.currentTimeMillis();
            // 0. 获取或创建对话并保存用户消息，缓存命中也必须保留多轮历史。
            var conversation = conversationManager.getOrCreateConversation(
                projectId, null, conversationId
            );
            conversationManager.saveUserMessage(conversation.getId(), question);
            stageTimings.put("init", System.currentTimeMillis() - stageStart);

            // 1. 语义缓存检查
            stageStart = System.currentTimeMillis();
            sendEvent(emitter, "thinking", Map.of("stage", "cache_check"));
            String cachedAnswer = semanticCache.get(projectId, question);
            stageTimings.put("cache_check", System.currentTimeMillis() - stageStart);
            
            if (cachedAnswer != null && !cachedAnswer.isBlank()) {
                String evidencesJson = "[]";
                var assistantMessage = conversationManager.saveAssistantMessage(
                    conversation.getId(), cachedAnswer, evidencesJson, 1.0
                );
                conversationManager.updateConversationTitle(conversation.getId());

                // 缓存命中：流式输出缓存答案
                log.info("Semantic cache hit for projectId={}, answerLength={}", projectId, cachedAnswer.length());
                for (int i = 0; i < cachedAnswer.length(); i++) {
                    String token = String.valueOf(cachedAnswer.charAt(i));
                    sendEvent(emitter, "token", Map.of("text", token));
                }
                Map<String, Object> completeData = new HashMap<>();
                completeData.put("conversationId", conversation.getId());
                completeData.put("messageId", assistantMessage != null ? assistantMessage.getId() : UUID.randomUUID().toString());
                completeData.put("fromCache", true);
                completeData.put("confidence", 1.0);
                completeData.put("evidences", List.of());
                sendEvent(emitter, "complete", completeData);
                emitter.complete();
                return;
            }

            // 1. 发送思考状态
            sendEvent(emitter, "thinking", Map.of("stage", "understanding"));
            
            // 4. 构建对话历史
            String contextText = conversationManager.buildContextText(conversation.getId());
            List<String> history = conversationManager.getConversationHistory(conversation.getId())
                .stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.toList());

            // 5. 意图分类
            stageStart = System.currentTimeMillis();
            sendEvent(emitter, "thinking", Map.of("stage", "classifying"));
            QueryIntent intent = intentClassifier.classify(projectId, question, history);
            stageTimings.put("intent_classify", System.currentTimeMillis() - stageStart);

            // 6. 查询改写
            stageStart = System.currentTimeMillis();
            sendEvent(emitter, "thinking", Map.of("stage", "rewriting"));
            List<String> queryVariants = new ArrayList<>(queryRewriter.rewrite(projectId, question, intent));
            String hypotheticalDoc = hydeGenerator.generateHypotheticalDocument(projectId, question);
            if (hypotheticalDoc != null && !hypotheticalDoc.isBlank()) {
                queryVariants.add(hypotheticalDoc);
            }
            stageTimings.put("query_rewrite", System.currentTimeMillis() - stageStart);

            // 6.5 GraphRAG 规划 + 执行（如果意图为复杂类型）
            List<GraphRagEvidenceCard> graphRagCards = Collections.emptyList();
            GraphRagPlan plan = null;
            if (intent.requiresPlanner()) {
                try {
                    sendEvent(emitter, "thinking", Map.of("stage", "planning_graph_rag"));
                    List<KnowledgeClaim> relevantClaims = loadRelevantClaims(projectId, versionId, question);
                    plan = plannerAgent.plan(projectId, question, relevantClaims);
                    log.debug("GraphRAG plan generated: {} sub-questions", 
                        plan.getSubQuestions() != null ? plan.getSubQuestions().size() : 0);

                    // 执行 GraphRAG 计划
                    if (plan != null && !plan.isNeedsHumanReview()) {
                        sendEvent(emitter, "thinking", Map.of("stage", "executing_graph_rag"));
                        try {
                            List<String> claimSubjects = plan.getClaimQueries() != null
                                ? plan.getClaimQueries().stream()
                                    .map(GraphRagPlan.ClaimQuery::getSubjectKey)
                                    .filter(Objects::nonNull)
                                    .distinct()
                                    .collect(Collectors.toList())
                                : Collections.emptyList();

                            List<String> pathFromKeys = plan.getPathQueries() != null
                                ? plan.getPathQueries().stream()
                                    .map(GraphRagPlan.PathQuery::getStartNodeFilter)
                                    .filter(Objects::nonNull)
                                    .distinct()
                                    .collect(Collectors.toList())
                                : Collections.emptyList();

                            List<String> pathToKeys = plan.getPathQueries() != null
                                ? plan.getPathQueries().stream()
                                    .map(GraphRagPlan.PathQuery::getEndNodeFilter)
                                    .filter(Objects::nonNull)
                                    .distinct()
                                    .collect(Collectors.toList())
                                : Collections.emptyList();

                            GraphRagExecutionResult result = planExecutor.execute(
                                projectId, versionId, claimSubjects, pathFromKeys, pathToKeys
                            );
                            graphRagCards = result.getAllCards() != null
                                ? result.getAllCards()
                                : Collections.emptyList();
                            log.info("GraphRAG execution completed: {} evidence cards", graphRagCards.size());
                        } catch (Exception e) {
                            log.warn("GraphRAG execution failed, continuing without: {}", e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("GraphRAG planning failed, continuing without plan: {}", e.getMessage());
                }
            }

            // 7. 多路召回
            stageStart = System.currentTimeMillis();
            sendEvent(emitter, "thinking", Map.of("stage", "retrieving"));
            List<VectorDocument> docs = hybridRetrievalService.retrieve(
                projectId, versionId, question, queryVariants, 20
            );
            stageTimings.put("retrieval", System.currentTimeMillis() - stageStart);

            // 8. Re-ranking
            stageStart = System.currentTimeMillis();
            docs = reRankingService.reRank(question, docs, 10);
            stageTimings.put("rerank", System.currentTimeMillis() - stageStart);

            // 9. 图谱扩展
            sendEvent(emitter, "thinking", Map.of("stage", "expanding_graph"));
            List<GraphNode> graphNodes = expandGraph(projectId, versionId, question, intent);

            // 10. 构建证据列表（含 GraphRAG 证据卡）
            List<EvidenceItem> evidences = buildEvidenceList(docs, graphNodes);
            // 追加 GraphRAG 证据卡
            for (GraphRagEvidenceCard card : graphRagCards) {
                if (evidences.size() >= 20) break;
                evidences.add(EvidenceItem.fromGraphNode(
                    card.getNodeKey(),
                    card.getNodeKey(),
                    card.getSourceType() != null ? card.getSourceType() : "GRAPH_RAG",
                    null,
                    card.getExcerpt()
                ));
            }
            sendEvent(emitter, "evidence", Map.of("items", evidences));

            // 11. 构建上下文（含 GraphRAG 结果）
            String retrievalContext = buildRetrievalContext(docs, graphNodes);
            retrievalContext = appendGraphRagContext(retrievalContext, graphRagCards, plan);
            String fullContext = contextText + "\n" + retrievalContext;

            // 12. 流式生成答案
            final long generateStart = System.currentTimeMillis();
            sendEvent(emitter, "thinking", Map.of("stage", "generating"));
            
            Map<String, String> llmVars = new HashMap<>();
            llmVars.put("question", question);
            llmVars.put("context", fullContext);
            llmVars.put("history", contextText);

            StringBuilder fullAnswer = new StringBuilder();
            
            llmGateway.callStream(projectId, "qa-answer-enhanced", llmVars, new LlmGateway.StreamCallback() {
                @Override
                public void onToken(String token) {
                    fullAnswer.append(token);
                    try {
                        sendEvent(emitter, "token", Map.of("text", token));
                    } catch (Exception e) {
                        log.error("Failed to send token", e);
                    }
                }

                @Override
                public void onComplete(String fullText) {
                    try {
                        stageTimings.put("llm_generate", System.currentTimeMillis() - generateStart);
                        
                        // 保存助手消息
                        String evidencesJson = objectMapper.writeValueAsString(evidences);
                        var assistantMessage = conversationManager.saveAssistantMessage(
                            conversation.getId(), fullText, evidencesJson, 0.8
                        );
                        
                        // 更新对话标题
                        conversationManager.updateConversationTitle(conversation.getId());

                        // 写入语义缓存
                        if (fullText != null && !fullText.isBlank()) {
                            semanticCache.put(projectId, question, fullText, evidencesJson);
                        }

                        // 记录端到端延迟
                        long totalLatency = System.currentTimeMillis() - totalStart;
                        log.info("QA 端到端延迟: {}ms, 各阶段: {}", totalLatency, stageTimings);

                        // 发送完成事件
                        Map<String, Object> completeData = new HashMap<>();
                        completeData.put("conversationId", conversation.getId());
                        completeData.put("messageId", assistantMessage != null ? assistantMessage.getId() : UUID.randomUUID().toString());
                        completeData.put("confidence", 0.8);
                        completeData.put("evidences", evidences);
                        completeData.put("latencyMs", totalLatency);
                        completeData.put("stageTimings", stageTimings);
                        sendEvent(emitter, "complete", completeData);
                        
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("Failed to complete stream", e);
                        emitter.completeWithError(e);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    log.error("LLM stream error", error);
                    try {
                        sendEvent(emitter, "error", Map.of("message", error.getMessage() != null ? error.getMessage() : "未知错误"));
                        emitter.completeWithError(error instanceof Exception ? (Exception) error : new RuntimeException(error));
                    } catch (Exception e) {
                        log.error("Failed to send error", e);
                    }
                }
            });

        } catch (Exception e) {
            log.error("QA stream error", e);
            try {
                sendEvent(emitter, "error", Map.of("message", e.getMessage()));
                emitter.completeWithError(e);
            } catch (Exception ex) {
                log.error("Failed to send error event", ex);
            }
        }
    }

    /**
     * 图谱扩展
     */
    private List<GraphNode> expandGraph(String projectId, String versionId, 
                                        String question, QueryIntent intent) {
        try {
            // 从向量检索结果中提取节点ID
            List<VectorDocument> seedDocs = vectorRetrievalService.semanticSearch(
                projectId, versionId, question, 5, null
            );
            
            List<String> seedNodeIds = seedDocs.stream()
                .filter(d -> d.getMeta() != null && d.getMeta().contains("nodeId"))
                .map(d -> {
                    // 从 meta JSON 提取 nodeId
                    try {
                        var meta = objectMapper.readTree(d.getMeta());
                        return meta.has("nodeId") ? meta.get("nodeId").asText() : null;
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .limit(5)
                .collect(Collectors.toList());

            if (seedNodeIds.isEmpty()) {
                return Collections.emptyList();
            }

            // 查找种子节点
            List<GraphNode> seedNodes = neo4jGraphDao.findNodesByIds(seedNodeIds);
            if (seedNodes.isEmpty()) {
                return Collections.emptyList();
            }

            // 根据意图类型决定扩展深度
            int depth = intent.getRecommendedGraphDepth();
            Set<String> visited = new HashSet<>();
            List<GraphNode> result = new ArrayList<>(seedNodes);
            visited.addAll(seedNodes.stream().map(GraphNode::getId).toList());

            for (int d = 1; d <= depth; d++) {
                for (GraphNode seed : new ArrayList<>(seedNodes)) {
                    try {
                        Set<String> neighborIds = neo4jGraphDao.findNeighborNodeIds(
                            projectId, seed.getId()
                        );
                        List<GraphNode> neighbors = neo4jGraphDao.findNodesByIds(
                            new ArrayList<>(neighborIds)
                        );
                        for (GraphNode neighbor : neighbors) {
                            if (visited.add(neighbor.getId())) {
                                result.add(neighbor);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to expand node {}: {}", seed.getId(), e.getMessage());
                    }
                }
            }

            return result;
        } catch (Exception e) {
            log.warn("Graph expansion failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 构建证据列表
     */
    private List<EvidenceItem> buildEvidenceList(List<VectorDocument> docs, List<GraphNode> nodes) {
        List<EvidenceItem> evidences = new ArrayList<>();

        // 文档证据
        for (VectorDocument doc : docs) {
            if (evidences.size() >= 10) break;
            evidences.add(EvidenceItem.fromDocChunk(
                doc.getId().toString(),
                doc.getSourceUri(),
                doc.getContent(),
                doc.getChunkType()
            ));
        }

        // 图谱节点证据
        for (GraphNode node : nodes) {
            if (evidences.size() >= 15) break;
            evidences.add(EvidenceItem.fromGraphNode(
                node.getId(),
                node.getDisplayName() != null ? node.getDisplayName() : node.getNodeName(),
                node.getNodeType(),
                node.getSourcePath(),
                node.getDescription()
            ));
        }

        return evidences;
    }

    /**
     * 构建检索上下文
     */
    private String buildRetrievalContext(List<VectorDocument> docs, List<GraphNode> nodes) {
        StringBuilder context = new StringBuilder();

        context.append("## 相关文档\n");
        for (int i = 0; i < Math.min(5, docs.size()); i++) {
            VectorDocument doc = docs.get(i);
            context.append("[").append(i + 1).append("] ")
                   .append(doc.getSourceUri()).append("\n")
                   .append(doc.getContent()).append("\n\n");
        }

        context.append("## 相关代码/实体\n");
        for (int i = 0; i < Math.min(5, nodes.size()); i++) {
            GraphNode node = nodes.get(i);
            context.append("- ").append(node.getNodeType()).append(": ")
                   .append(node.getDisplayName() != null ? node.getDisplayName() : node.getNodeName());
            if (node.getDescription() != null) {
                context.append(" - ").append(node.getDescription());
            }
            context.append("\n");
        }

        return context.toString();
    }

    /**
     * 追加 GraphRAG 查询结果到上下文
     */
    private String appendGraphRagContext(String currentContext, List<GraphRagEvidenceCard> cards, GraphRagPlan plan) {
        if (cards == null || cards.isEmpty()) {
            return currentContext;
        }

        StringBuilder ctx = new StringBuilder(currentContext);
        ctx.append("\n## GraphRAG 知识查询结果\n");

        if (plan != null && plan.getReasoning() != null) {
            ctx.append("> 查询策略: ").append(plan.getReasoning()).append("\n");
        }

        for (int i = 0; i < Math.min(10, cards.size()); i++) {
            GraphRagEvidenceCard card = cards.get(i);
            ctx.append("- [").append(card.getSourceType()).append("] ")
               .append(card.getNodeKey());
            if (card.getExcerpt() != null) {
                ctx.append(": ").append(card.getExcerpt());
            }
            if (card.getConfidence() != null) {
                ctx.append(" (置信度: ").append(card.getConfidence()).append(")");
            }
            ctx.append("\n");
        }

        return ctx.toString();
    }

    private List<KnowledgeClaim> loadRelevantClaims(String projectId, String versionId, String question) {
        try {
            // 先取较多样本，再按 query 相关性筛选
            List<KnowledgeClaim> allClaims = knowledgeClaimService.listClaims(
                projectId, versionId, null, null, null, null, 200
            );
            if (allClaims.isEmpty()) return List.of();

            // 按 query 关键词相关性打分并排序，取 Top 50
            String queryLower = question.toLowerCase();
            String[] queryTerms = queryLower.split("[\\s,，。？！]+");

            return allClaims.stream()
                .map(claim -> new ClaimScore(claim, computeClaimRelevance(claim, queryLower, queryTerms)))
                .filter(cs -> cs.score > 0)
                .sorted(java.util.Comparator.comparingDouble(ClaimScore::score).reversed())
                .limit(50)
                .map(ClaimScore::claim)
                .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to load KnowledgeClaims for GraphRAG planning: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 计算单条 Claim 与 query 的相关性分数。
     * 综合考虑 subjectKey、objectKey、objectValue、predicate 中的关键词命中。
     */
    private double computeClaimRelevance(KnowledgeClaim claim, String queryLower, String[] queryTerms) {
        double score = 0.0;

        String subjectKey = claim.getSubjectKey() != null ? claim.getSubjectKey().toLowerCase() : "";
        String objectKey = claim.getObjectKey() != null ? claim.getObjectKey().toLowerCase() : "";
        String objectValue = claim.getObjectValue() != null ? claim.getObjectValue().toLowerCase() : "";
        String predicate = claim.getPredicate() != null ? claim.getPredicate().toLowerCase() : "";

        String combined = subjectKey + " " + objectKey + " " + objectValue + " " + predicate;

        for (String term : queryTerms) {
            if (term.length() < 2) continue;
            if (subjectKey.contains(term)) score += 3.0;
            if (objectKey.contains(term)) score += 2.0;
            if (objectValue.contains(term)) score += 1.5;
            if (predicate.contains(term)) score += 1.0;
        }

        // confidence 加权
        if (claim.getConfidence() != null) {
            score *= (0.5 + claim.getConfidence().doubleValue());
        }

        return score;
    }

    private record ClaimScore(KnowledgeClaim claim, double score) {}

    private void sendEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event()
            .name(eventName)
            .data(objectMapper.writeValueAsString(data)));
    }
}
