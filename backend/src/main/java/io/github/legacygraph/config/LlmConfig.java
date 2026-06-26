package io.github.legacygraph.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 配置类
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.ai.openai", name = "api-key")
public class LlmConfig {

}
