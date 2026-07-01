package io.github.legacygraph.agent;

import io.github.legacygraph.dto.PrDescription;
import io.github.legacygraph.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * PrDescriptionAgent - PR 描述 / 提交信息自动生成（Phase 4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrDescriptionAgent {

    private final LlmGateway llmGateway;

    /**
     * 根据 diff 生成提交信息与 PR 描述
     *
     * @param projectId 项目ID
     * @param branch    分支名
     * @param issue     关联 issue（可空）
     * @param diff      git diff / 变更摘要
     */
    public PrDescription generate(String projectId, String branch, String issue, String diff) {
        Map<String, String> variables = new HashMap<>();
        variables.put("branch", branch != null ? branch : "");
        variables.put("issue", issue != null ? issue : "（无）");
        variables.put("diff", diff != null ? diff : "");

        return llmGateway.callWithTemplate(projectId, "pr-description", variables, PrDescription.class);
    }
}
