package io.github.legacygraph.agent.adapter;

import io.github.legacygraph.agent.FrontendPatchAgent;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.graph.PatchPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * FRONTEND_CHANGE 补丁适配器（阶段二-2.3）— 包装 {@link FrontendPatchAgent}，产出 {@link PatchPlan}。
 * <p>
 * 覆盖前端变更场景：Vue/React 组件修改、新增页面、样式调整、路由变更。
 * LLM 不可用（Agent 返回 null）时降级为最小骨架计划，标 manualReviewNeeded=true。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrontendPatchAgentAdapter {

    private final FrontendPatchAgent frontendPatchAgent;

    /**
     * @param taskId            变更任务 ID
     * @param projectId         项目 ID
     * @param framework         前端框架：vue / react / angular
     * @param changeType        变更类型：CREATE / MODIFY / DELETE
     * @param componentName     组件名
     * @param filePath          组件文件路径
     * @param changeDescription 变更描述
     * @param subgraph          影响子图
     * @param evidenceIds       证据 ID 列表
     */
    public PatchPlan toPatchPlan(String taskId, String projectId,
                                 String framework, String changeType,
                                 String componentName, String filePath, String changeDescription,
                                 ImpactSubgraph subgraph, List<String> evidenceIds) {
        String impactedFilesSummary = "";
        if (subgraph != null && subgraph.getImpactedFiles() != null) {
            impactedFilesSummary = String.join(", ", subgraph.getImpactedFiles());
        }

        FrontendPatchAgent.FrontendChangeInput input = FrontendPatchAgent.FrontendChangeInput.builder()
                .framework(framework)
                .changeType(changeType)
                .componentName(componentName)
                .filePath(filePath)
                .changeDescription(changeDescription)
                .impactedFilesSummary(impactedFilesSummary)
                .build();
        AgentEnvelope<FrontendPatchAgent.FrontendChangeInput> env =
                AgentEnvelope.<FrontendPatchAgent.FrontendChangeInput>builder()
                        .projectId(projectId)
                        .taskId(taskId)
                        .agentType("FrontendPatch")
                        .input(input)
                        .build();

        PatchPlan plan = frontendPatchAgent.generate(env);
        if (plan == null) {
            // LLM 不可用降级：最小骨架计划，强制人工复核
            log.warn("FrontendPatchAgent returned null for task={}, fallback to manual review plan", taskId);
            String resolvedPath = filePath != null && !filePath.isBlank()
                    ? filePath : "src/views/" + (componentName != null ? componentName : "Component") + ".vue";
            PatchPlan.Patch patch = PatchPlan.Patch.builder()
                    .filePath(resolvedPath)
                    .changeType("MODIFY".equals(changeType) ? "MODIFY" : "CREATE")
                    .patchText("<!-- 待生成：前端组件 " + componentName + " (" + changeType + ") -->")
                    .evidenceIds(evidenceIds)
                    .build();
            plan = PatchPlan.builder()
                    .taskId(taskId)
                    .taskType("FRONTEND_CHANGE")
                    .riskLevel("LOW")
                    .patches(List.of(patch))
                    .validationGates(List.of("STATIC", "UNIT"))
                    .manualReviewNeeded(true)
                    .generatedBy("FrontendPatchAgent(fallback)")
                    .build();
            return plan;
        }

        plan.setTaskId(taskId);
        plan.setTaskType("FRONTEND_CHANGE");
        if (plan.getGeneratedBy() == null || plan.getGeneratedBy().isBlank()) {
            plan.setGeneratedBy("FrontendPatchAgent");
        }
        log.info("FrontendPatchAgentAdapter produced PatchPlan for task={}, component={}",
                taskId, componentName);
        return plan;
    }
}
