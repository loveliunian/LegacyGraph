package io.github.legacygraph.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.rag.GraphRagPlan;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.llm.LlmCallException;
import io.github.legacygraph.llm.LlmGateway;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GraphRAG 查询规划器 Agent。
 * <p>
 * 职责：接收用户自然语言问题 + 相关 {@link KnowledgeClaim} 列表，
 * 通过 LLM 生成多步 GraphRAG 查询计划（{@link GraphRagPlan}）。
 * 计划从 Feature / API / Table / BusinessRule 等 Claim 维度出发，
 * 规划逐层查询路径，最终汇总为可执行的结构化查询方案。
 * </p>
 *
 * <p>调用流程：</p>
 * <ol>
 *   <li>将 KnowledgeClaim 序列化为 JSON 摘要</li>
 *   <li>构建 {@link AgentEnvelope}（含证据策略）</li>
 *   <li>通过 {@link LlmGateway#callWithEnvelope} 调用 'graph-rag-planner' 模板</li>
 *   <li>LLM 输出 JSON，反序列化为 {@link GraphRagPlan}</li>
 * </ol>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 *   GraphRagPlan plan = plannerAgent.plan(
 *       projectId,
 *       "订单创建涉及哪些表和 API？它们之间有什么依赖关系？",
 *       relevantClaims
 *   );
 *   // plan.subQuestions     → [SubQuestion(targetType=Feature, query=...), ...]
 *   // plan.claimQueries     → [ClaimQuery(subjectType=ApiEndpoint, ...), ...]
 *   // plan.pathQueries      → [PathQuery(startNodeType=Feature, ...), ...]
 *   // plan.requiredEvidenceTypes → ["API_DOC", "TABLE_SCHEMA"]
 * }</pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphRagPlannerAgent {

    private static final String TEMPLATE_NAME = "graph-rag-planner";

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    /**
     * 规划 GraphRAG 多步查询路径。
     *
     * @param projectId       项目 ID
     * @param question        用户自然语言问题
     * @param relevantClaims  与问题相关的 KnowledgeClaim 列表（可为空）
     * @return GraphRagPlan 查询计划，包含子问题、Claim 过滤、路径查询与所需证据类型
     */
    public GraphRagPlan plan(String projectId, String question,
                             List<KnowledgeClaim> relevantClaims) {
        // 1. 防御性校验
        if (question == null || question.isBlank()) {
            log.warn("GraphRagPlannerAgent: empty question for projectId={}", projectId);
            return emptyPlan(question);
        }

        // 2. 构建 AgentEnvelope（含证据目录与策略）
        AgentEnvelope<PlanInput> envelope = buildEnvelope(projectId, question, relevantClaims);

        // 3. 构建 Prompt 变量
        Map<String, String> variables = buildVariables(question, relevantClaims);

        // 4. 调用 LLM
        GraphRagPlan plan;
        try {
            plan = llmGateway.callWithEnvelope(envelope, TEMPLATE_NAME,
                    variables, GraphRagPlan.class);
        } catch (LlmCallException ex) {
            // LLM 调用失败或输出 schema 校验失败 → 降级为人工复核计划
            log.warn("GraphRagPlannerAgent LLM call failed for projectId={}: {}",
                    projectId, ex.getMessage());
            plan = fallbackPlan(question, ex.isNeedsReview());
        }

        // 5. 后处理：补全 question 字段
        if (plan != null) {
            plan.setQuestion(question);
            log.info("GraphRagPlannerAgent plan generated: subQuestions={}, claimQueries={}, pathQueries={}",
                    plan.getSubQuestions() != null ? plan.getSubQuestions().size() : 0,
                    plan.getClaimQueries() != null ? plan.getClaimQueries().size() : 0,
                    plan.getPathQueries() != null ? plan.getPathQueries().size() : 0);
        } else {
            log.warn("GraphRagPlannerAgent returned null plan for projectId={}", projectId);
            plan = emptyPlan(question);
        }

        return plan;
    }

    /**
     * 构建 AgentEnvelope。
     * <p>声明证据策略为 PREFER 模式：优先使用已有 Claim 证据，允许 AI 推断补充。</p>
     */
    private AgentEnvelope<PlanInput> buildEnvelope(String projectId, String question,
                                                    List<KnowledgeClaim> relevantClaims) {
        PlanInput input = new PlanInput(question, relevantClaims);

        // 提取可用证据 ID
        List<String> evidenceIds = relevantClaims != null
                ? relevantClaims.stream()
                .filter(c -> c.getEvidenceIds() != null && !c.getEvidenceIds().isEmpty())
                .flatMap(c -> parseJsonList(c.getEvidenceIds()).stream())
                .distinct()
                .collect(Collectors.toList())
                : List.of();

        return AgentEnvelope.<PlanInput>builder()
                .projectId(projectId)
                .agentType("GraphRagPlannerAgent")
                .schemaVersion("1.0")
                .input(input)
                .evidenceCatalog(AgentEnvelope.EvidenceCatalog.builder()
                        .usedEvidenceIds(evidenceIds)
                        .requiredEvidenceTypes(List.of(
                                "CLAIM",          // 知识断言
                                "SCHEMA",         // 数据库/API Schema
                                "GRAPH_STATS"     // 图谱统计信息
                        ))
                        .summary("GraphRAG 查询规划：基于 " +
                                (relevantClaims != null ? relevantClaims.size() : 0) +
                                " 条相关 Claim 生成多步查询路径")
                        .build())
                .policy(AgentEnvelope.RequiredEvidencePolicy.builder()
                        .mode("PREFER")
                        .failOnMissing(false)
                        .allowAiInference(true)
                        .minConfidence(0.5)
                        .description("优先使用已有 Claim 证据，允许 AI 补充推断")
                        .build())
                .build();
    }

    /**
     * 构建 Prompt 模板变量。
     * <p>将 question 与 KnowledgeClaim 摘要注入模板占位符。</p>
     */
    private Map<String, String> buildVariables(String question,
                                               List<KnowledgeClaim> relevantClaims) {
        Map<String, String> vars = new HashMap<>();
        vars.put("question", question);
        vars.put("claims", claimsToJson(relevantClaims));
        vars.put("claimCount", String.valueOf(relevantClaims != null ? relevantClaims.size() : 0));
        return vars;
    }

    /**
     * 将 KnowledgeClaim 列表序列化为 LLM 可读的 JSON 摘要。
     * <p>仅保留规划所需的关键字段，避免 context window 溢出。</p>
     */
    private String claimsToJson(List<KnowledgeClaim> claims) {
        if (claims == null || claims.isEmpty()) {
            return "[]";
        }
        try {
            List<ClaimSummary> summaries = claims.stream()
                    .map(c -> new ClaimSummary(
                            c.getSubjectType(),
                            c.getSubjectKey(),
                            c.getPredicate(),
                            c.getObjectType(),
                            c.getObjectKey(),
                            c.getObjectValue(),
                            c.getSourceType(),
                            c.getStatus(),
                            c.getConfidence() != null ? c.getConfidence().doubleValue() : 0.0))
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(summaries);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize claims to JSON", e);
            return "[]";
        }
    }

    /**
     * 解析 JSON 数组字符串为 List。
     * <p>用于从 evidenceIds 等 JSONB 字段提取 ID 列表。</p>
     */
    @SuppressWarnings("unchecked")
    private List<String> parseJsonList(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty() || "[]".equals(jsonStr)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(jsonStr, List.class);
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse JSON list: {}", jsonStr);
            return List.of();
        }
    }

    /**
     * 降级计划：LLM 调用失败时返回人工复核标记。
     */
    private GraphRagPlan fallbackPlan(String question, boolean needsReview) {
        GraphRagPlan plan = new GraphRagPlan();
        plan.setQuestion(question);
        plan.setNeedsHumanReview(true);
        plan.setReasoning("LLM 调用失败，计划需要人工补充。");
        plan.setRequiredEvidenceTypes(List.of("MANUAL_REVIEW"));
        return plan;
    }

    /**
     * 空计划：无可用 Claim 或输入为空时的兜底返回。
     */
    private GraphRagPlan emptyPlan(String question) {
        GraphRagPlan plan = new GraphRagPlan();
        plan.setQuestion(question != null ? question : "");
        plan.setReasoning("无可用 Claim 或输入为空，无法生成查询计划。");
        plan.setRequiredEvidenceTypes(List.of("MANUAL_REVIEW"));
        plan.setNeedsHumanReview(true);
        return plan;
    }

    // ==================== 内部 DTO ====================

    /**
     * Plan 输入载体 — 作为 AgentEnvelope 的泛型参数。
     */
    @Data
    public static class PlanInput {
        private final String question;
        private final List<KnowledgeClaim> claims;

        public PlanInput(String question, List<KnowledgeClaim> claims) {
            this.question = question;
            this.claims = claims;
        }
    }

    /**
     * Claim 摘要 — 发往 LLM 的轻量 Claim 表示。
     * <p>仅包含规划所需关键字段，避免全量实体 JSON 导致 context 超限。</p>
     */
    @Data
    public static class ClaimSummary {
        private final String subjectType;
        private final String subjectKey;
        private final String predicate;
        private final String objectType;
        private final String objectKey;
        private final String objectValue;
        private final String sourceType;
        private final String status;
        private final double confidence;

        public ClaimSummary(String subjectType, String subjectKey, String predicate,
                            String objectType, String objectKey, String objectValue,
                            String sourceType, String status, double confidence) {
            this.subjectType = subjectType;
            this.subjectKey = subjectKey;
            this.predicate = predicate;
            this.objectType = objectType;
            this.objectKey = objectKey;
            this.objectValue = objectValue;
            this.sourceType = sourceType;
            this.status = status;
            this.confidence = confidence;
        }
    }
}
