package io.github.legacygraph.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.PromptRun;
import io.github.legacygraph.repository.PromptRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Component
public class LlmGateway {

    private final OpenAiChatModel openAiChatModel;
    private final ObjectMapper objectMapper;
    private final PromptRunRepository promptRunRepository;
    private final PromptTemplateLoader templateLoader;

    public LlmGateway(OpenAiChatModel openAiChatModel,
                   ObjectMapper objectMapper,
                   PromptRunRepository promptRunRepository,
                   PromptTemplateLoader templateLoader) {
        this.openAiChatModel = openAiChatModel;
        this.objectMapper = objectMapper;
        this.promptRunRepository = promptRunRepository;
        this.templateLoader = templateLoader;
    }

    /**
     * 调用LLM使用指定模板
     * @param projectId 项目ID
     * @param templateName 模板名称
     * @param variables 模板变量
     * @param responseType 期望返回类型
     * @return 解析后的响应对象
     */
    @Transactional
    public <T> T callWithTemplate(String projectId, String templateName,
                                   Map<String, String> variables, Class<T> responseType) {
        log.info("LLM call: projectId={}, template={}", projectId, templateName);

        // 加载并渲染模板
        String prompt = templateLoader.render(templateName, variables);
        String maskedPrompt = maskSensitiveData(prompt);
        log.debug("Rendered prompt:\n{}", maskedPrompt);

        // 创建记录
        PromptRun run = new PromptRun();
        run.setProjectId(projectId);
        run.setTemplateCode(templateName);
        run.setMaskedInput(maskedPrompt);
        run.setRawOutput(null);
        run.setStatus("RUNNING");
        run.setCreatedAt(LocalDateTime.now());
        promptRunRepository.insert(run);

        try {
            // 调用OpenAI — Spring AI 2.0 API
            ChatClient chatClient = ChatClient.create(openAiChatModel);
            String response = chatClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder()
                            .temperature(0.1))
                    .call()
                    .content();

            log.debug("LLM response received, length={}", response != null ? response.length() : 0);

            // 更新记录
            run.setRawOutput(response);
            run.setStatus("SUCCESS");
            promptRunRepository.updateById(run);

            // 解析响应
            if (responseType == String.class) {
                return (T) response;
            }

            // 尝试解析JSON
            // 清理markdown代码块标记
            String cleanResponse = cleanJsonResponse(response);
            T result = objectMapper.readValue(cleanResponse, responseType);
            log.info("LLM call completed successfully: projectId={}, template={}", projectId, templateName);
            return result;

        } catch (Exception e) {
            log.error("LLM call failed: projectId={}, template={}", projectId, templateName, e);
            if (run.getId() != null) {
                run.setStatus("FAILED");
                promptRunRepository.updateById(run);
            }
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 清理JSON响应，移除markdown代码块标记
     */
    private String cleanJsonResponse(String response) {
        if (response == null) return "{}";
        String cleaned = response.trim();
        // 移除 ```json ... ```
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring("```json".length());
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring("```".length());
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - "```".length());
        }
        return cleaned.trim();
    }

    /**
     * 脱敏敏感数据（简化实现，可扩展）
     */
    private String maskSensitiveData(String prompt) {
        // 移除API key等敏感信息
        return prompt.replaceAll("sk-[a-zA-Z0-9]{20,}", "[REDACTED]");
    }
}
