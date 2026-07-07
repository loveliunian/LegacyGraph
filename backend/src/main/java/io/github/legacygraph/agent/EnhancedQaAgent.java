package io.github.legacygraph.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.TraversalDirection;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.ChangeImpactAnalysis;
import io.github.legacygraph.dto.EvidenceItem;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.rag.GraphRagEvidenceCard;
import io.github.legacygraph.dto.rag.GraphRagExecutionResult;
import io.github.legacygraph.dto.rag.GraphRagPlan;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.service.change.ImpactSubgraphService;
import io.github.legacygraph.service.graph.GraphRagPlanExecutor;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.service.qa.ConversationContextManager;
import io.github.legacygraph.service.qa.HybridRetrievalService;
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
    private final ImpactSubgraphService impactSubgraphService;
    private final ChangeImpactAgent changeImpactAgent;
    private final ChangeImpactQuestionParser changeImpactParser;

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

            // 5.5 变更影响专用链路（与 GraphRAG 分支并列互斥，CHANGE_IMPACT 不触发 GraphRAG Planner）
            long impactStart = System.currentTimeMillis();
            List<EvidenceItem> impactEvidences = Collections.emptyList();
            ChangeImpactAnalysis impactAnalysis = null;
            ChangeImpactQuestionParser.ParsedChangeRequest parsedChange = null;
            if (intent.requiresChangeImpact()) {
                try {
                    sendEvent(emitter, "thinking", Map.of("stage", "parsing_change"));
                    parsedChange = changeImpactParser.parse(question);
                    if (parsedChange.getChangeKind() != null
                            && !"UNKNOWN".equals(parsedChange.getChangeKind())
                            && parsedChange.getTableName() != null) {
                        sendEvent(emitter, "thinking", Map.of("stage", "extracting_impact"));
                        String targetNodeId = resolveTableNodeId(projectId, versionId, parsedChange.getTableName());
                        if (targetNodeId != null) {
                            ImpactSubgraph subgraph = impactSubgraphService.extractByNodeMultiHop(
                                projectId, versionId, targetNodeId,
                                TraversalDirection.TABLE_REVERSE, intent.getRecommendedGraphDepth());

                            sendEvent(emitter, "thinking", Map.of("stage", "analyzing_impact"));
                            ChangeImpactAgent.ChangeImpactInput impactInput = ChangeImpactAgent.ChangeImpactInput.builder()
                                .changeTarget(parsedChange.getTableName() + "."
                                    + (parsedChange.getColumnName() != null ? parsedChange.getColumnName() : ""))
                                .changeDescription(question)
                                .dependencies(subgraph.getDependencySummary())
                                .build();
                            AgentEnvelope<ChangeImpactAgent.ChangeImpactInput> env =
                                AgentEnvelope.<ChangeImpactAgent.ChangeImpactInput>builder()
                                    .projectId(projectId).agentType("ChangeImpact").input(impactInput).build();
                            try {
                                impactAnalysis = changeImpactAgent.analyze(env);
                            } catch (Exception e) {
                                log.warn("ChangeImpactAgent failed: {}", e.getMessage());
                            }
                            impactEvidences = toImpactEvidenceItems(subgraph);

                            Map<String, Object> impactData = new HashMap<>();
                            impactData.put("changeKind", parsedChange.getChangeKind());
                            impactData.put("tableName", parsedChange.getTableName());
                            impactData.put("severity", impactAnalysis != null ? impactAnalysis.getSeverity() : "");
                            impactData.put("impactedNodes", impactAnalysis != null ? impactAnalysis.getImpactedNodes() : List.of());
                            impactData.put("regressionScope", impactAnalysis != null ? impactAnalysis.getRegressionScope() : List.of());
                            sendEvent(emitter, "impact", impactData);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Change impact branch failed, continuing with generic retrieval: {}", e.getMessage());
                }
            }
            stageTimings.put("change_impact", System.currentTimeMillis() - impactStart);
            // lambda 闭包需 final 副本
            final List<EvidenceItem> finalImpactEvidences = impactEvidences;
            final ChangeImpactAnalysis finalImpactAnalysis = impactAnalysis;
            final ChangeImpactQuestionParser.ParsedChangeRequest finalParsedChange = parsedChange;

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
                    String plannerVersionId = intent == QueryIntent.COMPARATIVE ? null : versionId;
                    List<KnowledgeClaim> relevantClaims = loadRelevantClaims(projectId, plannerVersionId, question, intent);
                    plan = plannerAgent.plan(projectId, question, relevantClaims, intent);
                    log.debug("GraphRAG plan generated: {} sub-questions", 
                        plan.getSubQuestions() != null ? plan.getSubQuestions().size() : 0);

                    // 执行 GraphRAG 计划
                    if (plan != null && !plan.isNeedsHumanReview()) {
                        sendEvent(emitter, "thinking", Map.of("stage", "executing_graph_rag"));
                        try {
                            GraphRagExecutionResult result = planExecutor.execute(
                                projectId, versionId,
                                plan.getClaimQueries(),
                                plan.getPathQueries()
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

            // 10. 构建证据列表（含 GraphRAG 证据卡 + 变更影响证据）
            List<EvidenceItem> evidences = buildEvidenceList(docs, graphNodes);
            // 追加 GraphRAG 证据卡（结构化映射：claimId/nodeKey/行号/relationTypes/confidence）
            for (GraphRagEvidenceCard card : graphRagCards) {
                if (evidences.size() >= 20) break;
                evidences.add(EvidenceItem.fromGraphRagCard(card));
            }
            // 追加变更影响证据
            for (EvidenceItem ie : finalImpactEvidences) {
                if (evidences.size() >= 20) break;
                evidences.add(ie);
            }
            sendEvent(emitter, "evidence", Map.of("items", evidences));

            // 11. 构建上下文（含 GraphRAG 结果）
            String retrievalContext = buildRetrievalContext(docs, graphNodes);
            retrievalContext = appendGraphRagContext(retrievalContext, graphRagCards, plan);
            retrievalContext = appendChangeImpactContext(retrievalContext, finalImpactAnalysis, finalParsedChange);
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
                        // 尝试从 JSON 格式响应中提取 answer 字段（outputSchema 模板会要求 LLM 输出 JSON）
                        String displayText = fullText;
                        try {
                            var jsonNode = objectMapper.readTree(fullText);
                            if (jsonNode.has("answer")) {
                                displayText = jsonNode.get("answer").asText();
                            }
                        } catch (Exception ignored) {
                            // 非 JSON 格式，使用原始文本
                        }

                        stageTimings.put("llm_generate", System.currentTimeMillis() - generateStart);
                        
                        // 保存助手消息（使用提取后的纯文本回答）
                        String evidencesJson = objectMapper.writeValueAsString(evidences);
                        var assistantMessage = conversationManager.saveAssistantMessage(
                            conversation.getId(), displayText, evidencesJson, 0.8
                        );
                        
                        // 更新对话标题
                        conversationManager.updateConversationTitle(conversation.getId());

                        // 写入语义缓存
                        if (displayText != null && !displayText.isBlank()) {
                            semanticCache.put(projectId, question, displayText, evidencesJson);
                        }

                        // 记录端到端延迟
                        long totalLatency = System.currentTimeMillis() - totalStart;
                        log.info("QA 端到端延迟: {}ms, 各阶段: {}", totalLatency, stageTimings);

                        // 发送完成事件（包含提取后的 answer，前端直接用）
                        Map<String, Object> completeData = new HashMap<>();
                        completeData.put("conversationId", conversation.getId());
                        completeData.put("messageId", assistantMessage != null ? assistantMessage.getId() : UUID.randomUUID().toString());
                        completeData.put("confidence", 0.8);
                        completeData.put("evidences", evidences);
                        completeData.put("answer", displayText);
                        completeData.put("latencyMs", totalLatency);
                        completeData.put("stageTimings", stageTimings);
                        if (finalParsedChange != null && finalParsedChange.getChangeKind() != null
                                && !"UNKNOWN".equals(finalParsedChange.getChangeKind())) {
                            Map<String, Object> changeImpact = new HashMap<>();
                            changeImpact.put("changeKind", finalParsedChange.getChangeKind());
                            changeImpact.put("tableName", finalParsedChange.getTableName());
                            changeImpact.put("severity", finalImpactAnalysis != null ? finalImpactAnalysis.getSeverity() : null);
                            changeImpact.put("suggestCreateTask", "ADD_COLUMN".equals(finalParsedChange.getChangeKind()));
                            completeData.put("changeImpact", changeImpact);
                        }
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
     * 追加 GraphRAG 查询结果到上下文（结构化块，给 LLM grounding）。
     * <p>
     * 每张 card 输出 sourceType / nodeKey / claimId / sourcePath:lines /
     * relationTypes / confidence / status / excerpt，让 LLM 引用结构化事实而非自由文本。
     * </p>
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
            ctx.append("[").append(i + 1).append("] sourceType=").append(card.getSourceType())
               .append(" | nodeKey=").append(card.getNodeKey());
            if (card.getClaimId() != null && !card.getClaimId().isBlank()) {
                ctx.append(" | claimId=").append(card.getClaimId());
            }
            if (card.getSourcePath() != null && !card.getSourcePath().isBlank()) {
                ctx.append(" | sourcePath=").append(card.getSourcePath());
                if (card.getStartLine() != null) {
                    ctx.append(":").append(card.getStartLine());
                    if (card.getEndLine() != null) {
                        ctx.append("-").append(card.getEndLine());
                    }
                }
            }
            if (card.getRelationTypes() != null && !card.getRelationTypes().isEmpty()) {
                ctx.append(" | relationTypes=").append(String.join(",", card.getRelationTypes()));
            }
            if (card.getConfidence() != null) {
                ctx.append(" | confidence=").append(card.getConfidence());
            }
            if (card.getStatus() != null && !card.getStatus().isBlank()) {
                ctx.append(" | status=").append(card.getStatus());
            }
            if (card.getExcerpt() != null && !card.getExcerpt().isBlank()) {
                ctx.append("\n    excerpt: ").append(card.getExcerpt());
            }
            ctx.append("\n");
        }

        return ctx.toString();
    }

    /**
     * 加载与问题相关的 KnowledgeClaim，用于 GraphRAG 规划。
     * <p>COMPARATIVE 意图时 versionId 传 null，拉取所有版本的 Claim 以支持跨版本对比。</p>
     */
    private List<KnowledgeClaim> loadRelevantClaims(String projectId, String versionId, String question, QueryIntent intent) {
        try {
            if (intent == QueryIntent.COMPARATIVE) {
                log.info("COMPARATIVE intent: loading all-version claims for cross-version comparison");
            }
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

    /**
     * 按表名查 Table 节点 ID。
     * 无现成 findByName 方法，仿 GraphPathReadModel.getTableImpact：
     * queryNodes("Table") 取全量后按 nodeName 忽略大小写过滤。
     * versionId 须去横线匹配 Neo4j 存储格式。
     */
    private String resolveTableNodeId(String projectId, String versionId, String tableName) {
        if (tableName == null || tableName.isBlank()) return null;
        String normalizedVersionId = versionId != null ? versionId.replace("-", "") : null;
        List<GraphNode> tables = neo4jGraphDao.queryNodes(
            projectId, normalizedVersionId, "Table", null, null, null, 200);
        return tables.stream()
            .filter(t -> tableName.equalsIgnoreCase(t.getNodeName()))
            .map(GraphNode::getId)
            .findFirst()
            .orElse(null);
    }

    /**
     * 把影响子图节点转为 EvidenceItem（复用 EvidenceItem.fromGraphNode）。
     */
    private List<EvidenceItem> toImpactEvidenceItems(ImpactSubgraph subgraph) {
        if (subgraph == null || subgraph.getNodeIds() == null || subgraph.getNodeIds().isEmpty()) {
            return Collections.emptyList();
        }
        List<GraphNode> nodes = neo4jGraphDao.findNodesByIds(subgraph.getNodeIds());
        List<EvidenceItem> items = new ArrayList<>();
        for (GraphNode n : nodes) {
            items.add(EvidenceItem.fromGraphNode(n.getId(), n.getNodeName(), n.getNodeType(),
                n.getSourcePath(), n.getDescription()));
        }
        return items;
    }

    /**
     * 追加变更影响分析结果到上下文，指示 LLM 产出"影响清单 + 执行步骤 + 建议建任务"。
     */
    private String appendChangeImpactContext(String currentContext,
                                             ChangeImpactAnalysis impactAnalysis,
                                             ChangeImpactQuestionParser.ParsedChangeRequest parsed) {
        if (parsed == null || parsed.getChangeKind() == null
                || "UNKNOWN".equals(parsed.getChangeKind())) {
            return currentContext;
        }
        StringBuilder ctx = new StringBuilder(currentContext);
        ctx.append("\n## 变更影响分析\n");
        ctx.append("变更类型: ").append(parsed.getChangeKind()).append("\n");
        if (parsed.getTableName() != null) ctx.append("目标表: ").append(parsed.getTableName()).append("\n");
        if (parsed.getColumnName() != null) ctx.append("字段: ").append(parsed.getColumnName()).append("\n");
        if (parsed.getColumnType() != null) ctx.append("类型: ").append(parsed.getColumnType()).append("\n");
        if (impactAnalysis != null) {
            if (impactAnalysis.getSeverity() != null) ctx.append("严重程度: ").append(impactAnalysis.getSeverity()).append("\n");
            if (impactAnalysis.getImpactedNodes() != null && !impactAnalysis.getImpactedNodes().isEmpty()) {
                ctx.append("受影响节点: ").append(String.join(", ", impactAnalysis.getImpactedNodes())).append("\n");
            }
            if (impactAnalysis.getRegressionScope() != null && !impactAnalysis.getRegressionScope().isEmpty()) {
                ctx.append("回归范围: ").append(String.join(", ", impactAnalysis.getRegressionScope())).append("\n");
            }
        }
        ctx.append("\n请基于以上影响分析，给出：① 受影响清单（表→SQL→Mapper→Service→Controller→前端）")
          .append("② 执行步骤（DDL→实体→Mapper→Service→Controller→前端→测试）")
          .append("③ 建议（如需加字段，建议创建 ADD_COLUMN 变更任务）。无证据的内容标注为推断。\n");
        return ctx.toString();
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event()
            .name(eventName)
            .data(objectMapper.writeValueAsString(data)));
    }
}
