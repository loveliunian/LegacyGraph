package io.github.legacygraph.verification;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.extractors.adapter.ScanContext;
import io.github.legacygraph.understanding.tool.adapter.McpClientFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link McpVerificationAdapter} MCP 验证适配器测试。
 */
@ExtendWith(MockitoExtension.class)
class McpVerificationAdapterTest {

    @Mock
    private McpClientFacade mcpClientFacade;

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    private McpVerificationAdapter adapter;

    private ScanContext context;

    @BeforeEach
    void setUp() {
        adapter = new McpVerificationAdapter(mcpClientFacade, neo4jGraphDao);
        context = ScanContext.builder()
                .projectId("p1")
                .versionId("v1")
                .baseDir("/tmp")
                .build();
    }

    @Test
    void adapterName_returnsCorrectName() {
        assertEquals("mcp-verification", adapter.adapterName());
    }

    @Test
    void priority_returns10() {
        assertEquals(10, adapter.priority());
    }

    @Test
    void supports_alwaysReturnsTrue() {
        assertTrue(adapter.supports(context));
        assertTrue(adapter.supports(null));
    }

    @Test
    void checkHealth_mcpAvailable_returnsTrue() {
        when(mcpClientFacade.indexStatus(any())).thenReturn(Map.of("status", "ok"));

        assertTrue(adapter.checkHealth());
    }

    @Test
    void checkHealth_mcpThrowsException_returnsFalse() {
        when(mcpClientFacade.indexStatus(any()))
                .thenThrow(new IllegalStateException("MCP client facade is not configured"));

        assertFalse(adapter.checkHealth());
    }

    @Test
    void verify_mcpUnavailable_returnsEmptyResult() {
        when(mcpClientFacade.indexStatus(any()))
                .thenThrow(new IllegalStateException("not configured"));

        VerificationResult result = adapter.verify("p1", "v1", context);

        assertEquals("mcp-verification", result.getAdapterName());
        assertTrue(result.getConfirmedEdges().isEmpty());
        assertTrue(result.getMissingEdges().isEmpty());
        assertTrue(result.getSuspiciousEdges().isEmpty());
        assertEquals(0, result.getTotalChecked());
    }

    @Test
    void verify_callsEdgeConfirmed_addsToConfirmed() {
        // MCP 健康
        when(mcpClientFacade.indexStatus(any())).thenReturn(Map.of("status", "ok"));

        // 本地有 1 条 CALLS 边
        GraphEdge callsEdge = new GraphEdge();
        callsEdge.setId("edge-1");
        callsEdge.setFromNodeId("node-1");
        callsEdge.setToNodeId("node-2");

        GraphNode fromNode = new GraphNode();
        fromNode.setId("node-1");
        fromNode.setNodeKey("com.example.UserService");
        GraphNode toNode = new GraphNode();
        toNode.setId("node-2");
        toNode.setNodeKey("com.example.UserMapper");

        when(neo4jGraphDao.queryEdges("p1", "v1", "CALLS", null, 500))
                .thenReturn(List.of(callsEdge));
        when(neo4jGraphDao.findNodesByIds(anyList()))
                .thenReturn(List.of(fromNode, toNode));

        // MCP queryGraph 确认 CALLS 边存在（cnt > 0）
        when(mcpClientFacade.queryGraph(anyString()))
                .thenReturn(Map.of("results", List.of(Map.of("cnt", 1L))));

        // 缺失边补全：本地无缺失（MCP 返回的与本地一致）
        when(neo4jGraphDao.queryEdges("p1", "v1", "CALLS", null, 1000))
                .thenReturn(List.of(callsEdge));

        // 继承边：无
        when(neo4jGraphDao.queryEdges("p1", "v1", "EXTENDS", null, 500))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryEdges("p1", "v1", "IMPLEMENTS", null, 500))
                .thenReturn(List.of());

        VerificationResult result = adapter.verify("p1", "v1", context);

        assertEquals(1, result.getTotalChecked());
        assertEquals(1, result.getConfirmedEdges().size());
        assertEquals("com.example.UserService", result.getConfirmedEdges().get(0).getFromNodeKey());
        assertEquals("com.example.UserMapper", result.getConfirmedEdges().get(0).getToNodeKey());
        assertEquals("CALLS", result.getConfirmedEdges().get(0).getEdgeType());
        assertEquals("mcp", result.getConfirmedEdges().get(0).getSourceTool());
        assertTrue(result.getSuspiciousEdges().isEmpty());
    }

    @Test
    void verify_callsEdgeNotConfirmedByMcp_addsToSuspicious() {
        when(mcpClientFacade.indexStatus(any())).thenReturn(Map.of("status", "ok"));

        GraphEdge callsEdge = new GraphEdge();
        callsEdge.setId("edge-1");
        callsEdge.setFromNodeId("node-1");
        callsEdge.setToNodeId("node-2");

        GraphNode fromNode = new GraphNode();
        fromNode.setId("node-1");
        fromNode.setNodeKey("fromKey");
        GraphNode toNode = new GraphNode();
        toNode.setId("node-2");
        toNode.setNodeKey("toKey");

        when(neo4jGraphDao.queryEdges("p1", "v1", "CALLS", null, 500))
                .thenReturn(List.of(callsEdge));
        when(neo4jGraphDao.findNodesByIds(anyList()))
                .thenReturn(List.of(fromNode, toNode));

        // MCP 未发现该 CALLS 边（cnt = 0）
        when(mcpClientFacade.queryGraph(contains("count(r)")))
                .thenReturn(Map.of("results", List.of(Map.of("cnt", 0L))));

        // 缺失边补全：MCP 无额外 CALLS
        when(neo4jGraphDao.queryEdges("p1", "v1", "CALLS", null, 1000))
                .thenReturn(List.of(callsEdge));
        when(mcpClientFacade.queryGraph(contains("RETURN a.nodeKey")))
                .thenReturn(Map.of("results", List.of()));

        // 继承边：无
        when(neo4jGraphDao.queryEdges("p1", "v1", "EXTENDS", null, 500))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryEdges("p1", "v1", "IMPLEMENTS", null, 500))
                .thenReturn(List.of());

        VerificationResult result = adapter.verify("p1", "v1", context);

        assertEquals(1, result.getTotalChecked());
        assertEquals(0, result.getConfirmedEdges().size());
        assertEquals(1, result.getSuspiciousEdges().size());
        assertEquals("fromKey", result.getSuspiciousEdges().get(0).getFromNodeKey());
    }

    @Test
    void verify_missingCallsEdge_discoveredFromMcp() {
        when(mcpClientFacade.indexStatus(any())).thenReturn(Map.of("status", "ok"));

        // 本地无 CALLS 边
        when(neo4jGraphDao.queryEdges("p1", "v1", "CALLS", null, 500))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryEdges("p1", "v1", "CALLS", null, 1000))
                .thenReturn(List.of());

        // MCP 返回一条本地缺失的 CALLS 关系
        when(mcpClientFacade.queryGraph(contains("RETURN a.nodeKey")))
                .thenReturn(Map.of("results", List.of(
                        Map.of("fromKey", "com.A", "toKey", "com.B")
                )));

        // 继承边：无
        when(neo4jGraphDao.queryEdges("p1", "v1", "EXTENDS", null, 500))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryEdges("p1", "v1", "IMPLEMENTS", null, 500))
                .thenReturn(List.of());

        VerificationResult result = adapter.verify("p1", "v1", context);

        // 应发现 1 条缺失边
        assertFalse(result.getMissingEdges().isEmpty());
        VerifiedEdge missingEdge = result.getMissingEdges().stream()
                .filter(e -> "CALLS".equals(e.getEdgeType()))
                .findFirst()
                .orElseThrow();
        assertEquals("com.A", missingEdge.getFromNodeKey());
        assertEquals("com.B", missingEdge.getToNodeKey());
        assertEquals(0.85, missingEdge.getConfidence());
    }

    @Test
    void verify_inheritanceEdge_resolvesTargetViaMcp() {
        when(mcpClientFacade.indexStatus(any())).thenReturn(Map.of("status", "ok"));

        // 本地无 CALLS 边
        when(neo4jGraphDao.queryEdges("p1", "v1", "CALLS", null, 500))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryEdges("p1", "v1", "CALLS", null, 1000))
                .thenReturn(List.of());

        // 本地有 1 条 EXTENDS 边，toNode 未解析
        GraphEdge extendsEdge = new GraphEdge();
        extendsEdge.setId("edge-ext-1");
        extendsEdge.setFromNodeId("node-1");
        extendsEdge.setToNodeId(null); // toNode 未解析

        GraphNode fromNode = new GraphNode();
        fromNode.setId("node-1");
        fromNode.setNodeKey("com.example.ChildService");
        fromNode.setNodeName("ChildService");

        when(neo4jGraphDao.queryEdges("p1", "v1", "EXTENDS", null, 500))
                .thenReturn(List.of(extendsEdge));
        when(neo4jGraphDao.queryEdges("p1", "v1", "IMPLEMENTS", null, 500))
                .thenReturn(List.of());
        when(neo4jGraphDao.findNodesByIds(anyList()))
                .thenReturn(List.of(fromNode));

        // MCP searchGraph 返回继承目标
        when(mcpClientFacade.searchGraph("ChildService"))
                .thenReturn(Map.of("results", List.of(
                        Map.of("symbol_qn", "com.example.BaseService")
                )));

        VerificationResult result = adapter.verify("p1", "v1", context);

        // 应发现 1 条缺失的继承边
        assertFalse(result.getMissingEdges().isEmpty());
        VerifiedEdge missingInherited = result.getMissingEdges().stream()
                .filter(e -> "EXTENDS".equals(e.getEdgeType()))
                .findFirst()
                .orElseThrow();
        assertEquals("com.example.ChildService", missingInherited.getFromNodeKey());
        assertEquals("com.example.BaseService", missingInherited.getToNodeKey());
    }

    @Test
    void verify_mcpQueryGraphThrows_fallsBackGracefully() {
        when(mcpClientFacade.indexStatus(any())).thenReturn(Map.of("status", "ok"));

        // 本地有 CALLS 边但 queryGraph 抛异常
        GraphEdge callsEdge = new GraphEdge();
        callsEdge.setFromNodeId("n1");
        callsEdge.setToNodeId("n2");

        GraphNode n1 = new GraphNode();
        n1.setId("n1");
        n1.setNodeKey("k1");
        GraphNode n2 = new GraphNode();
        n2.setId("n2");
        n2.setNodeKey("k2");

        when(neo4jGraphDao.queryEdges("p1", "v1", "CALLS", null, 500))
                .thenReturn(List.of(callsEdge));
        when(neo4jGraphDao.findNodesByIds(anyList()))
                .thenReturn(List.of(n1, n2));

        // queryGraph 全部抛异常
        when(mcpClientFacade.queryGraph(anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        when(neo4jGraphDao.queryEdges("p1", "v1", "CALLS", null, 1000))
                .thenReturn(List.of(callsEdge));
        when(neo4jGraphDao.queryEdges("p1", "v1", "EXTENDS", null, 500))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryEdges("p1", "v1", "IMPLEMENTS", null, 500))
                .thenReturn(List.of());

        VerificationResult result = adapter.verify("p1", "v1", context);

        // queryGraph 异常 → 视为 MCP 未确认 → 加入 suspicious
        assertEquals(1, result.getTotalChecked());
        assertEquals(0, result.getConfirmedEdges().size());
        assertEquals(1, result.getSuspiciousEdges().size());
    }
}
