package io.github.legacygraph.agent.adapter;

import io.github.legacygraph.agent.AddColumnPatchAgent;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.graph.PatchPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ADD_COLUMN 补丁适配器 — 包装 {@link AddColumnPatchAgent}，产出 {@link PatchPlan}（taskType=ADD_COLUMN）。
 * <p>
 * 仿 {@code RefactorAgentAdapter}，不实现共享接口（项目无 PatchAgentAdapter 契约）。
 * LLM 不可用（Agent 返回 null）时降级为最小骨架计划，标 manualReviewNeeded=true。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AddColumnPatchAgentAdapter {

    private final AddColumnPatchAgent addColumnPatchAgent;

    /**
     * @param taskId      变更任务ID
     * @param projectId   项目ID
     * @param tableName   目标表名
     * @param columnName  新字段名
     * @param columnType  字段类型（如 VARCHAR(32)）
     * @param nullable    是否可空
     * @param defaultValue 默认值（可空）
     * @param subgraph    影响子图（提供 impactedFiles 白名单 + 摘要）
     * @param evidenceIds 证据ID列表
     */
    public PatchPlan toPatchPlan(String taskId, String projectId,
                                 String tableName, String columnName, String columnType,
                                 boolean nullable, String defaultValue,
                                 ImpactSubgraph subgraph, List<String> evidenceIds) {
        String impactedFilesSummary = "";
        if (subgraph != null && subgraph.getImpactedFiles() != null) {
            impactedFilesSummary = String.join(", ", subgraph.getImpactedFiles());
        }

        AddColumnPatchAgent.AddColumnInput input = AddColumnPatchAgent.AddColumnInput.builder()
                .tableName(tableName)
                .columnName(columnName)
                .columnType(columnType)
                .nullable(nullable)
                .defaultValue(defaultValue)
                .impactedFilesSummary(impactedFilesSummary)
                .build();
        AgentEnvelope<AddColumnPatchAgent.AddColumnInput> env =
                AgentEnvelope.<AddColumnPatchAgent.AddColumnInput>builder()
                        .projectId(projectId)
                        .taskId(taskId)
                        .agentType("AddColumnPatch")
                        .input(input)
                        .build();

        PatchPlan plan = addColumnPatchAgent.generate(env);
        if (plan == null) {
            // LLM 不可用降级：最小骨架计划，强制人工复核
            log.warn("AddColumnPatchAgent returned null for task={}, fallback to manual review plan", taskId);
            PatchPlan.Patch patch = PatchPlan.Patch.builder()
                    .filePath("db/migration/V_next__add_" + columnName + "_to_" + tableName + ".sql")
                    .changeType("CREATE")
                    .patchText("-- 待生成：ALTER TABLE " + tableName
                            + " ADD COLUMN " + columnName + " " + columnType
                            + (nullable ? ";" : " NOT NULL;"))
                    .evidenceIds(evidenceIds)
                    .build();
            plan = PatchPlan.builder()
                    .taskId(taskId)
                    .taskType("ADD_COLUMN")
                    .riskLevel("MEDIUM")
                    .patches(List.of(patch))
                    .validationGates(List.of("STATIC", "DB", "MIGRATION"))
                    .manualReviewNeeded(true)
                    .generatedBy("AddColumnPatchAgent(fallback)")
                    .build();
            return plan;
        }

        plan.setTaskId(taskId);
        plan.setTaskType("ADD_COLUMN");
        if (plan.getGeneratedBy() == null || plan.getGeneratedBy().isBlank()) {
            plan.setGeneratedBy("AddColumnPatchAgent");
        }
        // NOT NULL 无默认值 → 强制人工复核
        if (!nullable && (defaultValue == null || defaultValue.isBlank())) {
            plan.setManualReviewNeeded(true);
        }
        log.info("AddColumnPatchAgentAdapter produced PatchPlan for task={}, table={}.{}", taskId, tableName, columnName);
        return plan;
    }
}
