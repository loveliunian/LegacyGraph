package io.github.legacygraph.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.LlmProvider;
import io.github.legacygraph.entity.PromptRun;
import io.github.legacygraph.entity.PromptTemplate;
import io.github.legacygraph.repository.LlmProviderRepository;
import io.github.legacygraph.repository.PromptRunRepository;
import io.github.legacygraph.repository.PromptTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * LLM Gateway - 统一入口，处理：
 * - 模型路由
 * - 输入缓存（通过 input hash）
 * - 脱敏
 * - 审计日志
 * - 结构化输出校验
 */
@Slf4j
@Service
public class LlmGateway {

    @Autowired
    private LlmProviderRepository providerRepository;

    @Autowired
    private PromptTemplateRepository templateRepository;

    @Autowired
    private PromptRunRepository promptRunRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private PiiMaskingService piiMaskingService;

    /**
     * 执行 LLM 调用，使用模板
     */
    public <T> T callWithTemplate(String projectId, String templateCode,
                                   Map<String, String> variables, Class<T> responseType) {
        // 1. 获取激活的模板
        PromptTemplate template = templateRepository.findActiveByCode(templateCode);
        if (template == null) {
            throw new IllegalArgumentException("Prompt template not found: " + templateCode);
        }

        // 2. 获取默认提供者（这里使用 OpenAI，可扩展支持多提供者路由）
        LlmProvider provider = providerRepository.findByCode("openai");
        if (provider == null) {
            provider = providerRepository.findByCode("openai-batch");
        }
        if (provider == null) {
            throw new IllegalStateException("No LLM provider configured");
        }

        // 3. 构建完整 Prompt
        String fullPrompt = buildPrompt(template, variables);

        // 4. 脱敏处理
        String maskedPrompt = piiMaskingService.mask(fullPrompt);

        // 5. 计算输入 hash 用于缓存
        String inputHash = DigestUtils.sha256Hex(maskedPrompt);

        // 6. 检查缓存
        Long cachedId = promptRunRepository.findCachedRunIdByInputHash(inputHash);
        if (cachedId != null) {
            PromptRun cached = promptRunRepository.selectById(cachedId);
            if (cached != null && cached.getParsedOutput() != null) {
                try {
                    log.info("Using cached LLM response for input hash: {}", inputHash);
                    return objectMapper.readValue(cached.getParsedOutput(), responseType);
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse cached output, re-running", e);
                }
            }
        }

        // 7. 执行调用
        long start = System.currentTimeMillis();
        ChatResponse response;
        try {
            var chatClient = chatClientBuilder
                    .advisors(new SimpleLoggerAdvisor())
                    .build();

            response = chatClient.prompt()
                    .user(maskedPrompt)
                    .call()
                    .chatResponse();
        } catch (Exception e) {
            log.error("LLM call failed", e);
            savePromptRun(projectId, template, provider, inputHash, maskedPrompt, null, null,
                    (int) (System.currentTimeMillis() - start), "failed");
            throw new RuntimeException("LLM call failed", e);
        }

        long latency = System.currentTimeMillis() - start;
        int promptTokens = response.getMetadata().getUsage().getPromptTokens();
        int completionTokens = response.getMetadata().getUsage().getCompletionTokens();

        // 8. 解析输出
        String content = response.getResult().getOutput().getContent();
        T parsedOutput;
        try {
            // 尝试清理 markdown 包装
            content = cleanJsonContent(content);
            parsedOutput = objectMapper.readValue(content, responseType);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse LLM output as JSON: {}", content, e);
            savePromptRun(projectId, template, provider, inputHash, maskedPrompt, content, null,
                    (int) latency, "failed");
            throw new RuntimeException("Failed to parse structured output", e);
        }

        // 9. 保存运行记录
        String parsedJson;
        try {
            parsedJson = objectMapper.writeValueAsString(parsedOutput);
        } catch (JsonProcessingException e) {
            parsedJson = null;
        }
        savePromptRun(projectId, template, provider, inputHash, maskedPrompt, content, parsedJson,
                (int) latency, "success");

        log.info("LLM call completed: template={}, provider={}, latency={}ms, tokens={}+{}",
                templateCode, provider.getProviderCode(), latency, promptTokens, completionTokens);

        return parsedOutput;
    }

    private String buildPrompt(PromptTemplate template, Map<String, String> variables) {
        StringBuilder sb = new StringBuilder();

        if (template.getSystemPrompt() != null && !template.getSystemPrompt().isEmpty()) {
            sb.append("[system]\n").append(template.getSystemPrompt()).append("\n\n");
        }
        if (template.getDomainPrompt() != null && !template.getDomainPrompt().isEmpty()) {
            String domainPrompt = template.getDomainPrompt();
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                domainPrompt = domainPrompt.replace("${" + entry.getKey() + "}", entry.getValue());
            }
            sb.append("[domain]\n").append(domainPrompt).append("\n\n");
        }
        if (template.getTaskPrompt() != null && !template.getTaskPrompt().isEmpty()) {
            String taskPrompt = template.getTaskPrompt();
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                taskPrompt = taskPrompt.replace("${" + entry.getKey() + "}", entry.getValue());
            }
            sb.append("[task]\n").append(taskPrompt).append("\n\n");
        }
        sb.append("[output_schema]\n").append(template.getOutputSchema());

        return sb.toString();
    }

    private String cleanJsonContent(String content) {
        // 移除 ```json 和 ``` 包裹
        content = content.trim();
        if (content.startsWith("```json")) {
            content = content.substring(7);
        }
        if (content.startsWith("```")) {
            content = content.substring(3);
        }
        if (content.endsWith("```")) {
            content = content.substring(0, content.length() - 3);
        }
        return content.trim();
    }

    private void savePromptRun(String projectId, PromptTemplate template, LlmProvider provider,
                                String inputHash, String maskedInput, String rawOutput, String parsedOutput,
                                int latencyMs, String status) {
        PromptRun run = new PromptRun();
        run.setProjectId(projectId);
        run.setTaskType(template.getScene());
        run.setProviderCode(provider.getProviderCode());
        run.setModelId(provider.getModelId());
        run.setTemplateCode(template.getTemplateCode());
        run.setTemplateVersion(template.getVersion());
        run.setInputHash(inputHash);
        try {
            run.setMaskedInput(objectMapper.writeValueAsString(maskedInput));
        } catch (JsonProcessingException e) {
            run.setMaskedInput("\"" + maskedInput.replace("\"", "\\\"") + "\"");
        }
        if (rawOutput != null) {
            try {
                run.setRawOutput(objectMapper.writeValueAsString(rawOutput));
            } catch (JsonProcessingException ignored) {
            }
        }
        if (parsedOutput != null) {
            run.setParsedOutput(parsedOutput);
        }
        run.setLatencyMs(latencyMs);
        run.setStatus(status);
        run.setCreatedAt(LocalDateTime.now());
        promptRunRepository.insert(run);
    }
}
