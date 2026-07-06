package io.github.legacygraph.plugin;

import io.github.legacygraph.dto.plugin.ExternalPluginDescriptor;
import io.github.legacygraph.plugin.PluginRegistry.PluginDescriptor;
import io.github.legacygraph.plugin.PluginRegistry.PluginType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PluginRegistry} 动态注册测试（04 阶段4）。
 */
class PluginRegistryTest {

    private PluginRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PluginRegistry();
    }

    @Test
    void registerExternal_addsPluginAndStoresExternalDescriptor() {
        ExternalPluginDescriptor ext = ExternalPluginDescriptor.builder()
                .id("ext-mcp-1")
                .name("外部 MCP 工具")
                .description("测试动态注册")
                .pluginType("TOOL")
                .mcpEndpoint("http://localhost:3000/mcp")
                .protocol("MCP")
                .auth("token-xxx")
                .build();

        PluginDescriptor desc = registry.registerExternal(ext);

        assertEquals("ext-mcp-1", desc.id());
        assertEquals(PluginType.TOOL, desc.type());
        assertEquals("http://localhost:3000/mcp", desc.metadata().get("mcpEndpoint"));
        assertEquals("MCP", desc.metadata().get("protocol"));
        assertEquals(ext, registry.getExternal("ext-mcp-1"));
        assertTrue(registry.listAll().stream().anyMatch(p -> "ext-mcp-1".equals(p.id())));
        assertTrue(registry.listByType(PluginType.TOOL).stream()
                .anyMatch(p -> "ext-mcp-1".equals(p.id())));
    }

    @Test
    void registerExternal_graphViewTypeWorks() {
        ExternalPluginDescriptor ext = ExternalPluginDescriptor.builder()
                .id("ext-view-1")
                .name("外部视图")
                .description("视图插件")
                .pluginType("GRAPH_VIEW")
                .build();

        registry.registerExternal(ext);

        assertTrue(registry.listByType(PluginType.GRAPH_VIEW).stream()
                .anyMatch(p -> "ext-view-1".equals(p.id())));
    }
}
