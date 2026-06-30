package io.github.legacygraph.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.LlmProvider;
import io.github.legacygraph.entity.PromptRun;
import io.github.legacygraph.repository.PromptRunRepository;
import io.github.legacygraph.service.LlmProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 网关 — 支持多提供商动态切换
 * 从数据库读取当前默认的 LLM 提供商配置，动态创建 ChatClient
 */
@Slf4j
@Component
public class LlmGateway {

    private final ObjectMapper objectMapper;
    private final PromptRunRepository promptRunRepository;
    private final PromptTemplateLoader templateLoader;
    private final PiiMaskingService piiMaskingService;
    private final LlmProviderService llmProviderService;

    /** 缓存已创建的 ChatModel，按 providerCode 缓存 */
    private final Map<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();

    public LlmGateway(ObjectMapper objectMapper,
                      PromptRunRepository promptRunRepository,
                      PromptTemplateLoader templateLoader,
                      PiiMaskingService piiMaskingService,
                      LlmProviderService llmProviderService) {
        this.objectMapper = objectMapper;
        this.promptRunRepository = promptRunRepository;
        this.templateLoader = templateLoader;
        this.piiMaskingService = piiMaskingService;
        this.llmProviderService = llmProviderService;
    }

    /**
     * 获取当前可用的 ChatModel（从 DB 读取默认提供商）
     */
    private OpenAiChatModel getChatModel() {
        return getChatModel(null);
    }

    /**
     * 获取指定提供商的 ChatModel（带缓存）
     */
    private OpenAiChatModel getChatModel(String providerCode) {
        LlmProvider provider;
        if (providerCode != null && !providerCode.isEmpty()) {
            provider = llmProviderService.getByCode(providerCode);
        } else {
            provider = llmProviderService.getActiveDefault();
        }

        if (provider == null) {
            throw new IllegalStateException("没有可用的 LLM 提供商，请先在系统设置中配置");
        }

        String cacheKey = provider.getProviderCode();
        return chatModelCache.computeIfAbsent(cacheKey, k -> {
            OpenAiChatModel model = llmProviderService.createChatModel(provider);
            log.info("创建 ChatModel: provider={}, model={}, endpoint={}",
                    provider.getProviderCode(), provider.getModelId(), provider.getEndpoint());
            return model;
        });
    }

    /**
     * 清除缓存（切换提供商后调用）
     */
    public void clearCache() {
        chatModelCache.clear();
        log.info("LLM ChatModel 缓存已清除");
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
        String maskedPrompt = piiMaskingService.mask(prompt);
        log.debug("Rendered prompt:\n{}", maskedPrompt);

        // 获取当前默认提供商信息
        LlmProvider provider = llmProviderService.getActiveDefault();
        String providerCode = provider != null ? provider.getProviderCode() : "unknown";
        String modelId = provider != null ? provider.getModelId() : "unknown";

        // 创建记录
        PromptRun run = new PromptRun();
        run.setProjectId(projectId);
        run.setTaskType(templateName);
        run.setProviderCode(providerCode);
        run.setModelId(modelId);
        run.setTemplateCode(templateName);
        run.setTemplateVersion("1.0");
        run.setMaskedInput(maskedPrompt);
        run.setStatus("RUNNING");
        run.setCreatedAt(LocalDateTime.now());
        promptRunRepository.insert(run);

        try {
            // 动态获取 ChatModel 并调用
            OpenAiChatModel chatModel = getChatModel();
            ChatClient chatClient = ChatClient.create(chatModel);
            String response = chatClient.prompt()
                    .user(prompt)
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

            String cleanResponse = cleanJsonResponse(response);
            T result = objectMapper.readValue(cleanResponse, responseType);
            log.info("LLM call completed: projectId={}, template={}, provider={}, model={}",
                    projectId, templateName, providerCode, modelId);
            return result;

        } catch (Exception e) {
            log.error("LLM call failed: projectId={}, template={}", projectId, templateName, e);
            if (run.getId() != null) {
                run.setStatus("FAILED");
                promptRunRepository.updateById(run);
            }
            try {
                return objectMapper.readValue("{}", responseType);
            } catch (Exception ex) {
                throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 清理JSON响应，移除markdown代码块标记
     */
    private String cleanJsonResponse(String response) {
        if (response == null) return "{}";
        String cleaned = response.trim();
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
}
