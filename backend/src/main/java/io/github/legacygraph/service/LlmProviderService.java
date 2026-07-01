package io.github.legacygraph.service;

import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import io.github.legacygraph.entity.LlmProvider;
import io.github.legacygraph.repository.LlmProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LLM 提供商管理服务
 * 管理 LLM 提供商的 CRUD、默认选择、动态创建 ChatModel
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderService {

    private final LlmProviderRepository llmProviderRepository;

    /** B-S4：LLM 调用超时（秒），激活 application.yml 中 legacy-graph.ai.llm-timeout 配置 */
    @Value("${legacy-graph.ai.llm-timeout:120}")
    private long llmTimeoutSeconds;

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
                : 4096;

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(provider.getModelId())
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();

        return OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .options(options)
                .build();
    }
}
