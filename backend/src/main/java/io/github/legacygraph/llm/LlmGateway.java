package io.github.legacygraph.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class LlmGateway {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 调用LLM模板（占位实现）
     */
    public <T> T callWithTemplate(String projectId, String templateName,
                                   Map<String, String> variables, Class<T> responseType) {
        log.warn("LlmGateway.callWithTemplate is a placeholder - template={}, projectId={}", templateName, projectId);
        try {
            return objectMapper.readValue("{}", responseType);
        } catch (Exception e) {
            log.error("Failed to create empty response for {}", responseType.getSimpleName(), e);
            return null;
        }
    }
}
