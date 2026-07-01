package io.github.legacygraph.agent;

import io.github.legacygraph.dto.RefactorSuggestion;
import io.github.legacygraph.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * RefactorAgent - 代码异味智能重构建议（Phase 4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefactorAgent {

    private final LlmGateway llmGateway;

    /**
     * 针对代码异味生成重构建议
     *
     * @param projectId 项目ID
     * @param target    目标（类/方法）
     * @param smellType 异味类型（如 GOD_CLASS / LONG_METHOD）
     * @param code      代码内容
     */
    public RefactorSuggestion suggest(String projectId, String target, String smellType, String code) {
        Map<String, String> variables = new HashMap<>();
        variables.put("target", target != null ? target : "");
        variables.put("smellType", smellType != null ? smellType : "");
        variables.put("code", code != null ? code : "");

        return llmGateway.callWithTemplate(projectId, "refactor-suggestion",
                variables, RefactorSuggestion.class);
    }
}
