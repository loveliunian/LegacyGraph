package io.github.legacygraph.agent;

import io.github.legacygraph.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * HyDE 生成器 - Hypothetical Document Embeddings
 * 生成假设性答案文档，用其 embedding 检索，提高召回率
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HyDEGenerator {

    private final LlmGateway llmGateway;

    /**
     * 生成假设性答案文档
     */
    public String generateHypotheticalDocument(String projectId, String question) {
        if (question == null || question.isBlank()) {
            return "";
        }

        try {
            Map<String, String> variables = new HashMap<>();
            variables.put("question", question);

            HyDEResult result = llmGateway.callWithTemplate(
                projectId, "hyde-generator", variables, HyDEResult.class
            );

            if (result != null && result.getHypotheticalAnswer() != null) {
                log.info("Generated HyDE document: question='{}', docLength={}", 
                    question, result.getHypotheticalAnswer().length());
                return result.getHypotheticalAnswer();
            }
        } catch (Exception e) {
            log.warn("HyDE generation failed: {}", e.getMessage());
        }

        return "";
    }

    public static class HyDEResult {
        private String hypotheticalAnswer;

        public String getHypotheticalAnswer() {
            return hypotheticalAnswer;
        }

        public void setHypotheticalAnswer(String hypotheticalAnswer) {
            this.hypotheticalAnswer = hypotheticalAnswer;
        }
    }
}
