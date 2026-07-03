package io.github.legacygraph.understanding.tool;

import io.github.legacygraph.understanding.tool.adapter.LocalFallbackAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ToolHealthService 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolHealthService 健康检查测试")
class ToolHealthServiceTest {

    @Mock
    private CodeUnderstandingToolAdapter mockCodexAdapter;

    @Mock
    private CodeUnderstandingToolAdapter mockZreadAdapter;

    private final LocalFallbackAdapter localFallbackAdapter = new LocalFallbackAdapter();

    private ToolHealthService toolHealthService;

    @BeforeEach
    void setUp() {
        // 构建配置
        var configProperties = new ToolConfigProperties();
        Map<String, ToolConfigProperties.ToolConfig> tools = new HashMap<>();

        var localConfig = new ToolConfigProperties.ToolConfig();
        localConfig.setEnabled(true);
        localConfig.setKind(ToolKind.LOCAL);
        tools.put("local-fallback", localConfig);

        var codexConfig = new ToolConfigProperties.ToolConfig();
        codexConfig.setEnabled(true);
        codexConfig.setKind(ToolKind.CLI);
        codexConfig.setPath("codex");
        tools.put("codex", codexConfig);

        var zreadConfig = new ToolConfigProperties.ToolConfig();
        zreadConfig.setEnabled(false);
        zreadConfig.setKind(ToolKind.CLI);
        zreadConfig.setPath("zread");
        tools.put("zread", zreadConfig);

        configProperties.setTools(tools);

        // 配置 mock adapter
        lenient().when(mockCodexAdapter.toolName()).thenReturn("codex");
        lenient().when(mockCodexAdapter.toolKind()).thenReturn(ToolKind.CLI);
        lenient().when(mockCodexAdapter.capabilities())
                .thenReturn(EnumSet.of(ToolCapability.SEARCH_SYMBOL, ToolCapability.READ_SNIPPET));

        lenient().when(mockZreadAdapter.toolName()).thenReturn("zread");
        lenient().when(mockZreadAdapter.toolKind()).thenReturn(ToolKind.CLI);
        lenient().when(mockZreadAdapter.capabilities())
                .thenReturn(EnumSet.of(ToolCapability.READ_RESOURCE));

        List<CodeUnderstandingToolAdapter> adapters = List.of(
                localFallbackAdapter, mockCodexAdapter, mockZreadAdapter);
        var toolRegistry = new ToolRegistry(adapters, configProperties);

        toolHealthService = new ToolHealthService(toolRegistry);
    }

    @Test
    @DisplayName("命令不存在时返回 NOT_INSTALLED")
    void shouldReturnNotInstalledWhenCommandNotFound() {
        when(mockCodexAdapter.checkHealth(any(ToolContext.class)))
                .thenReturn(ToolHealth.builder()
                        .toolName("codex")
                        .toolKind(ToolKind.CLI)
                        .status(ToolStatus.NOT_INSTALLED)
                        .capabilities(EnumSet.noneOf(ToolCapability.class))
                        .indexFreshness("UNKNOWN")
                        .message("命令未找到")
                        .build());

        ToolHealth health = toolHealthService.checkHealth("test-project", "codex");

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(ToolStatus.NOT_INSTALLED);
    }

    @Test
    @DisplayName("命令存在时返回 READY")
    void shouldReturnReadyWhenCommandAvailable() {
        when(mockCodexAdapter.checkHealth(any(ToolContext.class)))
                .thenReturn(ToolHealth.builder()
                        .toolName("codex")
                        .toolKind(ToolKind.CLI)
                        .status(ToolStatus.READY)
                        .capabilities(EnumSet.noneOf(ToolCapability.class))
                        .indexFreshness("FRESH")
                        .message("就绪")
                        .build());

        ToolHealth health = toolHealthService.checkHealth("test-project", "codex");

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(ToolStatus.READY);
    }

    @Test
    @DisplayName("local-fallback 永远返回 READY")
    void localFallbackShouldAlwaysReturnReady() {
        ToolHealth health = toolHealthService.checkHealth("test-project", "local-fallback");

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(ToolStatus.READY);
    }

    @Test
    @DisplayName("禁用工具不参与健康检查")
    void shouldNotIncludeDisabledTools() {
        // zread 被禁用，checkAllTools 不应包含它
        List<ToolHealth> all = toolHealthService.checkAllTools("test-project");
        List<String> names = all.stream().map(ToolHealth::getToolName).toList();

        assertThat(names).doesNotContain("zread");
        assertThat(names).contains("local-fallback", "codex");
    }
}
