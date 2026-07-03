package io.github.legacygraph.understanding.tool.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * MCP 客户端默认装配。
 *
 * <p>真实 MCP 客户端可以通过实现 {@link McpClientFacade} 覆盖此默认 Bean。
 * 未接入真实客户端时，应用仍应正常启动，并在健康检查阶段暴露 UNAVAILABLE，
 * 由工具路由降级到 CLI/本地分析能力。</p>
 */
@Configuration(proxyBeanMethods = false)
public class McpClientFacadeConfiguration {

    @Bean
    @ConditionalOnMissingBean(McpClientFacade.class)
    public McpClientFacade unavailableMcpClientFacade() {
        return new UnavailableMcpClientFacade();
    }

    private static class UnavailableMcpClientFacade implements McpClientFacade {

        @Override
        public Map<String, Object> indexStatus(String projectName) {
            throw unavailable();
        }

        @Override
        public Map<String, Object> searchGraph(String query) {
            throw unavailable();
        }

        @Override
        public Map<String, Object> getCodeSnippet(String filePath, int lineStart, int lineEnd) {
            throw unavailable();
        }

        @Override
        public Map<String, Object> queryGraph(String cypherQuery) {
            throw unavailable();
        }

        private IllegalStateException unavailable() {
            return new IllegalStateException("MCP client facade is not configured");
        }
    }
}
