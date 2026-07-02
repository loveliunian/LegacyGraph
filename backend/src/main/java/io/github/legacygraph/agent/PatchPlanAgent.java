package io.github.legacygraph.agent;

import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.graph.PatchPlan;
import io.github.legacygraph.llm.LlmCallException;
import io.github.legacygraph.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PatchPlanAgent - BUGFIX 补丁计划生成（可用版）。
 * <p>Phase 3-1: 新增 {@link #generate(AgentEnvelope)} 重载，支持证据合约调用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatchPlanAgent {

    private final LlmGateway llmGateway;

    /**
     * 生成 BUGFIX 补丁计划（AgentEnvelope 合约版本 — Phase 3-1）。
     * <p>调用前需通过 RequiredEvidencePolicy 校验证据完整性。</p>
     */
    public PatchPlan generate(AgentEnvelope<PatchPlanInput> envelope) {
        PatchPlanInput input = envelope.getInput();
        if (input == null) {
            log.warn("PatchPlanAgent: empty input in envelope {}", envelope.getContractId());
            return null;
        }
        Map<String, String> variables = buildVariables(input);
        PatchPlan plan;
        try {
            plan = llmGateway.callWithEnvelope(envelope, "patch-plan", variables, PatchPlan.class);
        } catch (LlmCallException ex) {
            if (!ex.isNeedsReview()) {
                throw ex;
            }
            plan = manualReviewPlan(input, ex.getMessage());
        }
        if (plan != null) {
            plan.setTaskId(input.taskId);
            plan.setTaskType("BUGFIX");
            plan.setGeneratedBy("PatchPlanAgent");
        }
        log.info("PatchPlanAgent (envelope) generated plan for task {}: patches={}",
                input.taskId, plan != null && plan.getPatches() != null ? plan.getPatches().size() : 0);
        return plan;
    }

    private PatchPlan manualReviewPlan(PatchPlanInput input, String reason) {
        PatchPlan plan = new PatchPlan();
        plan.setTaskId(input.taskId);
        plan.setTaskType("BUGFIX");
        plan.setRiskLevel("REVIEW");
        plan.setManualReviewNeeded(true);
        plan.setGeneratedBy("PatchPlanAgent");
        plan.setValidationGates(List.of("MANUAL_REVIEW"));
        log.warn("PatchPlanAgent requires manual review for task {}: {}", input.taskId, reason);
        return plan;
    }

    /**
     * 生成 BUGFIX 补丁计划（兼容旧 API — 四参默认合约）。
     */
    public PatchPlan generate(String projectId, String taskId, String title,
                              String inputIssue, ImpactSubgraph subgraph,
                              String evidenceSummary, String failingTests) {
        Map<String, String> variables = new HashMap<>();
        variables.put("title", title != null ? title : "");
        variables.put("changeTarget", subgraph != null ? nz(subgraph.getTargetName()) : "");
        variables.put("inputIssue", inputIssue != null ? inputIssue : "");
        variables.put("dependencySummary", subgraph != null ? nz(subgraph.getDependencySummary()) : "");
        variables.put("impactedFiles", subgraph != null ? joinFiles(subgraph.getImpactedFiles()) : "（无）");
        variables.put("evidenceSummary", evidenceSummary != null ? evidenceSummary : "（无）");
        variables.put("failingTests", failingTests != null ? failingTests : "（无）");

        PatchPlan plan = llmGateway.callWithTemplate(projectId, "patch-plan", variables, PatchPlan.class);
        if (plan != null) {
            plan.setTaskId(taskId);
            plan.setTaskType("BUGFIX");
            plan.setGeneratedBy("PatchPlanAgent");
        }
        log.info("PatchPlanAgent generated plan for task {}: patches={}", taskId,
                plan != null && plan.getPatches() != null ? plan.getPatches().size() : 0);
        return plan;
    }

    private Map<String, String> buildVariables(PatchPlanInput input) {
        Map<String, String> vars = new HashMap<>();
        vars.put("title", nz(input.title));
        vars.put("changeTarget", nz(input.changeTarget));
        vars.put("inputIssue", nz(input.inputIssue));
        vars.put("dependencySummary", nz(input.dependencySummary));
        vars.put("impactedFiles", joinFiles(input.impactedFiles));
        vars.put("evidenceSummary", nz(input.evidenceSummary));
        vars.put("failingTests", nz(input.failingTests));
        return vars;
    }

    /** PatchPlan 输入 DTO */
    @lombok.Data
    @lombok.Builder
    public static class PatchPlanInput {
        private String taskId;
        private String title;
        private String changeTarget;
        private String inputIssue;
        private String dependencySummary;
        private List<String> impactedFiles;
        private String evidenceSummary;
        private String failingTests;
    }

    private String joinFiles(List<String> files) {
        if (files == null || files.isEmpty()) return "（无）";
        return String.join("\n", files.stream().map(f -> "- " + f).toList());
    }

    private String nz(String s) {
        return s != null ? s : "";
    }
}
