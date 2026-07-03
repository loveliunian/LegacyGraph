package io.github.legacygraph.agent;

import io.github.legacygraph.llm.LlmGateway;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询意图分类器 - 使用 LLM 判断查询类型
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryIntentClassifier {

    private final LlmGateway llmGateway;

    /**
     * 分类查询意图
     */
    public QueryIntent classify(String projectId, String question, List<String> history) {
        if (question == null || question.isBlank()) {
            return QueryIntent.FACT_LOOKUP;
        }

        try {
            Map<String, String> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistory(history));

            IntentResult result = llmGateway.callWithTemplate(
                projectId, "intent-classifier", variables, IntentResult.class
            );

            if (result != null && result.getIntent() != null) {
                QueryIntent intent = QueryIntent.valueOf(result.getIntent());
                log.info("Classified intent: question='{}', intent={}, confidence={}", 
                    question, intent, result.getConfidence());
                return intent;
            }
        } catch (Exception e) {
            log.warn("Intent classification failed, defaulting to FACT_LOOKUP: {}", e.getMessage());
        }

        return QueryIntent.FACT_LOOKUP;
    }

    private String formatHistory(List<String> history) {
        if (history == null || history.isEmpty()) {
            return "（无历史对话）";
        }
        return String.join("\n", history);
    }

    @Data
    public static class IntentResult {
        private String intent;
        private Double confidence;
    }
}
