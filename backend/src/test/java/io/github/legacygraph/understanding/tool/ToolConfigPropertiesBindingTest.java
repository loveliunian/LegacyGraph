package io.github.legacygraph.understanding.tool;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ToolConfigPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void bindsDocumentedLegacygraphUnderstandingToolsShape() {
        contextRunner
                .withPropertyValues(
                        "legacygraph.understanding.tools.codex.enabled=true",
                        "legacygraph.understanding.tools.codex.kind=CLI",
                        "legacygraph.understanding.tools.codex.path=codex",
                        "legacygraph.understanding.tools.codex.timeout-seconds=120",
                        "legacygraph.understanding.tools.codex.max-output-bytes=100000"
                )
                .run(context -> {
                    ToolConfigProperties props = context.getBean(ToolConfigProperties.class);

                    assertThat(props.getTools()).containsKey("codex");
                    ToolConfigProperties.ToolConfig codex = props.getTools().get("codex");
                    assertThat(codex.isEnabled()).isTrue();
                    assertThat(codex.getKind()).isEqualTo(ToolKind.CLI);
                    assertThat(codex.getPath()).isEqualTo("codex");
                    assertThat(codex.getTimeoutSeconds()).isEqualTo(120);
                    assertThat(codex.getMaxOutputBytes()).isEqualTo(100000);
                });
    }

    @Configuration
    @EnableConfigurationProperties(ToolConfigProperties.class)
    static class Config {
    }
}
