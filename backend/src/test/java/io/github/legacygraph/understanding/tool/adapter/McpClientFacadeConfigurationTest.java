package io.github.legacygraph.understanding.tool.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpClientFacadeConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(McpClientFacadeConfiguration.class);

    @Test
    void providesUnavailableFacadeWhenNoRealClientExists() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(McpClientFacade.class);

            McpClientFacade facade = context.getBean(McpClientFacade.class);
            assertThatThrownBy(() -> facade.indexStatus("proj-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not configured");
        });
    }

    @Test
    void backsOffWhenRealClientExists() {
        new ApplicationContextRunner()
                .withBean(McpClientFacade.class, FakeMcpClientFacade::new)
                .withUserConfiguration(McpClientFacadeConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(McpClientFacade.class);
                    assertThat(context.getBean(McpClientFacade.class))
                            .isInstanceOf(FakeMcpClientFacade.class);
                });
    }

    private static class FakeMcpClientFacade implements McpClientFacade {
        @Override
        public Map<String, Object> indexStatus(String projectName) {
            return Map.of("indexFreshness", "FRESH");
        }

        @Override
        public Map<String, Object> searchGraph(String query) {
            return Map.of();
        }

        @Override
        public Map<String, Object> getCodeSnippet(String filePath, int lineStart, int lineEnd) {
            return Map.of();
        }

        @Override
        public Map<String, Object> queryGraph(String cypherQuery) {
            return Map.of();
        }
    }
}
