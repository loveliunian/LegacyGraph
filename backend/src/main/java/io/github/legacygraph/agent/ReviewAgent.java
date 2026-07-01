package io.github.legacygraph.agent;

import io.github.legacygraph.llm.LlmGateway;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReviewAgent - 人工审核辅助
 *
 * 职责：
 * - 输出人工审核摘要
 * - 整理支持证据和冲突证据
 * - 给出建议动作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewAgent {

    private final LlmGateway llmGateway;

    /**
     * 审核请求
     */
    @Data
    public static class ReviewRequest {
        private String projectId;
        private String targetType;  // NODE / EDGE
        private String targetDescription;
        private List<String> supportingEvidence;
        private List<String> conflictingEvidence;
        private double currentConfidence;
    }

    /**
     * 审核结果
     */
    @Data
    public static class ReviewResult {
        private String summary;
        private List<String> supportingPoints;
        private List<String> conflictingPoints;
        private Recommendation recommendation;
        private String reasoning;

        public enum Recommendation {
            APPROVE,
            REJECT,
            NEED_MORE_INFO
        }
    }

    /**
     * 生成审核建议
     */
    public ReviewResult generateReviewSuggestion(ReviewRequest request) {
        Map<String, String> variables = new HashMap<>();
        variables.put("targetType", request.getTargetType());
        variables.put("targetDescription", request.getTargetDescription());
        variables.put("supportingEvidence", String.join("\n- ", request.getSupportingEvidence()));
        variables.put("conflictingEvidence", String.join("\n- ", request.getConflictingEvidence()));
        variables.put("currentConfidence", String.valueOf(request.getCurrentConfidence()));

        // 使用独立的审核模板
        return llmGateway.callWithTemplate(request.getProjectId(), "review-suggestion",
                variables, ReviewResult.class);
    }
}
