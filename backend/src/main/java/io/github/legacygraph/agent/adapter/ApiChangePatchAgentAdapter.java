package io.github.legacygraph.agent.adapter;

import io.github.legacygraph.agent.ApiChangePatchAgent;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.graph.PatchPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * API_CHANGE 补丁适配器（阶段二-2.3）— 包装 {@link ApiChangePatchAgent}，产出 {@link PatchPlan}。
 * <p>
 * 覆盖 API 端点变更场景：新增接口、修改请求/响应结构、废弃接口。
 * LLM 不可用（Agent 返回 null）时降级为最小骨架计划，标 manualReviewNeeded=true。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiChangePatchAgentAdapter {

    private final ApiChangePatchAgent apiChangePatchAgent;

    /**
     * @param taskId           变更任务 ID
     * @param projectId        项目 ID
     * @param endpointPath     API 端点路径
     * @param httpMethod       HTTP 方法
     * @param changeType       变更类型：CREATE / MODIFY / DEPRECATE
     * @param controllerName   Controller 类名
     * @param methodName       方法名
     * @param changeDescription 变更描述
     * @param subgraph         影响子图
     * @param evidenceIds      证据 ID 列表
     */
    public PatchPlan toPatchPlan(String taskId, String projectId,
                                 String endpointPath, String httpMethod, String changeType,
                                 String controllerName, String methodName, String changeDescription,
                                 ImpactSubgraph subgraph, List<String> evidenceIds) {
        String impactedFilesSummary = "";
        if (subgraph != null && subgraph.getImpactedFiles() != null) {
            impactedFilesSummary = String.join(", ", subgraph.getImpactedFiles());
        }

        ApiChangePatchAgent.ApiChangeInput input = ApiChangePatchAgent.ApiChangeInput.builder()
                .endpointPath(endpointPath)
                .httpMethod(httpMethod)
                .changeType(changeType)
                .controllerName(controllerName)
                .methodName(methodName)
                .changeDescription(changeDescription)
                .impactedFilesSummary(impactedFilesSummary)
                .build();
        AgentEnvelope<ApiChangePatchAgent.ApiChangeInput> env =
                AgentEnvelope.<ApiChangePatchAgent.ApiChangeInput>builder()
                        .projectId(projectId)
                        .taskId(taskId)
                        .agentType("ApiChangePatch")
                        .input(input)
                        .build();

        PatchPlan plan = apiChangePatchAgent.generate(env);
        if (plan == null) {
            // LLM 不可用降级：最小骨架计划，强制人工复核
            log.warn("ApiChangePatchAgent returned null for task={}, fallback to manual review plan", taskId);
            String controllerFile = controllerName != null
                    ? "src/main/java/.../" + controllerName + ".java" : "src/main/java/.../Controller.java";
            PatchPlan.Patch patch = PatchPlan.Patch.builder()
                    .filePath(controllerFile)
                    .changeType("MODIFY".equals(changeType) ? "MODIFY" : "CREATE")
                    .patchText("-- 待生成：API " + httpMethod + " " + endpointPath
                            + " (" + changeType + ")")
                    .evidenceIds(evidenceIds)
                    .build();
            plan = PatchPlan.builder()
                    .taskId(taskId)
                    .taskType("API_CHANGE")
                    .riskLevel("MEDIUM")
                    .patches(List.of(patch))
                    .validationGates(List.of("STATIC", "UNIT", "API"))
                    .manualReviewNeeded(true)
                    .generatedBy("ApiChangePatchAgent(fallback)")
                    .build();
            return plan;
        }

        plan.setTaskId(taskId);
        plan.setTaskType("API_CHANGE");
        if (plan.getGeneratedBy() == null || plan.getGeneratedBy().isBlank()) {
            plan.setGeneratedBy("ApiChangePatchAgent");
        }
        // DEPRECATE 类型 → 强制人工复核
        if ("DEPRECATE".equals(changeType)) {
            plan.setManualReviewNeeded(true);
        }
        log.info("ApiChangePatchAgentAdapter produced PatchPlan for task={}, endpoint={} {}",
                taskId, httpMethod, endpointPath);
        return plan;
    }
}
