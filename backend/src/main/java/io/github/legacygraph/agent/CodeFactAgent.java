package io.github.legacygraph.agent;

import io.github.legacygraph.dto.FactExtractionResult;
import io.github.legacygraph.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * CodeFactAgent - 理解代码事实的业务语义
 *
 * 职责：
 * - 理解方法业务语义
 * - 补全动态 SQL 分支
 * - 解释复杂调用链
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeFactAgent {

    private final LlmGateway llmGateway;

    /**
     * 处理代码片段，提取结构化事实
     */
    public FactExtractionResult extractFacts(String projectId, String codeContent, String sourcePath) {
        Map<String, String> variables = new HashMap<>();
        variables.put("projectId", projectId != null ? projectId : "");
        variables.put("codeContent", codeContent);
        variables.put("sourcePath", sourcePath);

        return llmGateway.callWithTemplate(projectId, "code-fact-extraction",
                variables, FactExtractionResult.class);
    }
}
