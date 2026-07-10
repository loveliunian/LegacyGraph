package io.github.legacygraph.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 插件适配器配置 —— 注册 McpPluginAdapter 为 Spring Bean。
 */
@Configuration
public class McpPluginAdapterConfiguration {

    @Bean
    public McpPluginAdapter mcpPluginAdapter(PluginRegistry pluginRegistry,
                                              ObjectMapper objectMapper,
                                              Neo4jGraphDao neo4jGraphDao) {
        return new McpPluginAdapter(pluginRegistry, objectMapper, neo4jGraphDao);
    }
}
