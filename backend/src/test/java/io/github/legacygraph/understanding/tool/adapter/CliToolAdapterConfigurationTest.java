package io.github.legacygraph.understanding.tool.adapter;

import io.github.legacygraph.understanding.tool.ToolCapability;
import io.github.legacygraph.understanding.tool.ToolConfigProperties;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CliToolAdapterConfigurationTest {

    private final CliToolAdapterConfiguration configuration = new CliToolAdapterConfiguration();
    private final CliProcessRunner processRunner = mock(CliProcessRunner.class);
    private final CliCommandPolicy commandPolicy = new CliCommandPolicy(Set.of());

    @Test
    void createsIndependentCliProfilesWithDifferentCapabilities() {
        ToolConfigProperties properties = new ToolConfigProperties();
        Map<String, ToolConfigProperties.ToolConfig> tools = new HashMap<>();
        tools.put("codex", cliConfig("codex"));
        tools.put("claude-code", cliConfig("claude"));
        tools.put("zread", cliConfig("zread"));
        tools.put("repomix", cliConfig("repomix"));
        properties.setTools(tools);

        CliToolAdapter codex = configuration.codexCliToolAdapter(properties, processRunner, commandPolicy);
        CliToolAdapter claude = configuration.claudeCodeCliToolAdapter(properties, processRunner, commandPolicy);
        CliToolAdapter zread = configuration.zreadCliToolAdapter(properties, processRunner, commandPolicy);
        CliToolAdapter repomix = configuration.repomixCliToolAdapter(properties, processRunner, commandPolicy);

        assertThat(codex.toolName()).isEqualTo("codex");
        assertThat(claude.toolName()).isEqualTo("claude-code");
        assertThat(zread.toolName()).isEqualTo("zread");
        assertThat(repomix.toolName()).isEqualTo("repomix");

        assertThat(codex.capabilities()).contains(ToolCapability.RUN_AGENT_RESEARCH);
        assertThat(claude.capabilities()).contains(ToolCapability.RUN_AGENT_RESEARCH);
        assertThat(zread.capabilities()).contains(ToolCapability.READ_RESOURCE)
                .doesNotContain(ToolCapability.RUN_AGENT_RESEARCH);
        assertThat(repomix.capabilities()).containsExactlyInAnyOrder(ToolCapability.PACK_CONTEXT);
    }

    private ToolConfigProperties.ToolConfig cliConfig(String path) {
        ToolConfigProperties.ToolConfig config = new ToolConfigProperties.ToolConfig();
        config.setEnabled(true);
        config.setPath(path);
        config.setTimeoutSeconds(120);
        config.setMaxOutputBytes(100_000);
        return config;
    }
}
