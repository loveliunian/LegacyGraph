package io.github.legacygraph.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.plugin.ExternalPluginDescriptor;
import io.github.legacygraph.extractors.adapter.ScanContext;
import io.github.legacygraph.verification.ResultFusionEngine;
import io.github.legacygraph.verification.VerificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PluginInvocationService 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginInvocationServiceTest {

    @Mock
    private PluginRegistry pluginRegistry;
    @Mock
    private ResultFusionEngine resultFusionEngine;
    @Mock
    private PluginAdapter fixedAdapter;
    @Mock
    private io.github.legacygraph.dao.Neo4jGraphDao neo4jGraphDao;

    private McpPluginAdapter mcpPluginAdapter;
    private PluginInvocationService service;

    @BeforeEach
    void setUp() {
        mcpPluginAdapter = new McpPluginAdapter(pluginRegistry, new ObjectMapper(), neo4jGraphDao);

        ResultFusionEngine.FusionStats emptyStats = ResultFusionEngine.FusionStats.builder()
                .confirmedCount(0).missingWritten(0).propertiesWritten(0)
                .suspiciousMarked(0).errors(0).build();
        when(resultFusionEngine.fuse(anyString(), anyString(), any())).thenReturn(emptyStats);
        // 默认 listAll 返回空列表，避免 McpPluginAdapter.checkHealth() NPE
        when(pluginRegistry.listAll()).thenReturn(List.of());

        service = new PluginInvocationService(
                List.of(fixedAdapter), pluginRegistry, mcpPluginAdapter, resultFusionEngine);
    }

    private ScanContext buildContext() {
        return ScanContext.builder()
                .projectId("proj-1")
                .versionId("v-1")
                .baseDir("/tmp")
                .backendDir("/tmp/backend")
                .frontendDir("/tmp/frontend")
                .config(new java.util.concurrent.ConcurrentHashMap<>())
                .build();
    }

    @Test
    void invokePlugins_nullPluginIds_returnsEmptyStats() {
        ResultFusionEngine.FusionStats stats = service.invokePlugins("p", "v", null, buildContext());
        assertNotNull(stats);
        assertEquals(0, stats.getConfirmedCount());
    }

    @Test
    void invokePlugins_emptyPluginIds_returnsEmptyStats() {
        ResultFusionEngine.FusionStats stats = service.invokePlugins("p", "v", List.of(), buildContext());
        assertNotNull(stats);
        assertEquals(0, stats.getConfirmedCount());
    }

    @Test
    void invokePlugins_noAvailableAdapters_returnsEmptyStats() {
        // pluginIds 不匹配任何适配器，也不在 Registry 中
        when(pluginRegistry.getExternal("unknown")).thenReturn(null);
        ResultFusionEngine.FusionStats stats = service.invokePlugins("p", "v", List.of("unknown"), buildContext());
        assertNotNull(stats);
        assertEquals(0, stats.getConfirmedCount());
    }

    @Test
    void invokePlugins_fixedAdapterMatches_callsAdapter() {
        when(fixedAdapter.pluginId()).thenReturn("my-tool");
        when(fixedAdapter.supports(any())).thenReturn(true);
        when(fixedAdapter.checkHealth()).thenReturn(true);
        when(fixedAdapter.invoke(anyString(), anyString(), any()))
                .thenReturn(VerificationResult.empty("my-tool"));

        service.invokePlugins("p", "v", List.of("my-tool"), buildContext());

        verify(fixedAdapter).invoke(eq("p"), eq("v"), any(ScanContext.class));
    }

    @Test
    void invokePlugins_fixedAdapterUnhealthy_skipsAdapter() {
        when(fixedAdapter.pluginId()).thenReturn("my-tool");
        when(fixedAdapter.supports(any())).thenReturn(true);
        when(fixedAdapter.checkHealth()).thenReturn(false);
        when(pluginRegistry.getExternal("my-tool")).thenReturn(null);

        ResultFusionEngine.FusionStats stats = service.invokePlugins("p", "v", List.of("my-tool"), buildContext());
        assertEquals(0, stats.getConfirmedCount());
        verify(fixedAdapter, never()).invoke(anyString(), anyString(), any());
    }

    @Test
    void invokePlugins_mcpExternalPlugin_callsMcpAdapter() {
        ExternalPluginDescriptor ext = ExternalPluginDescriptor.builder()
                .id("ext-mcp")
                .name("External MCP")
                .pluginType("TOOL")
                .mcpEndpoint("http://127.0.0.1:1/mcp")  // 不可达，会返回空结果
                .protocol("MCP")
                .build();
        when(pluginRegistry.getExternal("ext-mcp")).thenReturn(ext);
        // 让 checkHealth 通过：至少有一个插件注册
        PluginRegistry.PluginDescriptor desc = new PluginRegistry.PluginDescriptor(
                "ext-mcp", "External MCP", "", PluginRegistry.PluginType.TOOL, "1.0", null);
        when(pluginRegistry.listAll()).thenReturn(List.of(desc));

        ResultFusionEngine.FusionStats stats = service.invokePlugins("p", "v", List.of("ext-mcp"), buildContext());

        // MCP 调用失败（端口不可达），返回 empty result，不进入融合
        assertNotNull(stats);
    }

    @Test
    void invokePlugins_adapterThrowsException_doesNotBlock() {
        when(fixedAdapter.pluginId()).thenReturn("crashy");
        when(fixedAdapter.supports(any())).thenReturn(true);
        when(fixedAdapter.checkHealth()).thenReturn(true);
        when(fixedAdapter.invoke(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("boom"));
        when(pluginRegistry.getExternal("crashy")).thenReturn(null);

        ResultFusionEngine.FusionStats stats = service.invokePlugins("p", "v", List.of("crashy"), buildContext());
        assertNotNull(stats);
        // 异常被隔离，不传播
    }

    @Test
    void invokePlugins_multiplePlugins_fusesResults() {
        when(fixedAdapter.pluginId()).thenReturn("tool-a");
        when(fixedAdapter.supports(any())).thenReturn(true);
        when(fixedAdapter.checkHealth()).thenReturn(true);
        when(fixedAdapter.invoke(anyString(), anyString(), any()))
                .thenReturn(VerificationResult.builder()
                        .adapterName("tool-a")
                        .totalChecked(5)
                        .totalConfirmed(3)
                        .build());
        when(pluginRegistry.getExternal("tool-a")).thenReturn(null);

        ResultFusionEngine.FusionStats expectedStats = ResultFusionEngine.FusionStats.builder()
                .confirmedCount(3).missingWritten(0).propertiesWritten(0)
                .suspiciousMarked(0).errors(0).build();
        when(resultFusionEngine.fuse(anyString(), anyString(), any())).thenReturn(expectedStats);

        ResultFusionEngine.FusionStats stats = service.invokePlugins("p", "v", List.of("tool-a"), buildContext());

        assertEquals(3, stats.getConfirmedCount());
        verify(resultFusionEngine).fuse(eq("p"), eq("v"), anyList());
    }
}
