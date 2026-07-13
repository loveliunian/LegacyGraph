package io.github.legacygraph.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.TraversalDirection;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.ChangeImpactAnalysis;
import io.github.legacygraph.dto.EvidenceItem;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.qa.AccessContext;
import io.github.legacygraph.dto.qa.ConfidenceBreakdown;
import io.github.legacygraph.dto.qa.VerificationResult;
import io.github.legacygraph.dto.rag.GraphRagEvidenceCard;
import io.github.legacygraph.dto.rag.GraphRagExecutionResult;
import io.github.legacygraph.dto.rag.GraphRagPlan;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.entity.SemanticCacheEntry;
import io.github.legacygraph.entity.Solution;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.service.change.ImpactSubgraphService;
import io.github.legacygraph.service.graph.GraphRagPlanExecutor;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.service.qa.ConfidenceScorer;
import io.github.legacygraph.service.qa.ConversationContextManager;
import io.github.legacygraph.service.qa.EvidenceVerifier;
import io.github.legacygraph.service.qa.HybridRetrievalService;
import io.github.legacygraph.service.qa.ReRankingService;
import io.github.legacygraph.service.qa.SemanticCache;
import io.github.legacygraph.service.qa.VectorRetrievalService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;
import io.github.legacygraph.util.IdUtil;

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
    private final EvidenceVerifier evidenceVerifier;
    private final ConfidenceScorer confidenceScorer;
    private final io.github.legacygraph.repository.GraphReleaseRepository graphReleaseRepository;
    private final io.github.legacygraph.repository.SolutionRepository solutionRepository;
    private final io.github.legacygraph.config.GraphReleaseConfig graphReleaseConfig;
    private final io.github.legacygraph.service.qa.AclFilterService aclFilterService;

    /** QA 链路专用虚拟线程执行器 — 意图分类/改写/HyDE/规划/召回可部分并行 */
    private final ExecutorService qaExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** Bean 销毁时优雅关闭执行器，避免资源泄漏。 */
    @PreDestroy
    public void shutdown() {
        qaExecutor.shutdown();
        try {
            if (!qaExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                qaExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            qaExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 流式问答
     */
    public void answerStream(String projectId, String versionId, String question,
                             String conversationId, SseEmitter emitter) {
        answerStream(projectId, versionId, question, conversationId, emitter, AccessContext.PUBLIC);
    }

    /**
     * 流式问答（带访问上下文 — 支持证据 ACL 校验和版本化缓存）
     */
    public void answerStream(String projectId, String versionId, String question,
                             String conversationId, SseEmitter emitter, AccessContext accessContext) {
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

            // 0. GraphRelease 状态检查 — 版本未通过质量门禁时直接拒答
            if (checkGraphReleaseFailed(projectId, versionId, emitter, conversation.getId())) {
                return;
            }

            // 1. 语义缓存检查（版本化：按 graphReleaseId + aclHash 隔离）
            stageStart = System.currentTimeMillis();
            sendEvent(emitter, "thinking", Map.of("stage", "cache_check"));
            String aclHash = accessContext != null ? accessContext.aclHash() : null;
            String graphReleaseId = resolveGraphReleaseId(projectId, versionId);
            var cachedEntry = semanticCache.getVersioned(projectId, question, graphReleaseId, aclHash);
            stageTimings.put("cache_check", System.currentTimeMillis() - stageStart);

            if (cachedEntry.isPresent()) {
                SemanticCacheEntry cacheEntry = cachedEntry.get();
                String cachedAnswer = cacheEntry.getAnswer();
                String cachedEvidenceJson = cacheEntry.getEvidence() != null ? cacheEntry.getEvidence() : "[]";
                double cachedConfidence = cacheEntry.getConfidence() != null ? cacheEntry.getConfidence() : 0.8;
                List<EvidenceItem> cachedEvidences = parseEvidences(cachedEvidenceJson);

                var assistantMessage = conversationManager.saveAssistantMessage(
                    conversation.getId(), cachedAnswer, cachedEvidenceJson, cachedConfidence
                );
                conversationManager.updateConversationTitle(conversation.getId());

                // 缓存命中：流式输出缓存答案
                log.info("Semantic cache hit for projectId={}, releaseId={}, answerLength={}",
                    projectId, graphReleaseId, cachedAnswer.length());
                for (int i = 0; i < cachedAnswer.length(); i++) {
                    String token = String.valueOf(cachedAnswer.charAt(i));
                    sendEvent(emitter, "token", Map.of("text", token));
                }
                Map<String, Object> completeData = new HashMap<>();
                completeData.put("conversationId", conversation.getId());
                completeData.put("messageId", assistantMessage != null ? assistantMessage.getId() : IdUtil.fastUUID());
                completeData.put("fromCache", true);
                completeData.put("confidence", cachedConfidence);
                completeData.put("evidences", cachedEvidences);
                completeData.put("latencyMs", System.currentTimeMillis() - totalStart);
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

            // 5. 意图分类 + HyDE 并发执行（两者无依赖）
            stageStart = System.currentTimeMillis();
            sendEvent(emitter, "thinking", Map.of("stage", "classifying"));
            CompletableFuture<QueryIntent> intentFuture = CompletableFuture.supplyAsync(
                () -> intentClassifier.classify(projectId, question, history), qaExecutor);
            CompletableFuture<String> hydeFuture = CompletableFuture.supplyAsync(
                () -> hydeGenerator.generateHypotheticalDocument(projectId, question), qaExecutor);
            // 等待 classify 完成（后续依赖）
            QueryIntent intent = intentFuture.join();
            stageTimings.put("intent_classify", System.currentTimeMillis() - stageStart);

            // G10: 方案评估专用分支 — 加载最新 Solution + ImpactResult，调用 LLM 评估方案完整性/风险/成本
            if (intent == QueryIntent.SOLUTION_EVALUATION) {
                handleSolutionEvaluation(projectId, question, conversation.getId(), emitter,
                    stageTimings, totalStart);
                return;
            }
            // G10: 问题诊断专用分支 — 结合图谱路径检索 + 问题描述，调用 LLM 诊断根因
            if (intent.requiresDiagnosis()) {
                handleDiagnosis(projectId, versionId, question, conversation.getId(), emitter,
                    stageTimings, totalStart);
                return;
            }

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
                            impactEvidences = toImpactEvidenceItems(projectId, subgraph);

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
            final QueryIntent finalIntent = intent;
            final String finalGraphReleaseId = graphReleaseId;
            final String finalAclHash = aclHash;
            final AccessContext finalAccessContext = accessContext;

            // 6. 查询改写（依赖 intent）+ 等待 HyDE 结果
            stageStart = System.currentTimeMillis();
            sendEvent(emitter, "thinking", Map.of("stage", "rewriting"));
            List<String> queryVariants = new ArrayList<>(queryRewriter.rewrite(projectId, question, intent));
            // 取回已在 intent classify 阶段并发启动的 HyDE 结果
            String hypotheticalDoc = hydeFuture.join();
            if (hypotheticalDoc != null && !hypotheticalDoc.isBlank()) {
                queryVariants.add(hypotheticalDoc);
            }
            stageTimings.put("query_rewrite", System.currentTimeMillis() - stageStart);

            // 6.5 GraphRAG 规划 + 7. 多路召回 — 并发执行（两者无依赖）
            List<GraphRagEvidenceCard> graphRagCards = Collections.emptyList();
            GraphRagPlan plan = null;
            // 启动多路召回（异步）— 传入 graphReleaseId + ACL principals 做检索期过滤
            CompletableFuture<List<VectorDocument>> retrievalFuture = CompletableFuture.supplyAsync(
                () -> hybridRetrievalService.retrieve(projectId, versionId, question, queryVariants, 20,
                    finalGraphReleaseId,
                    finalAccessContext != null ? finalAccessContext.principals() : null),
                qaExecutor);
            CompletableFuture<GraphRagResult> graphRagFuture = null;
            if (intent.requiresPlanner()) {
                graphRagFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        sendEvent(emitter, "thinking", Map.of("stage", "planning_graph_rag"));
                        String plannerVersionId = intent == QueryIntent.COMPARATIVE ? null : versionId;
                        List<KnowledgeClaim> relevantClaims = loadRelevantClaims(projectId, plannerVersionId, question, intent);
                        GraphRagPlan p = plannerAgent.plan(projectId, question, relevantClaims, intent);
                        log.debug("GraphRAG plan generated: {} sub-questions",
                            p.getSubQuestions() != null ? p.getSubQuestions().size() : 0);

                        List<GraphRagEvidenceCard> cards = Collections.emptyList();
                        if (!p.isNeedsHumanReview()) {
                            sendEvent(emitter, "thinking", Map.of("stage", "executing_graph_rag"));
                            try {
                                GraphRagExecutionResult result = planExecutor.execute(
                                    projectId, versionId,
                                    p.getClaimQueries(),
                                    p.getPathQueries()
                                );
                                cards = result.getAllCards() != null
                                    ? result.getAllCards()
                                    : Collections.emptyList();
                                log.info("GraphRAG execution completed: {} evidence cards", cards.size());
                            } catch (Exception e) {
                                log.warn("GraphRAG execution failed, continuing without: {}", e.getMessage());
                            }
                        }
                        return new GraphRagResult(p, cards);
                    } catch (Exception e) {
                        log.warn("GraphRAG planning failed, continuing without plan: {}", e.getMessage());
                        return new GraphRagResult(null, Collections.emptyList());
                    }
                }, qaExecutor);
            }

            // 等待检索完成
            List<VectorDocument> docs = retrievalFuture.join();
            stageStart = System.currentTimeMillis();
            stageTimings.put("retrieval", System.currentTimeMillis() - stageStart);

            // 等待 GraphRAG 完成（如果启动了）
            if (graphRagFuture != null) {
                GraphRagResult graphRagResult = graphRagFuture.join();
                plan = graphRagResult.plan;
                graphRagCards = graphRagResult.cards;
            }

            // 8. Re-ranking
            stageStart = System.currentTimeMillis();
            docs = reRankingService.reRank(question, docs, 10);
            stageTimings.put("rerank", System.currentTimeMillis() - stageStart);

            // 9. 图谱扩展
            sendEvent(emitter, "thinking", Map.of("stage", "expanding_graph"));
            List<GraphNode> graphNodes = expandGraph(projectId, versionId, question, intent);

            // 10. 构建证据列表（含 GraphRAG 证据卡 + 变更影响证据）
            List<EvidenceItem> evidences = buildEvidenceList(projectId, docs, graphNodes);
            // 追加 GraphRAG 证据卡（结构化映射：claimId/nodeKey/行号/relationTypes/confidence）
            for (GraphRagEvidenceCard card : graphRagCards) {
                if (evidences.size() >= 20) break;
                evidences.add(EvidenceItem.fromGraphRagCard(projectId, card));
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
            // 列举题：「哪些数据库表」「有哪些 Service」等直接从图谱一次性查全该类型节点，绕开向量 topK
            retrievalContext = appendListingContext(retrievalContext, projectId, versionId, question);
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
                    sendEvent(emitter, "token", Map.of("text", token));
                }

                @Override
                public void onComplete(String fullText) {
                    try {
                        // 尝试从 LLM 响应中提取可读文本
                        String displayText = fullText;
                        try {
                            var jsonNode = objectMapper.readTree(fullText);
                            // 优先提取 answer 字段
                            if (jsonNode.has("answer")) {
                                displayText = jsonNode.get("answer").asText();
                            } else if (jsonNode.isArray()) {
                                // 纯数组 → 转为列表
                                StringBuilder sb = new StringBuilder();
                                for (var item : jsonNode) {
                                    sb.append("- ").append(item.asText()).append("\n");
                                }
                                displayText = sb.toString();
                            } else if (jsonNode.isObject() && jsonNode.size() <= 3) {
                                // 小对象（如 {table_list:[...], source:"..."}) → 扁平化
                                StringBuilder sb = new StringBuilder();
                                jsonNode.fields().forEachRemaining(e -> {
                                    if (e.getValue().isArray()) {
                                        sb.append("**").append(e.getKey()).append("**：\n");
                                        for (var item : e.getValue()) {
                                            sb.append("- ").append(item.asText()).append("\n");
                                        }
                                    } else if (e.getValue().isTextual()) {
                                        sb.append(e.getValue().asText()).append("\n");
                                    }
                                });
                                displayText = sb.toString().trim();
                                if (displayText.isEmpty()) displayText = fullText;
                            }
                            // 大 JSON 对象 → 保留原文（让模型自然语言指令生效）
                        } catch (Exception ignored) {
                            // 非 JSON，直接用
                        }

                        stageTimings.put("llm_generate", System.currentTimeMillis() - generateStart);

                        // ===== 证据验证 + 置信度打分（Task 9.4/9.5）=====
                        long verifyStart = System.currentTimeMillis();
                        VerificationResult verificationResult = evidenceVerifier.verify(
                            displayText, evidences, projectId, finalGraphReleaseId, finalAccessContext
                        );

                        // 检索一致性：简化估算（有 GraphRAG 卡片时一致性更高）
                        double retrievalConsistency = evidences.isEmpty() ? 0.3
                            : Math.min(1.0, (double) evidences.size() / 10.0);
                        // 路径置信度：有图谱节点证据时更高
                        double pathConfidence = computePathConfidence(evidences);

                        ConfidenceBreakdown confidenceBreakdown = confidenceScorer.score(
                            verificationResult, finalIntent, retrievalConsistency, pathConfidence, null
                        );
                        stageTimings.put("verify_confidence", System.currentTimeMillis() - verifyStart);

                        double finalConfidence = confidenceBreakdown.finalScore();
                        boolean isLowConfidence = verificationResult.isLowCoverage();

                        // S4-T1: 强制证据回填校验 — 答案必须引用至少 1 个图谱节点 ID
                        if (graphNodes != null && !graphNodes.isEmpty()
                                && !isChitchatIntent(finalIntent)) {
                            boolean cited = false;
                            for (GraphNode gn : graphNodes) {
                                if (gn.getId() != null && displayText.contains(gn.getId())) {
                                    cited = true;
                                    break;
                                }
                            }
                            if (!cited) {
                                log.warn("S4-T1: answer lacks graph node citation, intent={}, question='{}'",
                                        finalIntent, question);
                                displayText += "\n\n> ⚠️ 本回答未引用图谱证据节点，请核实信息来源。";
                                finalConfidence *= 0.8;
                                isLowConfidence = true;
                            }
                        }

                        // 高风险意图（CHANGE_IMPACT）置信度 < 0.5 → 拒答
                        if (finalIntent == QueryIntent.CHANGE_IMPACT
                                && finalConfidence < ConfidenceScorer.HIGH_RISK_REJECT_THRESHOLD) {
                            String rejectMsg = "抱歉，基于当前证据无法给出可靠的变更影响分析。"
                                + "置信度不足（" + String.format("%.2f", finalConfidence) + "），"
                                + "建议补充更多上下文或联系人工确认。"
                                + (verificationResult.violations().isEmpty() ? ""
                                    : " 违规项: " + String.join("; ", verificationResult.violations()));

                            var rejectMessage = conversationManager.saveAssistantMessage(
                                conversation.getId(), rejectMsg, "[]", finalConfidence
                            );
                            conversationManager.updateConversationTitle(conversation.getId());

                            // 拒答不写入缓存（避免低质量答案被复用）
                            sendEvent(emitter, "token", Map.of("text", rejectMsg));
                            Map<String, Object> rejectData = new HashMap<>();
                            rejectData.put("conversationId", conversation.getId());
                            rejectData.put("messageId", rejectMessage != null ? rejectMessage.getId() : IdUtil.fastUUID());
                            rejectData.put("confidence", finalConfidence);
                            rejectData.put("rejected", true);
                            rejectData.put("reason", "HIGH_RISK_LOW_CONFIDENCE");
                            rejectData.put("evidences", verificationResult.verifiedEvidences());
                            rejectData.put("answer", rejectMsg);
                            rejectData.put("latencyMs", System.currentTimeMillis() - totalStart);
                            // H23: 拒答原因 violations() 数组传给前端，供可验证列表展示
                            rejectData.put("violations", verificationResult.violations());
                            sendEvent(emitter, "complete", rejectData);
                            // H22: 持久化 ACL 阻断事件到审计日志（触发后 lg_qa_audit_log 递增）
                            String auditPrincipal = finalAccessContext != null
                                    && !finalAccessContext.principals().isEmpty()
                                    ? finalAccessContext.principals().get(0) : "anonymous";
                            String questionHash = Integer.toHexString(question.hashCode());
                            String blockedReason = "BLOCK:HIGH_RISK_LOW_CONFIDENCE"
                                    + (verificationResult.violations().isEmpty() ? ""
                                        : "|violations=" + String.join(",", verificationResult.violations()));
                            aclFilterService.audit(projectId, finalGraphReleaseId, auditPrincipal,
                                    questionHash, finalAclHash, blockedReason);
                            emitter.complete();
                            return;
                        }

                        // 低覆盖率标记 LOW_CONFIDENCE（仍返回答案，但前端可展示警告）
                        if (isLowConfidence) {
                            displayText = displayText + "\n\n> ⚠️ **LOW_CONFIDENCE**: 本答案的证据覆盖率为 "
                                + String.format("%.0f%%", verificationResult.evidenceCoverage() * 100)
                                + "，部分结论可能缺乏证据支撑，请谨慎参考。";
                        }

                        // 保存助手消息（使用动态置信度）
                        String evidencesJson = objectMapper.writeValueAsString(
                            verificationResult.verifiedEvidences().isEmpty()
                                ? evidences : verificationResult.verifiedEvidences()
                        );
                        var assistantMessage = conversationManager.saveAssistantMessage(
                            conversation.getId(), displayText, evidencesJson, finalConfidence
                        );

                        // 更新对话标题
                        conversationManager.updateConversationTitle(conversation.getId());

                        // 写入版本化语义缓存
                        if (displayText != null && !displayText.isBlank()) {
                            semanticCache.putVersioned(
                                projectId, question, displayText, evidencesJson,
                                finalGraphReleaseId, finalAclHash,
                                finalIntent.name(), finalConfidence
                            );
                        }

                        // 记录端到端延迟
                        long totalLatency = System.currentTimeMillis() - totalStart;
                        log.info("QA 端到端延迟: {}ms, 各阶段: {}, confidence={}",
                            totalLatency, stageTimings, String.format("%.2f", finalConfidence));

                        // 发送完成事件（包含提取后的 answer，前端直接用）
                        Map<String, Object> completeData = new HashMap<>();
                        completeData.put("conversationId", conversation.getId());
                        completeData.put("messageId", assistantMessage != null ? assistantMessage.getId() : IdUtil.fastUUID());
                        completeData.put("confidence", finalConfidence);
                        completeData.put("confidenceBreakdown", confidenceBreakdown);
                        completeData.put("lowConfidence", isLowConfidence);
                        completeData.put("evidenceCoverage", verificationResult.evidenceCoverage());
                        completeData.put("evidences", verificationResult.verifiedEvidences().isEmpty()
                            ? evidences : verificationResult.verifiedEvidences());
                        completeData.put("answer", displayText);
                        completeData.put("latencyMs", totalLatency);
                        completeData.put("stageTimings", stageTimings);
                        // H23: 低置信度时传 violations 数组，前端展示可验证列表
                        completeData.put("violations", verificationResult.violations());
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
                    sendEvent(emitter, "error", Map.of("message", error.getMessage() != null ? error.getMessage() : "未知错误"));
                    try {
                        emitter.completeWithError(error instanceof Exception ? (Exception) error : new RuntimeException(error));
                    } catch (Exception e) {
                        log.debug("Failed to complete emitter with error", e);
                    }
                }
            });

        } catch (Exception e) {
            log.error("QA stream error", e);
            sendEvent(emitter, "error", Map.of("message", e.getMessage()));
            try {
                emitter.completeWithError(e);
            } catch (Exception ex) {
                log.debug("Failed to complete emitter with error", ex);
            }
        }
    }

    /** 图扩展每层最大节点数 */
    private static final int MAX_EXPANDED_NODES = 100;
    private static final int PER_NODE_NEIGHBOR_LIMIT = 20;

    /**
     * 图谱扩展 — 多源 BFS 批量展开。
     *
     * <p>原实现存在两个问题：
     * <ol>
     *   <li>每层循环都用原始 seedNodes，depth>1 时重复查询同一批邻居（bug）</li>
     *   <li>每个种子节点单独调用 findNeighborNodeIds，seed 数量 × depth 次 Neo4j 往返</li>
     * </ol>
     *
     * <p>优化后：BFS 逐层推进 frontier（已访问节点不再入队），
     * 每层一次批量查询所有 frontier 节点的邻居，Neo4j 往返从 O(N×depth) 降到 O(depth)。</p>
     */
    private List<GraphNode> expandGraph(String projectId, String versionId,
                                        String question, QueryIntent intent) {
        try {
            // 1. 向量检索找种子节点 ID
            List<VectorDocument> seedDocs = vectorRetrievalService.semanticSearch(
                projectId, versionId, question, 5, null
            );
            List<String> seedNodeIds = seedDocs.stream()
                .filter(d -> d.getMeta() != null && d.getMeta().contains("nodeId"))
                .map(d -> {
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

            if (seedNodeIds.isEmpty()) return Collections.emptyList();

            // 2. 解析种子节点
            List<GraphNode> seedNodes = neo4jGraphDao.findNodesByIds(seedNodeIds);
            if (seedNodes.isEmpty()) return Collections.emptyList();

            // 3. BFS 逐层批量展开
            int depth = intent.getRecommendedGraphDepth();
            Set<String> visited = new LinkedHashSet<>();
            List<GraphNode> result = new ArrayList<>(seedNodes);
            visited.addAll(seedNodes.stream().map(GraphNode::getId).collect(Collectors.toList()));

            // frontier: 当前层需要展开的节点 ID
            Set<String> frontier = new LinkedHashSet<>(visited);
            for (int level = 1; level <= depth && !frontier.isEmpty()
                    && result.size() < MAX_EXPANDED_NODES; level++) {
                // 批量查询所有 frontier 节点的邻居
                Map<String, Set<String>> allNeighbors;
                try {
                    allNeighbors = neo4jGraphDao.findNeighborNodeIdsBySources(
                        projectId, frontier, PER_NODE_NEIGHBOR_LIMIT);
                } catch (Exception e) {
                    log.warn("Batch neighbor query failed at level {}: {}", level, e.getMessage());
                    break;
                }

                // 收集下一层 frontier
                Set<String> nextFrontier = new LinkedHashSet<>();
                for (Set<String> nbrs : allNeighbors.values()) {
                    for (String nbrId : nbrs) {
                        if (visited.add(nbrId) && result.size() < MAX_EXPANDED_NODES) {
                            nextFrontier.add(nbrId);
                        }
                    }
                }

                // 批量查询下一层节点实体
                if (!nextFrontier.isEmpty()) {
                    try {
                        List<GraphNode> nextNodes = neo4jGraphDao.findNodesByIds(
                            new ArrayList<>(nextFrontier));
                        result.addAll(nextNodes);
                    } catch (Exception e) {
                        log.warn("Failed to load nodes at level {}: {}", level, e.getMessage());
                    }
                }
                frontier = nextFrontier;
            }

            log.debug("Graph expanded: {} seeds, {} total nodes after {} hops",
                seedNodes.size(), result.size(), depth);

            // S4-T2: 按 evidenceScore + confidence 降序排序，负反馈节点排后面
            result.sort((a, b) -> {
                int scoreA = (a.getEvidenceScore() != null ? a.getEvidenceScore() : 0)
                        + (int) ((a.getConfidence() != null ? a.getConfidence().doubleValue() : 0) * 100);
                int scoreB = (b.getEvidenceScore() != null ? b.getEvidenceScore() : 0)
                        + (int) ((b.getConfidence() != null ? b.getConfidence().doubleValue() : 0) * 100);
                return Integer.compare(scoreB, scoreA);
            });

            return result;
        } catch (Exception e) {
            log.warn("Graph expansion failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 构建证据列表
     */
    private List<EvidenceItem> buildEvidenceList(String projectId, List<VectorDocument> docs,
                                                   List<GraphNode> nodes) {
        List<EvidenceItem> evidences = new ArrayList<>();

        // 文档证据
        for (VectorDocument doc : docs) {
            if (evidences.size() >= 10) break;
            evidences.add(EvidenceItem.fromDocChunk(
                projectId,
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
                projectId,
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
        context.append("（请在答案中用 [节点ID] 格式引用至少一个上述节点）\n");
        for (int i = 0; i < Math.min(5, nodes.size()); i++) {
            GraphNode node = nodes.get(i);
            // S4-T1: 节点 ID 嵌入 context，供 LLM 引用 + 答案校验
            context.append("- [").append(node.getId()).append("] ")
                   .append(node.getNodeType()).append(": ")
                   .append(node.getDisplayName() != null ? node.getDisplayName() : node.getNodeName());
            if (node.getDescription() != null) {
                context.append(" - ").append(node.getDescription());
            }
            context.append("\n");
        }

        return context.toString();
    }

    /**
     * 列举题专用上下文：检测「哪些/列出 所有 X」意图，直接从图谱一次性查出该类型全部节点，
     * 拼成清单喂给 LLM。绕开向量检索 topK 上限 —— 否则「哪些数据库表」只能列到 topK 内的 7~10 张。
     * <p>非列举题或图谱无该类型节点时原样返回。</p>
     */
    private String appendListingContext(String currentContext, String projectId, String versionId, String question) {
        String nodeType = detectListingNodeType(question);
        if (nodeType == null) {
            return currentContext;
        }
        try {
            String vid = IdUtil.normalizeId(versionId);
            List<GraphNode> nodes = neo4jGraphDao.queryNodes(
                    projectId, vid, nodeType, null, null, null, 0);
            if (nodes == null || nodes.isEmpty()) {
                return currentContext;
            }
            List<String> names = nodes.stream()
                    .map(n -> n.getDisplayName() != null && !n.getDisplayName().isBlank()
                            ? n.getDisplayName() : n.getNodeName())
                    .filter(n -> n != null && !n.isBlank())
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
            if (names.isEmpty()) {
                return currentContext;
            }
            int total = names.size();
            int cap = 300; // 避免超大类型（如 Method 近千）撑爆 prompt
            StringBuilder sb = new StringBuilder();
            sb.append("\n## ").append(nodeType).append(" 清单（图谱共 ").append(total).append(" 个，完整列表）\n");
            for (String n : names.subList(0, Math.min(cap, total))) {
                sb.append("- ").append(n).append("\n");
            }
            if (total > cap) {
                sb.append("（仅列前 ").append(cap).append(" 个，共 ").append(total)
                        .append(" 个；如需全部请分批询问）\n");
            }
            sb.append("\n请将以上 ").append(nodeType).append(" 清单用自然语言整理回答，不要直接输出 JSON 或原文照抄。\n");
            log.info("Listing context: type={}, total={}, question='{}'", nodeType, total, question);
            return currentContext + sb;
        } catch (Exception e) {
            log.warn("Listing context failed for type={}: {}", nodeType, e.getMessage());
            return currentContext;
        }
    }

    /**
     * 检测列举意图并映射到 NodeType；非列举题或无法识别类型时返回 null。
     * <p>关键词匹配：列举词（哪些/列出/所有/全部/清单/有几个/多少个...）+ 类型词（数据库表/Service/...）。
     * 中文类型词用 substring；英文类型词用词边界（\b），避免 OrderController/OrderService
     * 这类类名后缀被误当成类型词（「OrderController 处理哪些 API」应映射 ApiEndpoint 而非 Controller）。
     * 单独「表」放最后兜底，避免匹配「表示」「列表」。
     * </p>
     */
    String detectListingNodeType(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String q = question.toLowerCase();
        boolean isListing = q.contains("哪些") || q.contains("列出") || q.contains("列举")
                || q.contains("所有") || q.contains("全部") || q.contains("清单")
                || q.contains("列表") || q.contains("有几个") || q.contains("多少个")
                || q.contains("一共有");
        if (!isListing) {
            return null;
        }
        // 中文类型词（不会出现在类名里，substring 即可）
        if (q.contains("数据库表") || q.contains("数据表") || q.contains("表名")) return "Table";
        if (q.contains("业务域")) return "BusinessDomain";
        if (q.contains("业务对象")) return "BusinessObject";
        if (q.contains("业务流程")) return "BusinessProcess";
        if (q.contains("业务规则")) return "BusinessRule";
        if (q.contains("服务")) return "Service";
        if (q.contains("控制器")) return "Controller";
        if (q.contains("映射")) return "Mapper";
        if (q.contains("接口")) return "ApiEndpoint";
        if (q.contains("页面")) return "Page";
        if (q.contains("方法")) return "Method";
        // 英文类型词 —— 词边界匹配，规避类名后缀（OrderController/OrderService/...）
        if (containsWord(q, "service")) return "Service";
        if (containsWord(q, "controller")) return "Controller";
        if (containsWord(q, "mapper")) return "Mapper";
        if (containsWord(q, "api")) return "ApiEndpoint";
        if (containsWord(q, "page")) return "Page";
        if (containsWord(q, "method")) return "Method";
        if (containsWord(q, "sql")) return "SqlStatement";
        // 中文「表」最后兜底
        if (q.contains("表")) return "Table";
        return null;
    }

    /** 英文词边界匹配（规避类名后缀误匹配）。 */
    private boolean containsWord(String text, String word) {
        return text.matches("(?s).*\\b" + word + "\\b.*");
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
        String normalizedVersionId = IdUtil.normalizeId(versionId);
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
    private List<EvidenceItem> toImpactEvidenceItems(String projectId, ImpactSubgraph subgraph) {
        if (subgraph == null || subgraph.getNodeIds() == null || subgraph.getNodeIds().isEmpty()) {
            return Collections.emptyList();
        }
        List<GraphNode> nodes = neo4jGraphDao.findNodesByIds(subgraph.getNodeIds());
        List<EvidenceItem> items = new ArrayList<>();
        for (GraphNode n : nodes) {
            items.add(EvidenceItem.fromGraphNode(projectId, n.getId(), n.getNodeName(),
                n.getNodeType(), n.getSourcePath(), n.getDescription()));
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

    /**
     * 解析图谱发布 ID — 从 versionId（scanVersionId）查询对应的 GraphRelease。
     * 用于版本化缓存隔离。查不到时返回 null（不版本化，向后兼容）。
     */
    private String resolveGraphReleaseId(String projectId, String versionId) {
        if (versionId == null || versionId.isBlank()) return null;
        try {
            var release = graphReleaseRepository.findByProjectAndVersion(projectId, versionId);
            return release != null ? release.getId() : null;
        } catch (Exception e) {
            log.debug("Failed to resolve graphReleaseId for version {}: {}", versionId, e.getMessage());
            return null;
        }
    }

    /**
     * 检查 GraphRelease 发布门禁 — 配置关闭时全部放行；配置开启时按版本状态决定是否拒答：
     * <ul>
     *   <li>release == null → 允许回答（向后兼容，无发布版本时不阻断）</li>
     *   <li>PUBLISHED → 允许回答</li>
     *   <li>FAILED → 拒绝（未通过质量门禁）</li>
     *   <li>DRAFT / VALIDATING → 拒绝（图谱正在发布验证中，暂不可用）</li>
     * </ul>
     * 返回 true 表示已拒答（调用方应直接 return）。
     */
    private boolean checkGraphReleaseFailed(String projectId, String versionId,
                                             SseEmitter emitter, String conversationId) {
        // 配置关闭时不做任何检查，全部放行
        if (!graphReleaseConfig.isEnabled()) {
            return false;
        }
        if (versionId == null || versionId.isBlank()) return false;
        try {
            var release = graphReleaseRepository.findByProjectAndVersion(projectId, versionId);
            if (release == null) {
                // 无发布版本 → 向后兼容，允许回答
                return false;
            }
            String status = release.getStatus();
            if ("FAILED".equals(status)) {
                String msg = "该版本未通过质量门禁，无法回答。请重新扫描或选择已发布的版本。";
                sendEvent(emitter, "answer", Map.of("content", msg));
                emitter.complete();
                conversationManager.saveAssistantMessage(conversationId, msg, "[]", 0.0);
                return true;
            }
            if ("DRAFT".equals(status) || "VALIDATING".equals(status)) {
                String msg = "图谱正在发布验证中，暂不可用。";
                sendEvent(emitter, "answer", Map.of("content", msg));
                emitter.complete();
                conversationManager.saveAssistantMessage(conversationId, msg, "[]", 0.0);
                return true;
            }
            // PUBLISHED 及其它状态 → 允许回答
            return false;
        } catch (Exception e) {
            log.debug("Failed to check GraphRelease status for version {}: {}", versionId, e.getMessage());
        }
        return false;
    }

    /**
     * G10: 方案评估 — 加载最新 Solution（含 ImpactResult / 风险 / 成本快照），调用 LLM 从
     * 完整性、风险、成本三个维度评估方案。无 Solution 时返回"暂无方案可评估"。
     * <p>轻量实现：复用 {@link LlmGateway#call} 直接传 system + user prompt，不新建模板。</p>
     */
    private void handleSolutionEvaluation(String projectId, String question, String conversationId,
                                          SseEmitter emitter, Map<String, Long> stageTimings,
                                          long totalStart) {
        long start = System.currentTimeMillis();
        sendEvent(emitter, "thinking", Map.of("stage", "loading_solution"));
        Solution solution;
        try {
            solution = solutionRepository.lambdaQuery()
                .eq(Solution::getProjectId, projectId)
                .orderByDesc(Solution::getCreatedAt)
                .last("LIMIT 1")
                .one();
        } catch (Exception e) {
            log.warn("Failed to load latest solution for evaluation: {}", e.getMessage());
            solution = null;
        }
        if (solution == null) {
            String msg = "暂无方案可评估。请先生成实施方案后再发起评估。";
            sendEvent(emitter, "answer", Map.of("content", msg));
            emitter.complete();
            conversationManager.saveAssistantMessage(conversationId, msg, "[]", 0.0);
            stageTimings.put("solution_evaluation", System.currentTimeMillis() - start);
            return;
        }
        StringBuilder ctx = new StringBuilder();
        ctx.append("## 方案摘要\n")
            .append(solution.getSummary() != null ? solution.getSummary() : "（无）").append("\n\n");
        ctx.append("## 状态\n")
            .append(solution.getStatus() != null ? solution.getStatus() : "UNKNOWN").append("\n\n");
        if (solution.getImpactResultJson() != null && !solution.getImpactResultJson().isBlank()) {
            ctx.append("## 影响分析结果\n").append(solution.getImpactResultJson()).append("\n\n");
        }
        if (solution.getRiskAssessmentJson() != null && !solution.getRiskAssessmentJson().isBlank()) {
            ctx.append("## 风险评估\n").append(solution.getRiskAssessmentJson()).append("\n\n");
        }
        if (solution.getEstimatedCostJson() != null && !solution.getEstimatedCostJson().isBlank()) {
            ctx.append("## 成本估算\n").append(solution.getEstimatedCostJson()).append("\n\n");
        }

        String systemPrompt = "你是资深架构评审专家。请基于给定方案上下文，从完整性、风险、成本三个维度评估该方案，"
            + "指出潜在风险点和遗漏，并给出改进建议。回答用中文，结构清晰。";
        String userPrompt = "用户问题：\n" + question + "\n\n方案上下文：\n" + ctx;

        String answer;
        try {
            answer = llmGateway.call(projectId, systemPrompt, userPrompt, String.class);
        } catch (Exception e) {
            log.warn("Solution evaluation LLM call failed: {}", e.getMessage());
            answer = "方案评估生成失败，请稍后重试。";
        }
        streamSimpleAnswer(emitter, conversationId, answer, stageTimings,
            "solution_evaluation", start, totalStart);
    }

    /**
     * G10: 问题诊断 — 复用现有 GraphRAG 检索（混合召回 + 图谱扩展）收集上下文，
     * 结合用户问题描述调用 LLM 诊断根因与传播路径。
     * <p>轻量实现：复用 {@link LlmGateway#call} 直接传诊断 prompt。</p>
     */
    private void handleDiagnosis(String projectId, String versionId, String question,
                                  String conversationId, SseEmitter emitter,
                                  Map<String, Long> stageTimings, long totalStart) {
        long start = System.currentTimeMillis();
        sendEvent(emitter, "thinking", Map.of("stage", "diagnosing"));
        // 复用现有检索能力收集上下文
        String graphReleaseId = resolveGraphReleaseId(projectId, versionId);
        List<VectorDocument> docs = Collections.emptyList();
        try {
            docs = hybridRetrievalService.retrieve(projectId, versionId, question,
                List.of(question), 10, graphReleaseId, null);
            docs = reRankingService.reRank(question, docs, 5);
        } catch (Exception e) {
            log.warn("Diagnosis retrieval failed: {}", e.getMessage());
        }
        List<GraphNode> graphNodes = expandGraph(projectId, versionId, question, QueryIntent.DIAGNOSIS);
        String retrievalContext = buildRetrievalContext(docs, graphNodes);

        String systemPrompt = "你是根因诊断专家。请结合图谱路径与问题描述，分析问题的根本原因与传播路径，"
            + "定位关键节点，并给出可执行的修复方向。回答用中文，结构清晰。";
        String userPrompt = "问题描述：\n" + question + "\n\n图谱与文档证据：\n" + retrievalContext;

        String answer;
        try {
            answer = llmGateway.call(projectId, systemPrompt, userPrompt, String.class);
        } catch (Exception e) {
            log.warn("Diagnosis LLM call failed: {}", e.getMessage());
            answer = "问题诊断生成失败，请稍后重试。";
        }
        streamSimpleAnswer(emitter, conversationId, answer, stageTimings,
            "diagnosis", start, totalStart);
    }

    /**
     * 流式输出轻量分支的简单答案（非 GraphRAG 链路复用）。
     * <p>逐字符 token + complete 事件 + 持久化助手消息。</p>
     */
    private void streamSimpleAnswer(SseEmitter emitter, String conversationId, String answer,
                                     Map<String, Long> stageTimings, String stageKey,
                                     long stageStart, long totalStart) {
        if (answer == null) answer = "";
        for (int i = 0; i < answer.length(); i++) {
            sendEvent(emitter, "token", Map.of("text", String.valueOf(answer.charAt(i))));
        }
        conversationManager.saveAssistantMessage(conversationId, answer, "[]", 0.7);
        conversationManager.updateConversationTitle(conversationId);
        stageTimings.put(stageKey, System.currentTimeMillis() - stageStart);
        Map<String, Object> completeData = new HashMap<>();
        completeData.put("conversationId", conversationId);
        completeData.put("messageId", IdUtil.fastUUID());
        completeData.put("confidence", 0.7);
        completeData.put("answer", answer);
        completeData.put("latencyMs", System.currentTimeMillis() - totalStart);
        completeData.put("stageTimings", stageTimings);
        sendEvent(emitter, "complete", completeData);
        emitter.complete();
    }

    /**
     * 解析证据 JSON 为列表。
     */
    private List<EvidenceItem> parseEvidences(String evidencesJson) {
        if (evidencesJson == null || evidencesJson.isBlank() || "[]".equals(evidencesJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(evidencesJson,
                objectMapper.getTypeFactory().constructCollectionType(List.class, EvidenceItem.class));
        } catch (Exception e) {
            log.debug("Failed to parse cached evidences JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 计算路径置信度 — 基于证据类型和数量估算图谱路径完整度。
     * <p>有 GRAPH_NODE / GRAPH_RAG 类型证据时路径置信度更高；
     * 纯 DOC_CHUNK 证据时路径置信度较低（无图谱路径支撑）。</p>
     */
    private double computePathConfidence(List<EvidenceItem> evidences) {
        if (evidences == null || evidences.isEmpty()) return 0.0;
        long graphEvidences = evidences.stream()
            .filter(e -> "GRAPH_NODE".equals(e.getSourceKind()) || "GRAPH_RAG".equals(e.getSourceKind()))
            .count();
        long docEvidences = evidences.stream()
            .filter(e -> "DOC_CHUNK".equals(e.getSourceKind()))
            .count();
        double graphScore = Math.min(1.0, graphEvidences / 3.0);
        double docScore = Math.min(0.5, docEvidences / 5.0 * 0.5);
        return Math.min(1.0, graphScore * 0.7 + docScore * 0.3);
    }

    /**
     * S4-T1: 闲聊型意图白名单 — 跳过强制证据回填，避免命中率断崖。
     * null 意图视为闲聊，跳过校验；其他意图均需证据引用。
     */
    private boolean isChitchatIntent(QueryIntent intent) {
        return intent == null;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                .name(eventName)
                .data(objectMapper.writeValueAsString(data)));
        } catch (org.springframework.web.context.request.async.AsyncRequestNotUsableException e) {
            log.debug("SSE connection already closed by client, event={}", eventName);
        } catch (IOException e) {
            log.debug("Failed to send SSE event, connection may be closed, event={}", eventName);
        }
    }

    /**
     * 同步问答 — 评测 / 门禁场景使用。
     * <p>
     * 内部复用 {@link #answerStream}，通过 {@link CollectingSseEmitter} 拦截 SSE 事件，
     * 阻塞等待 {@code complete} 事件后返回答案与证据。超时或异常返回 {@code error=true} 的空结果。
     * </p>
     *
     * @param projectId 项目 ID
     * @param versionId 扫描版本 ID
     * @param question  问题
     * @return 同步问答结果（answer / evidences / confidence / error）
     */
    public QaAnswerResult answer(String projectId, String versionId, String question) {
        CollectingSseEmitter emitter = new CollectingSseEmitter();
        // 评测用独立 conversationId（null → 自动创建），避免污染线上对话历史
        try {
            qaExecutor.execute(() -> {
                try {
                    answerStream(projectId, versionId, question, null, emitter);
                } catch (Exception e) {
                    emitter.fail(e);
                }
            });
        } catch (Exception e) {
            return new QaAnswerResult("", List.of(), 0.0, true);
        }
        try {
            if (!emitter.latch.await(120, TimeUnit.SECONDS)) {
                log.warn("EnhancedQaAgent#answer: timed out for question='{}'", question);
                return new QaAnswerResult("", List.of(), 0.0, true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new QaAnswerResult("", List.of(), 0.0, true);
        }
        if (emitter.errorRef.get() != null) {
            return new QaAnswerResult("", List.of(), 0.0, true);
        }
        String answer = emitter.answerRef.get() != null ? emitter.answerRef.get() : "";
        List<EvidenceItem> evidences = emitter.evidencesRef.get() != null
            ? emitter.evidencesRef.get() : List.of();
        double confidence = emitter.confidenceRef.get() != null ? emitter.confidenceRef.get() : 0.0;
        return new QaAnswerResult(answer, evidences, confidence, false);
    }

    /**
     * 收集型 SseEmitter — 拦截 {@code send} 不向真实客户端推送，仅解析 {@code complete}/{@code error}
     * 事件并填充结果引用。同步评测场景下 handler 未初始化，需重写 {@code complete*} 避免空指针。
     */
    private final class CollectingSseEmitter extends SseEmitter {

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> answerRef = new AtomicReference<>();
        final AtomicReference<List<EvidenceItem>> evidencesRef = new AtomicReference<>();
        final AtomicReference<Double> confidenceRef = new AtomicReference<>();
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        private String currentEvent;

        CollectingSseEmitter() {
            super(120_000L);
        }

        /**
         * 拦截 {@code sendEvent} 通过 {@code emitter.send(SseEmitter.event()...)} 发出的事件，
         * 解析事件名与 data 负载，不向真实 handler 委托（同步模式下无 SSE 客户端）。
         * <p>
         * 必须重写 {@code send(SseEventBuilder)} 而非 {@code send(Object, MediaType)}：
         * 后者由 {@code SseEmitter.send(SseEventBuilder)} 通过 {@code super.send} 调用，
         * {@code INVOKESPECIAL} 会绕过子类覆写。
         * </p>
         */
        @Override
        public synchronized void send(SseEventBuilder builder) {
            if (builder == null) {
                return;
            }
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                Object data = item.getData();
                if (data instanceof String s) {
                    if (s.startsWith("event:")) {
                        currentEvent = s.substring("event:".length()).trim();
                    } else if (s.startsWith("data:")) {
                        handleData(currentEvent, s.substring("data:".length()).trim());
                    }
                }
            }
        }

        private void handleData(String event, String json) {
            if (event == null) {
                return;
            }
            try {
                var node = objectMapper.readTree(json);
                if ("complete".equals(event)) {
                    if (node.has("answer") && !node.get("answer").isNull()) {
                        answerRef.set(node.get("answer").asText(""));
                    }
                    if (node.has("confidence") && node.get("confidence").isNumber()) {
                        confidenceRef.set(node.get("confidence").asDouble());
                    }
                    if (node.has("evidences")) {
                        evidencesRef.set(objectMapper.convertValue(
                            node.get("evidences"),
                            new com.fasterxml.jackson.core.type.TypeReference<List<EvidenceItem>>() {}));
                    }
                    latch.countDown();
                } else if ("error".equals(event)) {
                    String msg = node.has("message") ? node.get("message").asText() : "qa stream error";
                    errorRef.set(new RuntimeException(msg));
                    latch.countDown();
                }
            } catch (Exception ignored) {
                // 单条事件解析失败不影响整体，继续等待 complete
            }
        }

        void fail(Throwable t) {
            errorRef.set(t);
            latch.countDown();
        }

        @Override
        public void complete() {
            // 同步模式：complete 由 complete 事件触发 latch，此处不向 handler 委托
        }

        @Override
        public void completeWithError(Throwable failure) {
            errorRef.set(failure);
            latch.countDown();
        }
    }

    /**
     * GraphRAG 并发结果容器（planning + execution 在同一线程内完成）。
     */
    private record GraphRagResult(GraphRagPlan plan, List<GraphRagEvidenceCard> cards) {}
}
