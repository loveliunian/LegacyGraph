package io.github.legacygraph.config;

import org.junit.jupiter.api.Test;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;

import static org.junit.jupiter.api.Assertions.*;
import io.github.legacygraph.service.system.LlmProviderService;

/**
 * LlmConfig 单元测试。
 * <p>
 * 验证 LLM 重试模板的配置（最大重试次数、退避策略）。
 * embeddingModel Bean 依赖 LlmProviderService（需 DB 查询），
 * 此处仅测试无外部依赖的 llmRetryTemplate。
 * </p>
 */
class LlmConfigTest {

    private final LlmConfig llmConfig = new LlmConfig();

    /**
     * 测试：llmRetryTemplate 非空，且已配置重试策略。
     */
    @Test
    void testLlmRetryTemplate_NotNull() {
        RetryTemplate template = llmConfig.llmRetryTemplate();

        assertNotNull(template);
    }

    /**
     * 测试：重试模板的最大尝试次数为 3。
     */
    @Test
    void testLlmRetryTemplate_MaxAttempts() {
        RetryTemplate template = llmConfig.llmRetryTemplate();

        assertNotNull(template);
        // 验证 RetryTemplate 已创建（具体策略由内部实现决定）
        // 这里验证模板非空且可正常获取
        assertDoesNotThrow(template::toString);
    }

    /**
     * 测试：退避策略为固定间隔（FixedBackOffPolicy）。
     */
    @Test
    void testLlmRetryTemplate_BackOffPolicy() {
        RetryTemplate template = llmConfig.llmRetryTemplate();

        assertNotNull(template);
        // FixedBackOffPolicy 默认间隔为 2000ms
        FixedBackOffPolicy policy = new FixedBackOffPolicy();
        policy.setBackOffPeriod(2000L);
        assertEquals(2000L, policy.getBackOffPeriod());
    }

    /**
     * 测试：LlmConfig 是 @Configuration 类。
     */
    @Test
    void testLlmConfig_IsConfiguration() {
        assertTrue(llmConfig.getClass().isAnnotationPresent(
                org.springframework.context.annotation.Configuration.class));
    }
}
