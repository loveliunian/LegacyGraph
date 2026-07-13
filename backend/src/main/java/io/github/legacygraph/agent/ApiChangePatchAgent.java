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
 * API_CHANGE 补丁生成 Agent（阶段二-2.3）。
 * <p>
 * 针对 API 端点变更（新增接口、修改请求/响应结构、废弃接口）生成补丁计划。
 * 薄 LLM shim：组模板变量转发 {@link LlmGateway#callWithEnvelope}，模板名 api-change-patch。
 * LLM 不可用时由 {@code ApiChangePatchAgentAdapter} 降级为人工复核骨架计划。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiChangePatchAgent {

    private final LlmGateway llmGateway;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiChangeInput {
        /** API 端点路径，如 /api/v1/users */
        private String endpointPath;
        /** HTTP 方法：GET/POST/PUT/DELETE/PATCH */
        private String httpMethod;
        /** 变更类型：CREATE（新增）/ MODIFY（修改）/ DEPRECATE（废弃） */
        private String changeType;
        /** Controller 类名 */
        private String controllerName;
        /** 方法名 */
        private String methodName;
        /** 变更描述 */
        private String changeDescription;
        /** 影响文件摘要 */
        private String impactedFilesSummary;
    }

    public PatchPlan generate(AgentEnvelope<ApiChangeInput> envelope) {
        ApiChangeInput input = envelope.getInput();
        if (input == null) {
            log.warn("ApiChangePatchAgent: empty input in envelope {}", envelope.getContractId());
            return null;
        }
        Map<String, String> variables = new HashMap<>();
        variables.put("endpointPath", input.getEndpointPath() != null ? input.getEndpointPath() : "");
        variables.put("httpMethod", input.getHttpMethod() != null ? input.getHttpMethod() : "");
        variables.put("changeType", input.getChangeType() != null ? input.getChangeType() : "");
        variables.put("controllerName", input.getControllerName() != null ? input.getControllerName() : "");
        variables.put("methodName", input.getMethodName() != null ? input.getMethodName() : "");
        variables.put("changeDescription", input.getChangeDescription() != null ? input.getChangeDescription() : "");
        variables.put("impactedFiles", input.getImpactedFilesSummary() != null ? input.getImpactedFilesSummary() : "");
        return llmGateway.callWithEnvelope(envelope, "api-change-patch", variables, PatchPlan.class);
    }
}
