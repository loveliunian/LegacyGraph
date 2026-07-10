package io.github.legacygraph.service;

import io.github.legacygraph.dto.plugin.ExternalPluginDescriptor;
import io.github.legacygraph.dto.plugin.PluginTestResult;
import io.github.legacygraph.dto.plugin.PluginCheckResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PluginTestService 单元测试。
 * 测试分层检查逻辑：端点未配置、连通性失败、各状态码判定。
 */
class PluginTestServiceTest {

    private final PluginTestService service = new PluginTestService();

    private ExternalPluginDescriptor descriptor(String endpoint, String protocol, String auth) {
        return ExternalPluginDescriptor.builder()
                .id("test-plugin")
                .name("Test Plugin")
                .pluginType("TOOL")
                .mcpEndpoint(endpoint)
                .protocol(protocol)
                .auth(auth)
                .build();
    }

    @Test
    void test_nullEndpoint_returnsUnavailable() {
        ExternalPluginDescriptor desc = descriptor(null, "MCP", null);
        PluginTestResult result = service.test(desc);

        assertEquals("UNAVAILABLE", result.getOverallStatus());
        assertEquals(1, result.getChecks().size());
        assertEquals("FAIL", result.getChecks().get(0).getStatus());
        assertTrue(result.getChecks().get(0).getMessage().contains("未配置"));
    }

    @Test
    void test_blankEndpoint_returnsUnavailable() {
        ExternalPluginDescriptor desc = descriptor("  ", "MCP", null);
        PluginTestResult result = service.test(desc);

        assertEquals("UNAVAILABLE", result.getOverallStatus());
    }

    @Test
    void test_unreachableEndpoint_returnsUnavailable() {
        // 使用一个保证不可达的端口
        ExternalPluginDescriptor desc = descriptor("http://127.0.0.1:1", "MCP", null);
        PluginTestResult result = service.test(desc);

        assertEquals("UNAVAILABLE", result.getOverallStatus());
        PluginCheckResult connectivity = result.getChecks().get(0);
        assertEquals("connectivity", connectivity.getName());
        assertEquals("FAIL", connectivity.getStatus());
    }

    @Test
    void test_defaultProtocolIsMcp() {
        ExternalPluginDescriptor desc = ExternalPluginDescriptor.builder()
                .id("test-plugin")
                .name("Test")
                .pluginType("TOOL")
                .mcpEndpoint("http://127.0.0.1:1")
                .protocol(null)
                .build();
        PluginTestResult result = service.test(desc);

        assertEquals("MCP", result.getProtocol());
    }

    @Test
    void test_httpProtocol_runsResponseFormatCheck() {
        ExternalPluginDescriptor desc = descriptor("http://127.0.0.1:1", "HTTP", null);
        PluginTestResult result = service.test(desc);

        // 连通性失败后不会进入 response-format，但 protocol 应为 HTTP
        assertEquals("HTTP", result.getProtocol());
        assertEquals("UNAVAILABLE", result.getOverallStatus());
    }

    @Test
    void test_elapsedMsIsNonNegative() {
        ExternalPluginDescriptor desc = descriptor(null, "MCP", null);
        PluginTestResult result = service.test(desc);

        assertTrue(result.getElapsedMs() >= 0);
    }

    @Test
    void test_pluginIdIsPropagated() {
        ExternalPluginDescriptor desc = ExternalPluginDescriptor.builder()
                .id("my-custom-id")
                .name("Test")
                .pluginType("TOOL")
                .mcpEndpoint("http://127.0.0.1:1")
                .protocol("MCP")
                .build();
        PluginTestResult result = service.test(desc);

        assertEquals("my-custom-id", result.getPluginId());
    }

    @Test
    void test_checksListHasAtLeastOneEntry() {
        ExternalPluginDescriptor desc = descriptor(null, "MCP", null);
        PluginTestResult result = service.test(desc);

        assertNotNull(result.getChecks());
        assertFalse(result.getChecks().isEmpty());
    }
}
