package io.github.legacygraph.understanding.tool.adapter;

import io.github.legacygraph.understanding.tool.ToolCapability;
import io.github.legacygraph.understanding.tool.ToolConfigProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * CLI 工具 profile 配置。
 *
 * <p>每个外部 CLI 工具必须作为独立 Adapter 注册，ToolRegistry 再按
 * {@code legacygraph.understanding.tools.<tool>.enabled} 决定是否启用。</p>
 */
@Configuration
public class CliToolAdapterConfiguration {

    @Bean
    public CliToolAdapter codexCliToolAdapter(ToolConfigProperties properties,
                                               CliProcessRunner processRunner,
                                               CliCommandPolicy commandPolicy) {
        return createAdapter("codex", "codex", "exec --no-write --json \"{query}\"",
                properties, processRunner, commandPolicy,
                Set.of(ToolCapability.READ_SNIPPET, ToolCapability.SUMMARIZE,
                        ToolCapability.RUN_AGENT_RESEARCH));
    }

    @Bean
    public CliToolAdapter claudeCodeCliToolAdapter(ToolConfigProperties properties,
                                                   CliProcessRunner processRunner,
                                                   CliCommandPolicy commandPolicy) {
        return createAdapter("claude-code", "claude", "-p \"{query}\"",
                properties, processRunner, commandPolicy,
                Set.of(ToolCapability.READ_SNIPPET, ToolCapability.SUMMARIZE,
                        ToolCapability.RUN_AGENT_RESEARCH));
    }

    @Bean
    public CliToolAdapter zreadCliToolAdapter(ToolConfigProperties properties,
                                              CliProcessRunner processRunner,
                                              CliCommandPolicy commandPolicy) {
        return createAdapter("zread", "zread", "\"{query}\"",
                properties, processRunner, commandPolicy,
                Set.of(ToolCapability.READ_RESOURCE, ToolCapability.READ_SNIPPET,
                        ToolCapability.SUMMARIZE));
    }

    @Bean
    public CliToolAdapter repomixCliToolAdapter(ToolConfigProperties properties,
                                                CliProcessRunner processRunner,
                                                CliCommandPolicy commandPolicy) {
        return createAdapter("repomix", "repomix", "--stdout \"{query}\"",
                properties, processRunner, commandPolicy,
                Set.of(ToolCapability.PACK_CONTEXT));
    }

    private CliToolAdapter createAdapter(String toolName,
                                         String defaultBinary,
                                         String defaultArgs,
                                         ToolConfigProperties properties,
                                         CliProcessRunner processRunner,
                                         CliCommandPolicy commandPolicy,
                                         Set<ToolCapability> capabilities) {
        ToolConfigProperties.ToolConfig config = properties.getTools().get(toolName);
        String binary = config != null && config.getPath() != null && !config.getPath().isBlank()
                ? config.getPath()
                : defaultBinary;
        int timeoutSeconds = config != null ? config.getTimeoutSeconds() : 120;
        int maxOutputBytes = config != null ? config.getMaxOutputBytes() : 100_000;
        return new CliToolAdapter(toolName, binary + " " + defaultArgs, timeoutSeconds,
                maxOutputBytes, processRunner, commandPolicy, capabilities);
    }
}
