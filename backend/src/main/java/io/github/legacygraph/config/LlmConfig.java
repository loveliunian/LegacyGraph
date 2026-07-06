package io.github.legacygraph.config;

import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import io.github.legacygraph.entity.LlmProvider;
import io.github.legacygraph.service.system.LlmProviderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * LLM 配置类
 */
@Configuration
public class LlmConfig {

    @Bean
    public RetryTemplate llmRetryTemplate() {
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(3);
        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(2000L);
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOffPolicy);
        return template;
    }

    /**
     * Embedding 模型 — 从 DB lg_llm_provider 表读取 Embedding 提供商配置。
     * 使用 provider_code 包含 "embedding" 的激活提供商，与 ChatModel 配置分开。
     * 未配置 Embedding 提供商时，Bean 不创建，语义搜索静默降级。
     */
    @Bean
    @Primary
    @Profile("!test")
    @DependsOn("flyway")
    public EmbeddingModel embeddingModel(LlmProviderService llmProviderService) {
        LlmProvider provider = llmProviderService.getEmbeddingProvider();
        if (provider == null) {
            throw new IllegalStateException(
                    "lg_llm_provider 表中缺少 Embedding 提供商（provider_code 包含 'embedding'），语义搜索不可用");
        }

        Map<String, Object> config = provider.getApiConfig();
        if (config == null) config = Map.of();
        String apiKey = (String) config.getOrDefault("api_key", "");
        // 解析环境变量占位符 ${VAR} 或 ${VAR:default}
        apiKey = io.github.legacygraph.service.system.LlmProviderService.resolveEnvPlaceholders(apiKey);
        if (apiKey.isBlank()) {
            throw new IllegalStateException("默认提供商的 api_key 为空");
        }

        var httpClient = SpringAiOpenAiHttpClient.builder()
                .timeout(Duration.ofSeconds(60))
                .build();

        var clientOptions = ClientOptions.builder()
                .baseUrl(provider.getEndpoint())
                .apiKey(apiKey)
                .httpClient(httpClient)
                .build();

        var client = new OpenAIClientImpl(clientOptions);

        String baseUrl = provider.getEndpoint();

        var options = OpenAiEmbeddingOptions.builder()
                .model(provider.getModelId())
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        return OpenAiEmbeddingModel.builder()
                .openAiClient(client)
                .options(options)
                .build();
    }
}
