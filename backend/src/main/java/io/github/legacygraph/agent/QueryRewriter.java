package io.github.legacygraph.agent;

import io.github.legacygraph.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 查询改写器 - 生成多个查询变体以提高召回率
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriter {

    private final LlmGateway llmGateway;

    /**
     * 改写查询，生成 2-3 个变体
     */
    public List<String> rewrite(String projectId, String question, QueryIntent intent) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        try {
            Map<String, String> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("intent", intent.name());

            RewriteResult result = llmGateway.callWithTemplate(
                projectId, "query-rewriter", variables, RewriteResult.class
            );

            if (result != null && result.getRewrites() != null && !result.getRewrites().isEmpty()) {
                log.info("Query rewrites: original='{}', rewrites={}", 
                    question, result.getRewrites().size());
                return result.getRewrites();
            }
        } catch (Exception e) {
            log.warn("Query rewrite failed: {}", e.getMessage());
        }

        // 降级：返回原始查询
        return List.of(question);
    }

    public static class RewriteResult {
        private List<String> rewrites;

        public List<String> getRewrites() {
            return rewrites;
        }

        public void setRewrites(List<String> rewrites) {
            this.rewrites = rewrites;
        }
    }
}
