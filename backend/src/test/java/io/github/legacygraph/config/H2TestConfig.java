package io.github.legacygraph.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H2 测试环境的 Mock Bean 配置
 * 仅在 test profile 下激活，mock 外部依赖（如 AI 模型）以加速测试启动
 */
@Configuration
@Profile("test")
public class H2TestConfig {

    @Bean
    @Primary
    public OpenAiChatModel openAiChatModel() {
        return mock(OpenAiChatModel.class);
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        when(mockModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        return mockModel;
    }

    /**
     * 测试环境下禁用安全认证，允许 API 请求通过
     */
    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                );
        return http.build();
    }
}
