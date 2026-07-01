package io.github.legacygraph.agent;

import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.graph.PatchPlan;
import io.github.legacygraph.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PatchPlanAgent - BUGFIX 补丁计划生成（可用版）。
 * <p>
 * 见 doc §现有 Agent 到 PatchPlan 的差距：Refactor/Migration 已由 Adapter 覆盖，
 * BUGFIX 需要一个以 {@link ImpactSubgraph} + Evidence + 失败测试为输入、
 * 直接输出 {@link PatchPlan} JSON 契约的生成器。
 * </p>
 * <p>输出仍须经 {@code PatchPlanValidator} 三类校验后才落盘。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatchPlanAgent {

    private final LlmGateway llmGateway;

    /**
     * 生成 BUGFIX 补丁计划。
     *
     * @param projectId     项目ID
     * @param taskId        变更任务ID
     * @param title         任务标题
     * @param inputIssue    问题描述
     * @param subgraph      影响子图（提供范围白名单与依赖摘要）
     * @param evidenceSummary 证据摘要文本
     * @param failingTests  失败测试 / 复现摘要
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

    private String joinFiles(List<String> files) {
        if (files == null || files.isEmpty()) return "（无）";
        return String.join("\n", files.stream().map(f -> "- " + f).toList());
    }

    private String nz(String s) {
        return s != null ? s : "";
    }
}
