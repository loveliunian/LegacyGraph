package io.github.legacygraph.config;

import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.service.LlmProviderService;
import io.github.legacygraph.service.PromptTemplateService;
import io.github.legacygraph.service.ScanVersionService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H2 测试环境的 Mock Bean 配置。
 * 覆盖 LLM 相关基础设施，让 Controller 集成测试在无外部 API key 时也能加载 ApplicationContext。
 */
@Configuration
public class H2TestConfig {

    @Bean
    @Primary
    public OpenAiChatModel openAiChatModel() {
        return mock(OpenAiChatModel.class);
    }

    @Bean
    @Primary
    public LlmGateway llmGateway() {
        return mock(LlmGateway.class);
    }

    @Bean
    @Primary
    public LlmProviderService llmProviderService() {
        return mock(LlmProviderService.class);
    }

    @Bean
    @Primary
    public PromptTemplateService promptTemplateService() {
        return mock(PromptTemplateService.class);
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        when(mockModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        return mockModel;
    }

    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
