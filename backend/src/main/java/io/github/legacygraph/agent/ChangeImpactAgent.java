package io.github.legacygraph.agent;

import io.github.legacygraph.dto.ChangeImpactAnalysis;
import io.github.legacygraph.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * ChangeImpactAgent - 变更影响分析增强（Phase 4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeImpactAgent {

    private final LlmGateway llmGateway;

    /**
     * 语义级变更影响分析
     *
     * @param projectId         项目ID
     * @param changeTarget      变更目标
     * @param changeDescription 变更描述 / diff
     * @param dependencies      图谱依赖节点摘要
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
}
