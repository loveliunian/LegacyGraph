package io.github.legacygraph.agent;

import io.github.legacygraph.dto.ChangeImpactAnalysis;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * ChangeImpactAgent - 变更影响分析增强（Phase 4）。
 * <p>Phase 3-1: 新增 {@link #analyze(AgentEnvelope)} 重载，支持证据合约调用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeImpactAgent {

    private final LlmGateway llmGateway;

    /**
     * 语义级变更影响分析（AgentEnvelope 合约版本 — Phase 3-1）。
     */
    public ChangeImpactAnalysis analyze(AgentEnvelope<ChangeImpactInput> envelope) {
        ChangeImpactInput input = envelope.getInput();
        if (input == null) {
            log.warn("ChangeImpactAgent: empty input in envelope {}", envelope.getContractId());
            return null;
        }
        Map<String, String> variables = new HashMap<>();
        variables.put("changeTarget", input.changeTarget != null ? input.changeTarget : "");
        variables.put("changeDescription", input.changeDescription != null ? input.changeDescription : "");
        variables.put("dependencies", input.dependencies != null ? input.dependencies : "");

        return llmGateway.callWithEnvelope(envelope, "change-impact",
                variables, ChangeImpactAnalysis.class);
    }

    /**
     * 语义级变更影响分析（兼容旧 API）。
     */
    public ChangeImpactAnalysis analyze(String projectId, String changeTarget,
                                        String changeDescription, String dependencies) {
        Map<String, String> variables = new HashMap<>();
        variables.put("changeTarget", changeTarget != null ? changeTarget : "");
        variables.put("changeDescription", changeDescription != null ? changeDescription : "");
        variables.put("dependencies", dependencies != null ? dependencies : "");

        return llmGateway.callWithTemplate(projectId, "change-impact",
                variables, ChangeImpactAnalysis.class);
    }

    /** ChangeImpact 输入 DTO */
    @lombok.Data
    @lombok.Builder
    public static class ChangeImpactInput {
        private String changeTarget;
        private String changeDescription;
        private String dependencies;
    }
}
