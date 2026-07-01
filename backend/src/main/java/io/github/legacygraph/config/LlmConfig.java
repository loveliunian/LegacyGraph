package io.github.legacygraph.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * LLM 配置类
 * 注意：实际的 LLM 提供商配置从数据库 lg_llm_provider 表读取
 * 此处不再依赖 spring.ai.openai.api-key 属性条件。
 */
@Configuration
public class LlmConfig {

    /**
     * B-S4：提供编程式重试给 LlmGateway 使用。
     * 私有内部调用无法依赖 @Retryable AOP 代理，故在调用点用 RetryTemplate 包裹真实模型请求。
     */
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
}
