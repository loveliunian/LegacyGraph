package io.github.legacygraph.agent;

import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.graph.PatchPlan;
import io.github.legacygraph.llm.LlmGateway;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * FRONTEND_CHANGE 补丁生成 Agent（阶段二-2.3）。
 * <p>
 * 针对前端变更（Vue/React 组件修改、新增页面、样式调整、路由变更）生成补丁计划。
 * 薄 LLM shim：组模板变量转发 {@link LlmGateway#callWithEnvelope}，模板名 frontend-change-patch。
 * LLM 不可用时由 {@code FrontendPatchAgentAdapter} 降级为人工复核骨架计划。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrontendPatchAgent {

    private final LlmGateway llmGateway;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FrontendChangeInput {
        /** 前端框架：vue / react / angular */
        private String framework;
        /** 变更类型：CREATE（新增组件）/ MODIFY（修改组件）/ DELETE（删除组件） */
        private String changeType;
        /** 组件名 */
        private String componentName;
        /** 组件文件路径 */
        private String filePath;
        /** 变更描述 */
        private String changeDescription;
        /** 影响文件摘要 */
        private String impactedFilesSummary;
    }

    public PatchPlan generate(AgentEnvelope<FrontendChangeInput> envelope) {
        FrontendChangeInput input = envelope.getInput();
        if (input == null) {
            log.warn("FrontendPatchAgent: empty input in envelope {}", envelope.getContractId());
            return null;
        }
        Map<String, String> variables = new HashMap<>();
        variables.put("framework", input.getFramework() != null ? input.getFramework() : "vue");
        variables.put("changeType", input.getChangeType() != null ? input.getChangeType() : "");
        variables.put("componentName", input.getComponentName() != null ? input.getComponentName() : "");
        variables.put("filePath", input.getFilePath() != null ? input.getFilePath() : "");
        variables.put("changeDescription", input.getChangeDescription() != null ? input.getChangeDescription() : "");
        variables.put("impactedFiles", input.getImpactedFilesSummary() != null ? input.getImpactedFilesSummary() : "");
        return llmGateway.callWithEnvelope(envelope, "frontend-change-patch", variables, PatchPlan.class);
    }
}
