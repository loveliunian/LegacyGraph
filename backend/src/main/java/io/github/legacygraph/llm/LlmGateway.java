package io.github.legacygraph.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.LlmProvider;
import io.github.legacygraph.entity.PromptRun;
import io.github.legacygraph.repository.PromptRunRepository;
import io.github.legacygraph.service.LlmProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 网关 — 支持多提供商动态切换
 * 从数据库读取当前默认的 LLM 提供商配置，动态创建 ChatClient。
 *
 * <p>Phase 0 加固要点：
 * <ul>
 *   <li>结构化校验：LLM 输出必须能反序列化到目标 DTO，否则进入 REVIEW。</li>
 *   <li>失败显式返回：不再把失败默默转换为空对象，而是抛出 {@link LlmCallException}。</li>
 *   <li>PromptRun 审计补全：写入 inputHash / parsedOutput / promptTokens / completionTokens / latencyMs。</li>
 * </ul>
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
     * @throws LlmCallException 调用失败或输出无法反序列化到目标 DTO 时抛出（失败显式返回）
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
        run.setInputHash(sha256(prompt));
        run.setMaskedInput(maskedPrompt);
        run.setStatus("RUNNING");
        run.setCreatedAt(LocalDateTime.now());
        promptRunRepository.insert(run);

        long startMs = System.currentTimeMillis();
        String response;
        try {
            // 动态获取 ChatModel 并调用
            OpenAiChatModel chatModel = getChatModel();
            ChatClient chatClient = ChatClient.create(chatModel);
            ChatResponse chatResponse = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .chatResponse();

            response = chatResponse != null && chatResponse.getResult() != null
                    && chatResponse.getResult().getOutput() != null
                    ? chatResponse.getResult().getOutput().getText()
                    : null;

            log.debug("LLM response received, length={}", response != null ? response.length() : 0);

            // 记录 token / latency 审计信息
            run.setLatencyMs((int) (System.currentTimeMillis() - startMs));
            applyUsage(run, chatResponse);
            run.setRawOutput(response);
        } catch (Exception e) {
            run.setLatencyMs((int) (System.currentTimeMillis() - startMs));
            run.setStatus("FAILED");
            safeUpdate(run);
            log.error("LLM call failed: projectId={}, template={}", projectId, templateName, e);
            throw new LlmCallException("LLM call failed: " + e.getMessage(), e, false, run.getId());
        }

        // 直接返回字符串类型
        if (responseType == String.class) {
            run.setStatus("SUCCESS");
            run.setParsedOutput(response);
            safeUpdate(run);
            return (T) response;
        }

        // 结构化解析与校验：失败时不返回空对象，而是标记 REVIEW 并抛出异常
        String cleanResponse = cleanJsonResponse(response);
        try {
            T result = objectMapper.readValue(cleanResponse, responseType);
            run.setStatus("SUCCESS");
            run.setParsedOutput(writeJsonSafe(result));
            safeUpdate(run);
            log.info("LLM call completed: projectId={}, template={}, provider={}, model={}",
                    projectId, templateName, providerCode, modelId);
            return result;
        } catch (Exception parseEx) {
            run.setStatus("REVIEW");
            safeUpdate(run);
            log.warn("LLM output failed schema validation, marked REVIEW: projectId={}, template={}, target={}",
                    projectId, templateName, responseType.getSimpleName(), parseEx);
            throw new LlmCallException(
                    "LLM output failed schema validation for " + responseType.getSimpleName()
                            + ": " + parseEx.getMessage(),
                    parseEx, true, run.getId());
        }
    }

    /**
     * 从 ChatResponse 提取 token 用量并写入审计记录（容错，缺失则跳过）
     */
    private void applyUsage(PromptRun run, ChatResponse chatResponse) {
        try {
            if (chatResponse == null || chatResponse.getMetadata() == null
                    || chatResponse.getMetadata().getUsage() == null) {
                return;
            }
            var usage = chatResponse.getMetadata().getUsage();
            if (usage.getPromptTokens() != null) {
                run.setPromptTokens(usage.getPromptTokens());
            }
            if (usage.getCompletionTokens() != null) {
                run.setCompletionTokens(usage.getCompletionTokens());
            }
        } catch (Exception ignore) {
            // token 用量为可选审计项，提取失败不影响主流程
        }
    }

    private void safeUpdate(PromptRun run) {
        try {
            if (run.getId() != null) {
                promptRunRepository.updateById(run);
            }
        } catch (Exception e) {
            log.warn("Failed to update PromptRun audit record: {}", e.getMessage());
        }
    }

    private String writeJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String sha256(String input) {
        if (input == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
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
