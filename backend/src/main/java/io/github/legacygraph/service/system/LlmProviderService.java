package io.github.legacygraph.service.system;

import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import io.github.legacygraph.entity.LlmProvider;
import io.github.legacygraph.repository.LlmProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 提供商管理服务
 * 管理 LLM 提供商的 CRUD、默认选择、动态创建 ChatModel
 */
@Slf4j
@Service
@org.springframework.context.annotation.Lazy(false)
@RequiredArgsConstructor
public class LlmProviderService {

    private final LlmProviderRepository llmProviderRepository;

    /** B-S4：LLM 调用超时（秒），激活 application.yml 中 legacygraph.ai.llm-timeout 配置 */
    @Value("${legacygraph.ai.llm-timeout:120}")
    private long llmTimeoutSeconds;

    /** 匹配 ${VAR} 或 ${VAR:default} 占位符 */
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    /**
     * 解析字符串中的环境变量占位符 ${VAR} 或 ${VAR:default}。
     * 例如 {@code resolveEnvVars("${OPENAI_API_KEY}")} → 返回 OPENAI_API_KEY 环境变量的值。
     * 未匹配到环境变量时使用 default 值（如有），否则抛出 IllegalStateException。
     */
    public static String resolveEnvPlaceholders(String value) {
        if (value == null || value.isBlank()) return value;
        Matcher m = ENV_PLACEHOLDER.matcher(value);
        if (!m.find()) return value; // 无占位符，直接返回
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            String defaultValue = m.group(2);
            String resolved = System.getenv(varName);
            if (resolved == null || resolved.isBlank()) {
                if (defaultValue != null) {
                    resolved = defaultValue;
                } else {
                    throw new IllegalStateException(
                            "环境变量 " + varName + " 未设置。请在启动前 export " + varName + "=your-key");
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 获取所有提供商
     */
    public List<LlmProvider> listAll() {
        return llmProviderRepository.findAll();
    }

    /**
     * 根据 providerCode 获取提供商
     */
    public LlmProvider getByCode(String providerCode) {
        return llmProviderRepository.findByCode(providerCode);
    }

    /**
     * 获取当前激活的默认提供商（缓存：每次 LLM 调用都会读取，配置极少变更）
     */
    @Cacheable(cacheNames = "llm-provider-default", key = "'default'")
    public LlmProvider getActiveDefault() {
        List<LlmProvider> all = llmProviderRepository.findAll();
        return all.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsDefault()) && Boolean.TRUE.equals(p.getIsActive()))
                .findFirst()
                .orElseGet(() -> all.stream()
                        .filter(p -> Boolean.TRUE.equals(p.getIsActive()))
                        .findFirst()
                        .orElse(null));
    }

    /**
     * 获取 Embedding 提供商（查找 provider_code 包含 "embedding" 的激活提供商）
     */
    @Cacheable(cacheNames = "llm-provider-default", key = "'embedding'")
    public LlmProvider getEmbeddingProvider() {
        List<LlmProvider> all = llmProviderRepository.findAll();
        return all.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsActive()))
                .filter(p -> p.getProviderCode() != null && p.getProviderCode().toLowerCase().contains("embedding"))
                .findFirst()
                .orElse(null);
    }

    /**
     * 保存或更新提供商配置
     */
    @Transactional
    @CacheEvict(cacheNames = "llm-provider-default", allEntries = true)
    public LlmProvider save(LlmProvider provider) {
        LlmProvider existing = llmProviderRepository.findByCode(provider.getProviderCode());
        if (existing != null) {
            // 已存在则更新，沿用已有 ID
            provider.setId(existing.getId());
            llmProviderRepository.updateById(provider);
        } else {
            llmProviderRepository.insert(provider);
        }
        return provider;
    }

    /**
     * 切换默认提供商
     */
    @Transactional
    @CacheEvict(cacheNames = "llm-provider-default", allEntries = true)
    public void setDefault(String providerCode) {
        List<LlmProvider> all = llmProviderRepository.findAll();
        for (LlmProvider p : all) {
            if (Boolean.TRUE.equals(p.getIsDefault())) {
                p.setIsDefault(false);
                llmProviderRepository.updateById(p);
            }
        }
        LlmProvider target = llmProviderRepository.findByCode(providerCode);
        if (target != null) {
            target.setIsDefault(true);
            target.setIsActive(true);
            llmProviderRepository.updateById(target);
            log.info("切换默认 LLM 提供商: {}", providerCode);
        }
    }

    /**
     * 启用/禁用提供商
     */
    @Transactional
    @CacheEvict(cacheNames = "llm-provider-default", allEntries = true)
    public void toggleActive(String providerCode, boolean active) {
        LlmProvider provider = llmProviderRepository.findByCode(providerCode);
        if (provider != null) {
            provider.setIsActive(active);
            llmProviderRepository.updateById(provider);
        }
    }

    /**
     * 删除提供商
     */
    @Transactional
    @CacheEvict(cacheNames = "llm-provider-default", allEntries = true)
    public void delete(String providerCode) {
        LlmProvider provider = llmProviderRepository.findByCode(providerCode);
        if (provider != null) {
            llmProviderRepository.deleteById(provider);
        }
    }

    /**
     * 根据提供商配置动态创建 OpenAiChatModel（兼容 DeepSeek / OpenAI / 其他兼容 API）
     */
    public OpenAiChatModel createChatModel(LlmProvider provider) {
        Map<String, Object> config = provider.getApiConfig();
        // 防御：apiConfig 可能为 null（DB 中 JSONB 列无默认值时），统一降级为空 Map
        if (config == null) {
            log.warn("LlmProvider apiConfig is null for provider={}, falling back to empty config", provider.getProviderCode());
            config = Map.of();
        }
        String apiKey = (String) config.getOrDefault("api_key", "");
        // 解析环境变量占位符 ${VAR} 或 ${VAR:default}
        apiKey = resolveEnvPlaceholders(apiKey);
        // 防御：OpenAI Java Client 4.x 不接受空字符串作为 apiKey，给出明确错误
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "LLM 提供商 " + provider.getProviderCode() + " 的 api_key 未配置。" +
                    "请在数据库 lg_llm_provider 表中为该提供商设置有效的 api_config，或通过环境变量 DEEPSEEK_API_KEY / OPENAI_API_KEY 注入。" +
                    " 当前 apiConfig=" + config);
        }
        String baseUrl = provider.getEndpoint();

        // 使用 OpenAI Java Client 4.x API — ClientOptions + SpringAiOpenAiHttpClient（OkHttp）
        var httpClient = SpringAiOpenAiHttpClient.builder()
                .timeout(Duration.ofSeconds(llmTimeoutSeconds))
                .build();

        var clientOptions = ClientOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .httpClient(httpClient)
                .build();

        var openAiClient = new OpenAIClientImpl(clientOptions);

        double temperature = config.containsKey("temperature")
                ? ((Number) config.get("temperature")).doubleValue()
                : 0.1;
        int maxTokens = config.containsKey("max_tokens")
                ? ((Number) config.get("max_tokens")).intValue()
                : 16384;

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(provider.getModelId())
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();

        return OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .options(options)
                .build();
    }

    /**
     * 根据提供商配置动态创建 OpenAiEmbeddingModel（用于向量模型测试）
     */
    public OpenAiEmbeddingModel createEmbeddingModel(LlmProvider provider) {
        Map<String, Object> config = provider.getApiConfig();
        if (config == null) {
            log.warn("LlmProvider apiConfig is null for provider={}, falling back to empty config", provider.getProviderCode());
            config = Map.of();
        }
        String apiKey = (String) config.getOrDefault("api_key", "");
        apiKey = resolveEnvPlaceholders(apiKey);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "LLM 提供商 " + provider.getProviderCode() + " 的 api_key 未配置。");
        }
        String baseUrl = provider.getEndpoint();

        var httpClient = SpringAiOpenAiHttpClient.builder()
                .timeout(Duration.ofSeconds(60))
                .build();

        var clientOptions = ClientOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .httpClient(httpClient)
                .build();

        var openAiClient = new OpenAIClientImpl(clientOptions);

        var options = OpenAiEmbeddingOptions.builder()
                .model(provider.getModelId())
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        return OpenAiEmbeddingModel.builder()
                .openAiClient(openAiClient)
                .options(options)
                .build();
    }

    /**
     * 判断提供商是否为向量（Embedding）模型。
     * 约定：provider_code 包含 "embedding"（大小写不敏感）。
     */
    public boolean isEmbeddingProvider(LlmProvider provider) {
        return provider != null
                && provider.getProviderCode() != null
                && provider.getProviderCode().toLowerCase().contains("embedding");
    }

    /**
     * 测试 LLM 提供商连通性（区分 chat / embedding 两类）。
     * 返回 Map：success, message, latencyMs, model, responseSnippet
     */
    public Map<String, Object> testProvider(LlmProvider provider) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("providerCode", provider.getProviderCode());
        result.put("modelId", provider.getModelId());
        result.put("type", isEmbeddingProvider(provider) ? "embedding" : "chat");

        long start = System.currentTimeMillis();
        try {
            if (isEmbeddingProvider(provider)) {
                OpenAiEmbeddingModel embeddingModel = createEmbeddingModel(provider);
                float[] vec = embeddingModel.embed("hello");
                long latency = System.currentTimeMillis() - start;
                result.put("success", true);
                result.put("message", "Embedding 测试成功，返回维度=" + (vec != null ? vec.length : 0));
                result.put("latencyMs", latency);
                result.put("vectorDim", vec != null ? vec.length : 0);
            } else {
                OpenAiChatModel chatModel = createChatModel(provider);
                org.springframework.ai.chat.client.ChatClient chatClient =
                        org.springframework.ai.chat.client.ChatClient.create(chatModel);
                org.springframework.ai.chat.model.ChatResponse chatResponse = chatClient.prompt()
                        .user("请回复 'OK' 两个字，确认连接正常。")
                        .call()
                        .chatResponse();
                long latency = System.currentTimeMillis() - start;
                String reply = "";
                if (chatResponse != null && chatResponse.getResult() != null
                        && chatResponse.getResult().getOutput() != null) {
                    reply = chatResponse.getResult().getOutput().getText();
                }
                result.put("success", true);
                result.put("message", "Chat 测试成功");
                result.put("latencyMs", latency);
                result.put("responseSnippet", reply != null && reply.length() > 200
                        ? reply.substring(0, 200) + "..." : reply);
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("message", "测试失败: " + rootCauseMessage(e));
            result.put("latencyMs", latency);
        }
        return result;
    }

    /** 提取异常根因消息 */
    private static String rootCauseMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg != null ? msg : cur.getClass().getSimpleName();
    }
}
