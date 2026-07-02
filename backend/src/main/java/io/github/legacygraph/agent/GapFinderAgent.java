package io.github.legacygraph.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.entity.Evidence;
import io.github.legacygraph.entity.GapTask;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.llm.LlmGateway;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GapFinderAgent — 知识缺口分析 Agent。
 * <p>
 * 基于 GapTask + 相关 Claim + 相关 Evidence 输入，使用 LLM 生成：
 * <ul>
 *   <li>explanation — 中文缺口解释</li>
 *   <li>priorityScore — 优先级评分 0~1</li>
 *   <li>suggestedActions — 可执行的补证动作</li>
 *   <li>requiredEvidenceTypes — 补证需要的证据类型</li>
 *   <li>needsHumanReview — 是否需要人工审核</li>
 * </ul>
 * <p>
 * 必须通过 AgentEnvelope + callWithEnvelope 调用（架构门禁要求）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GapFinderAgent {

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    private static final String TEMPLATE_NAME = "gap-finder";

    /**
     * GapFinder 输出 DTO。
     */
    @Data
    public static class GapAdvice {
        private String explanation;
        private BigDecimal priorityScore;
        private List<String> suggestedActions = new ArrayList<>();
        private List<String> requiredEvidenceTypes = new ArrayList<>();
        private boolean needsHumanReview;
    }

    /**
     * 分析单个 GapTask，生成补证建议。
     *
     * @param projectId 项目ID
     * @param gap       缺口任务
     * @param claims    相关 Claim 列表
     * @param evidence  相关 Evidence 列表
     * @return GapAdvice 补证建议
     */
    public GapAdvice advise(String projectId, GapTask gap,
                            List<KnowledgeClaim> claims, List<Evidence> evidence) {
        if (gap == null) {
            log.warn("GapFinderAgent.advise called with null gap");
            return emptyAdvice();
        }

        // 构建 AgentEnvelope
        AgentEnvelope<GapInput> envelope = AgentEnvelope.<GapInput>builder()
                .projectId(projectId)
                .agentType("GapFinderAgent")
                .schemaVersion("1.0")
                .input(new GapInput(gap, claims, evidence))
                .evidenceCatalog(AgentEnvelope.EvidenceCatalog.builder()
                        .usedEvidenceIds(evidence != null
                                ? evidence.stream().map(Evidence::getId).collect(Collectors.toList())
                                : List.of())
                        .requiredEvidenceTypes(List.of("claim", "evidence"))
                        .build())
                .policy(AgentEnvelope.RequiredEvidencePolicy.builder()
                        .mode("ALLOW_EMPTY_WITH_REVIEW")
                        .failOnMissing(false)
                        .allowAiInference(true)
                        .build())
                .build();

        // 构建 Prompt 变量
        Map<String, String> variables = new HashMap<>();
        variables.put("gapTask", gapToJson(gap));
        variables.put("claims", claimsToJson(claims));
        variables.put("evidence", evidenceToJson(evidence));

        // 调用 LLM（使用 AgentEnvelope 模式）
        try {
            GapAdviceResponse response = llmGateway.callWithEnvelope(
                    envelope, TEMPLATE_NAME, variables, GapAdviceResponse.class);

            if (response == null) {
                log.warn("GapFinderAgent returned null for gapId={}", gap.getId());
                return emptyAdvice();
            }

            GapAdvice advice = new GapAdvice();
            advice.setExplanation(response.getExplanation() != null ? response.getExplanation() : "");
            advice.setPriorityScore(response.getPriorityScore());
            if (response.getSuggestedActions() != null) {
                advice.setSuggestedActions(response.getSuggestedActions());
            }
            if (response.getRequiredEvidenceTypes() != null) {
                advice.setRequiredEvidenceTypes(response.getRequiredEvidenceTypes());
            }
            advice.setNeedsHumanReview(response.isNeedsHumanReview());

            return advice;
        } catch (Exception e) {
            log.warn("GapFinderAgent call failed for gapId={}: {}", gap.getId(), e.getMessage());
            return emptyAdvice();
        }
    }

    /**
     * 批量分析多个 GapTask（逐个调用 LLM）。
     */
    public Map<String, GapAdvice> adviseBatch(String projectId, List<GapTask> gaps,
                                               List<KnowledgeClaim> claims, List<Evidence> evidence) {
        Map<String, GapAdvice> results = new HashMap<>();
        if (gaps == null || gaps.isEmpty()) {
            return results;
        }

        for (GapTask gap : gaps) {
            // 为每个 gap 筛选相关 Claim
            List<String> relatedClaimIds = parseJsonList(gap.getRelatedClaimIds());
            List<KnowledgeClaim> relatedClaims = claims != null
                    ? claims.stream()
                    .filter(c -> relatedClaimIds.contains(c.getId()))
                    .collect(Collectors.toList())
                    : List.of();

            try {
                GapAdvice advice = advise(projectId, gap, relatedClaims, evidence);
                results.put(gap.getId(), advice);
            } catch (Exception e) {
                log.warn("GapFinderAgent batch advice failed for gapId={}: {}", gap.getId(), e.getMessage());
                results.put(gap.getId(), emptyAdvice());
            }
        }

        return results;
    }

    private GapAdvice emptyAdvice() {
        GapAdvice advice = new GapAdvice();
        advice.setExplanation("LLM 调用失败，建议人工处理");
        advice.setPriorityScore(BigDecimal.valueOf(0.3));
        advice.setSuggestedActions(List.of("人工确认"));
        advice.setRequiredEvidenceTypes(List.of("human_review"));
        advice.setNeedsHumanReview(true);
        return advice;
    }

    private String gapToJson(GapTask gap) {
        if (gap == null) return "{}";
        try {
            return objectMapper.writeValueAsString(new GapSummary(gap));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String claimsToJson(List<KnowledgeClaim> claims) {
        if (claims == null || claims.isEmpty()) return "[]";
        try {
            List<ClaimSummary> summaries = claims.stream()
                    .map(c -> new ClaimSummary(
                            c.getSubjectType(), c.getSubjectKey(),
                            c.getPredicate(), c.getObjectType(), c.getObjectKey(),
                            c.getSourceType(), c.getStatus()))
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(summaries);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String evidenceToJson(List<Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) return "[]";
        try {
            List<Object> summaries = evidence.stream()
                    .map(e -> Map.of(
                            "id", e.getId() != null ? e.getId() : "",
                            "type", e.getEvidenceType() != null ? e.getEvidenceType() : "",
                            "sourcePath", e.getSourcePath() != null ? e.getSourcePath() : "",
                            "summary", e.getSummary() != null ? e.getSummary() : ""))
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(summaries);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonList(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty() || "[]".equals(jsonStr)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(jsonStr, List.class);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    // ──────────── Inner types for LLM input/output ────────────

    @Data
    public static class GapInput {
        private final GapTask gap;
        private final List<KnowledgeClaim> claims;
        private final List<Evidence> evidence;

        public GapInput(GapTask gap, List<KnowledgeClaim> claims, List<Evidence> evidence) {
            this.gap = gap;
            this.claims = claims;
            this.evidence = evidence;
        }
    }

    @Data
    public static class GapAdviceResponse {
        private String explanation;
        private BigDecimal priorityScore;
        private List<String> suggestedActions;
        private List<String> requiredEvidenceTypes;
        private boolean needsHumanReview;
    }

    @Data
    static class GapSummary {
        private final String id;
        private final String gapType;
        private final String title;
        private final String description;
        private final String severity;
        private final String subjectType;
        private final String subjectKey;

        GapSummary(GapTask gap) {
            this.id = gap.getId();
            this.gapType = gap.getGapType();
            this.title = gap.getTitle();
            this.description = gap.getDescription();
            this.severity = gap.getSeverity();
            this.subjectType = gap.getSubjectType();
            this.subjectKey = gap.getSubjectKey();
        }
    }

    record ClaimSummary(String subjectType, String subjectKey, String predicate,
                        String objectType, String objectKey, String sourceType, String status) {}
}
