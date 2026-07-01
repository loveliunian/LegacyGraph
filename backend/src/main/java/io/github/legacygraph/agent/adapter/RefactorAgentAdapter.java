package io.github.legacygraph.agent.adapter;

import io.github.legacygraph.agent.RefactorAgent;
import io.github.legacygraph.dto.RefactorSuggestion;
import io.github.legacygraph.dto.graph.PatchPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RefactorAgent → PatchPlan 适配器（增强版2）。
 * <p>
 * 复用现有 {@link RefactorAgent}（返回建议 DTO），把 {@link RefactorSuggestion} 的
 * refactoredSkeleton 与拆分建议转换为 PatchPlan 草案。因 RefactorAgent 只给出重构骨架
 * 而非可直接应用的 diff，故生成的 PatchPlan 默认 manualReviewNeeded=true，交人工补全 diff。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefactorAgentAdapter {

    private final RefactorAgent refactorAgent;

    /**
     * 生成重构 PatchPlan 草案。
     *
     * @param taskId    变更任务ID
     * @param projectId 项目ID
     * @param target    重构目标（类/方法）
     * @param smellType 异味类型
     * @param code      目标代码
     * @param filePath  目标文件路径（用于 patch 落点）
     * @param evidenceIds 证据ID（来自 impacted subgraph / featureslice）
     */
    public PatchPlan toPatchPlan(String taskId, String projectId, String target,
                                 String smellType, String code, String filePath,
                                 List<String> evidenceIds) {
        RefactorSuggestion suggestion = refactorAgent.suggest(projectId, target, smellType, code);

        PatchPlan.Patch patch = PatchPlan.Patch.builder()
                .filePath(filePath)
                .changeType("MODIFY")
                // RefactorAgent 输出骨架而非 diff，作为草案文本承载，人工/后续 Agent 转 diff
                .patchText(suggestion != null ? suggestion.getRefactoredSkeleton() : null)
                .evidenceIds(evidenceIds)
                .build();

        PatchPlan.ImpactedFile impacted = PatchPlan.ImpactedFile.builder()
                .path(filePath)
                .reason(suggestion != null ? suggestion.getSummary() : "重构建议")
                .build();

        String risk = suggestion != null && suggestion.getRisk() != null ? suggestion.getRisk() : "MEDIUM";

        log.info("RefactorAgentAdapter produced PatchPlan draft for task={}, target={}", taskId, target);

        return PatchPlan.builder()
                .taskId(taskId)
                .taskType("REFACTOR")
                .riskLevel(normalizeRisk(risk))
                .impactedFiles(List.of(impacted))
                .patches(List.of(patch))
                .validationGates(List.of("STATIC", "UNIT"))
                .manualReviewNeeded(true)
                .generatedBy("RefactorAgent")
                .build();
    }

    private String normalizeRisk(String risk) {
        String r = risk != null ? risk.toUpperCase() : "MEDIUM";
        if (r.contains("HIGH") || r.contains("高")) return "HIGH";
        if (r.contains("LOW") || r.contains("低")) return "LOW";
        return "MEDIUM";
    }
}
