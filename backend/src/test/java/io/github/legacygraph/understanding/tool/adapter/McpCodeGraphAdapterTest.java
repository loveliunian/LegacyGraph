package io.github.legacygraph.understanding.tool.adapter;

import io.github.legacygraph.understanding.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * McpCodeGraphAdapter 单元测试。
 *
 * <p>使用 Spring Boot Test + Mockito 进行测试：
 * {@code @Mock} 模拟 McpClientFacade，{@code @InjectMocks} 注入 McpCodeGraphAdapter。</p>
 *
 * <p>测试要点：</p>
 * <ul>
 *   <li>index_status 成功 → READY</li>
 *   <li>MCP 连接失败 → UNAVAILABLE，不抛异常</li>
 *   <li>search_graph 成功并正确映射</li>
 *   <li>get_code_snippet 成功并正确映射</li>
 * </ul>
 *
 * @author LegacyGraph
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpCodeGraphAdapter 单元测试")
class McpCodeGraphAdapterTest {

    /** fake MCP 客户端门面 —— 替代真实 HTTP 调用 */
    @Mock
    private McpClientFacade mcpClientFacade;

    @InjectMocks
    private McpCodeGraphAdapter adapter;

    @BeforeEach
    void setUp() {
        // @InjectMocks 已自动注入 mock，此处可放置额外初始化逻辑
    }

    // ──────────────────────────── checkHealth 测试 ────────────────────────────

    @Test
    @DisplayName("indexStatus 成功返回 → READY 状态")
    void checkHealthIndexStatusSuccess() {
        // given: MCP 服务正常，索引新鲜
        Map<String, Object> statusMap = Map.of(
                "indexFreshness", "FRESH",
                "fileCount", 150
        );
        when(mcpClientFacade.indexStatus(anyString())).thenReturn(statusMap);

        ToolContext context = ToolContext.builder()
                .projectId("test-project")
                .projectRoot("/tmp/test")
                .build();

        // when
        ToolHealth health = adapter.checkHealth(context);

        // then
        assertNotNull(health);
        assertEquals(ToolStatus.READY, health.getStatus());
        assertEquals("codebase-memory-mcp", health.getToolName());
        assertEquals(ToolKind.MCP, health.getToolKind());
        assertEquals("FRESH", health.getIndexFreshness());
        // 健康时 capabilities 应包含完整能力
        assertNotNull(health.getCapabilities());
        assertTrue(health.getCapabilities().contains(ToolCapability.SEARCH_SYMBOL));
        assertTrue(health.getCapabilities().contains(ToolCapability.TRACE_CALL));
        assertTrue(health.getCapabilities().contains(ToolCapability.READ_SNIPPET));
    }

    @Test
    @DisplayName("MCP 连接失败 → UNAVAILABLE，不抛异常")
    void checkHealthMcpConnectionFailure() {
        // given: MCP 服务不可达
        when(mcpClientFacade.indexStatus(anyString()))
                .thenThrow(new RuntimeException("Connection refused"));

        ToolContext context = ToolContext.builder()
                .projectId("test-project")
                .build();

        // when: 不应抛异常
        ToolHealth health = null;
        try {
            health = adapter.checkHealth(context);
        } catch (Exception e) {
            fail("checkHealth 不应抛出异常，但抛出了: " + e.getMessage());
        }

        // then
        assertNotNull(health);
        assertEquals(ToolStatus.UNAVAILABLE, health.getStatus());
        assertEquals("codebase-memory-mcp", health.getToolName());
        assertNotNull(health.getMessage());
        assertTrue(health.getMessage().contains("Connection refused"),
                "错误消息应包含原始异常信息");
        // 不可用时 capabilities 应为空
        assertTrue(health.getCapabilities().isEmpty());
    }

    @Test
    @DisplayName("indexStatus 返回 null → 安全降级")
    void checkHealthIndexStatusReturnsNull() {
        // given: MCP 返回 null
        when(mcpClientFacade.indexStatus(anyString())).thenReturn(null);

        ToolContext context = ToolContext.builder()
                .projectId("test-project")
                .build();

        // when
        ToolHealth health = adapter.checkHealth(context);

        // then: 不应 NPE，应正常返回 READY（null 不算异常）
        assertNotNull(health);
        assertEquals(ToolStatus.READY, health.getStatus());
        assertEquals("UNKNOWN", health.getIndexFreshness());
    }

    // ──────────────────────────── execute 测试 ────────────────────────────

    @Test
    @DisplayName("SEARCH_SYMBOL 成功并正确映射 evidenceRecords")
    void executeSearchSymbolSuccess() {
        // given: MCP 返回两条符号搜索结果
        Map<String, Object> rawResult = Map.of("results", List.of(
                Map.of("source_path", "src/main/UserService.java",
                        "symbol_qn", "com.example.UserService",
                        "line_start", 10,
                        "line_end", 25,
                        "excerpt", "public class UserService {"),
                Map.of("source_path", "src/main/OrderService.java",
                        "symbol_qn", "com.example.OrderService",
                        "line_start", 5,
                        "line_end", 30,
                        "excerpt", "public class OrderService {")
        ));
        when(mcpClientFacade.searchGraph(anyString())).thenReturn(rawResult);

        ToolRequest request = ToolRequest.builder()
                .projectId("test-project")
                .operation(ToolCapability.SEARCH_SYMBOL)
                .parameters(Map.of("query", "Service"))
                .build();

        // when
        ToolResult result = adapter.execute(request);

        // then
        assertNotNull(result);
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(0, result.getExitCode());
        assertEquals(ToolCapability.SEARCH_SYMBOL, result.getOperation());
        assertNotNull(result.getEvidenceRecords());
        assertEquals(2, result.getEvidenceRecords().size());

        // 验证第一条记录已归一化
        Map<String, Object> firstRecord = result.getEvidenceRecords().get(0);
        assertEquals("src/main/UserService.java", firstRecord.get("source_path"));
        assertEquals("com.example.UserService", firstRecord.get("symbol_qn"));
        assertEquals(10, firstRecord.get("line_start"));
        assertEquals(25, firstRecord.get("line_end"));

        // 验证 SHA-256 已计算
        assertNotNull(result.getStdoutSha256());
        assertEquals(64, result.getStdoutSha256().length()); // SHA-256 十六进制 64 字符

        // 验证 stdout excerpt 已生成
        assertNotNull(result.getStdoutExcerpt());
    }

    @Test
    @DisplayName("READ_SNIPPET 成功并正确映射")
    void executeReadSnippetSuccess() {
        // given: MCP 返回代码片段
        Map<String, Object> rawResult = Map.of(
                "source_path", "src/main/UserService.java",
                "line_start", 42,
                "line_end", 50,
                "excerpt", "    public User findById(Long id) {\n        return repository.findById(id);\n    }"
        );
        when(mcpClientFacade.getCodeSnippet(anyString(), anyInt(), anyInt()))
                .thenReturn(rawResult);

        ToolRequest request = ToolRequest.builder()
                .projectId("test-project")
                .operation(ToolCapability.READ_SNIPPET)
                .parameters(Map.of(
                        "filePath", "src/main/UserService.java",
                        "lineStart", 42,
                        "lineEnd", 50
                ))
                .build();

        // when
        ToolResult result = adapter.execute(request);

        // then
        assertNotNull(result);
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(ToolCapability.READ_SNIPPET, result.getOperation());
        assertNotNull(result.getEvidenceRecords());
        assertEquals(1, result.getEvidenceRecords().size());

        Map<String, Object> record = result.getEvidenceRecords().get(0);
        assertEquals("src/main/UserService.java", record.get("source_path"));
        assertEquals(42, record.get("line_start"));
        assertEquals(50, record.get("line_end"));
        assertNotNull(record.get("excerpt"));
        assertNotNull(result.getStdoutSha256());
    }

    @Test
    @DisplayName("TRACE_CALL 成功执行 Cypher 查询")
    void executeTraceCallSuccess() {
        // given: MCP 返回调用链结果
        Map<String, Object> rawResult = Map.of("results", List.of(
                Map.of("source_path", "src/main/Controller.java",
                        "symbol_qn", "com.example.OrderController",
                        "line_start", 30,
                        "line_end", 40,
                        "excerpt", "orderService.createOrder();"),
                Map.of("source_path", "src/main/OrderService.java",
                        "symbol_qn", "com.example.OrderService",
                        "line_start", 15,
                        "line_end", 28,
                        "excerpt", "public Order createOrder() {")
        ));
        when(mcpClientFacade.queryGraph(anyString())).thenReturn(rawResult);

        ToolRequest request = ToolRequest.builder()
                .projectId("test-project")
                .operation(ToolCapability.TRACE_CALL)
                .parameters(Map.of("symbolQn", "com.example.OrderController.createOrder"))
                .build();

        // when
        ToolResult result = adapter.execute(request);

        // then
        assertNotNull(result);
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(ToolCapability.TRACE_CALL, result.getOperation());
        assertEquals(2, result.getEvidenceRecords().size());
    }

    @Test
    @DisplayName("execute 异常 → 返回 FAILED 状态，不抛异常")
    void executeFailureReturnsFailedNotThrow() {
        // given: MCP 调用抛异常
        when(mcpClientFacade.searchGraph(anyString()))
                .thenThrow(new RuntimeException("MCP timeout"));

        ToolRequest request = ToolRequest.builder()
                .projectId("test-project")
                .operation(ToolCapability.SEARCH_SYMBOL)
                .parameters(Map.of("query", "AnyClass"))
                .build();

        // when: 不应抛异常
        ToolResult result = null;
        try {
            result = adapter.execute(request);
        } catch (Exception e) {
            fail("execute 不应抛出异常，但抛出了: " + e.getMessage());
        }

        // then
        assertNotNull(result);
        assertEquals("FAILED", result.getStatus());
        assertEquals(-1, result.getExitCode());
        assertNotNull(result.getErrorExcerpt());
        assertTrue(result.getErrorExcerpt().contains("MCP timeout"),
                "错误摘要应包含原始异常信息");
    }

    @Test
    @DisplayName("不支持的操作 → 返回 FAILED")
    void executeUnsupportedOperation() {
        ToolRequest request = ToolRequest.builder()
                .projectId("test-project")
                .operation(ToolCapability.DISCOVER_PROJECT) // 不支持的操作
                .parameters(Map.of())
                .build();

        // when
        ToolResult result = adapter.execute(request);

        // then
        assertNotNull(result);
        assertEquals("FAILED", result.getStatus());
        assertNotNull(result.getErrorExcerpt());
        assertTrue(result.getErrorExcerpt().contains("不支持的操作类型"));
    }

    // ──────────────────────────── 基础属性测试 ────────────────────────────

    @Test
    @DisplayName("toolName 返回 'codebase-memory-mcp'")
    void toolNameReturnsCorrectValue() {
        assertEquals("codebase-memory-mcp", adapter.toolName());
    }

    @Test
    @DisplayName("toolKind 返回 ToolKind.MCP")
    void toolKindReturnsMcp() {
        assertEquals(ToolKind.MCP, adapter.toolKind());
    }

    @Test
    @DisplayName("capabilities 包含 SEARCH_SYMBOL / TRACE_CALL / READ_SNIPPET")
    void capabilitiesContainsExpectedEnums() {
        var caps = adapter.capabilities();
        assertNotNull(caps);
        assertEquals(3, caps.size());
        assertTrue(caps.contains(ToolCapability.SEARCH_SYMBOL));
        assertTrue(caps.contains(ToolCapability.TRACE_CALL));
        assertTrue(caps.contains(ToolCapability.READ_SNIPPET));
    }
}
