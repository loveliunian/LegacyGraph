package io.github.legacygraph.config;

import org.springframework.context.annotation.Configuration;

/**
 * LLM 配置类
 * 注意：实际的 LLM 提供商配置从数据库 lg_llm_provider 表读取
 * 此处不再依赖 spring.ai.openai.api-key 属性条�件
 */
@Configuration
public class LlmConfig {

}
