package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.junit.jupiter.api.DisplayName;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.graph.GraphPathReadModel;

/**
 * GraphPathReadModel 测试：L-16 验证 BFS 邻居展开（queryOutgoingEdges）替代全表 queryEdges。
 */
@ExtendWith(MockitoExtension.class)
class GraphPathReadModelTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    private GraphPathReadModel pathReadModel;

    private GraphNode apiNode;

    @BeforeEach
    void setUp() {
        pathReadModel = new GraphPathReadModel(neo4jGraphDao);
        // L-16: 注入 @Value 字段（单元测试无 Spring 上下文）
        ReflectionTestUtils.setField(pathReadModel, "apiChainMaxDepth", 12);

        apiNode = new GraphNode();
        apiNode.setId("api-1");
        apiNode.setNodeType("ApiEndpoint");
        apiNode.setNodeName("GET /api/orders");
    }

    /**
     * L-16: BFS 邻居展开 — queryOutgoingEdges 返回带目标节点信息的边 Map。
     */
    @Test
    @DisplayName("getApiCallChain: BFS 邻居展开，边 Map 携带目标节点信息")
    void testGetApiCallChain_bfsNeighborExpansion() {
        when(neo4jGraphDao.findNode("p1", "v1", "ApiEndpoint", "node-key"))
                .thenReturn(Optional.of(apiNode));

        // L-16: queryOutgoingEdges 返回带 to* 字段的 Map
        Map<String, Object> edge1 = new HashMap<>();
        edge1.put("id", "edge-1");
        edge1.put("type", "CALLS");
        edge1.put("source", "api-1");
        edge1.put("target", "svc-1");
        edge1.put("toType", "Service");
        edge1.put("toName", "OrderService");
        edge1.put("toDisplayName", "Order Service");
        edge1.put("toConfidence", java.math.BigDecimal.ONE);
        edge1.put("toStatus", "CONFIRMED");
        edge1.put("toSourcePath", "/src/OrderService.java");

        // 第一次调用返回 1 条出边，第二次（已访问 svc-1）返回空
        when(neo4jGraphDao.queryOutgoingEdges(eq("p1"), eq("v1"), anyCollection()))
                .thenReturn(List.of(edge1))
                .thenReturn(List.of());

        GraphPathReadModel.PathChain chain = pathReadModel.getApiCallChain("p1", "v1", "node-key");

        assertNotNull(chain);
        assertEquals("api-1", chain.startNodeId);
        assertTrue(chain.nodes.size() >= 2, "应包含 API 节点 + Service 节点");
        assertEquals(1, chain.edges.size(), "应有 1 条 CALLS 边");
        assertEquals("CALLS", chain.edges.get(0).type());

        // L-16: 验证使用 queryOutgoingEdges 而非 queryEdges
        verify(neo4jGraphDao, atLeastOnce()).queryOutgoingEdges(eq("p1"), eq("v1"), anyCollection());
        verify(neo4jGraphDao, never()).queryEdges(anyString(), anyString(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("getApiCallChain: 无出边时只返回起始节点")
    void testGetApiCallChain_noEdges_returnsStartNodeOnly() {
        when(neo4jGraphDao.findNode("p1", "v1", "ApiEndpoint", "key"))
                .thenReturn(Optional.of(apiNode));
        when(neo4jGraphDao.queryOutgoingEdges(eq("p1"), eq("v1"), anyCollection()))
                .thenReturn(List.of());

        GraphPathReadModel.PathChain chain = pathReadModel.getApiCallChain("p1", "v1", "key");

        assertNotNull(chain);
        assertEquals("api-1", chain.startNodeId);
        assertEquals(1, chain.nodes.size());
        assertTrue(chain.edges.isEmpty());
    }

    @Test
    @DisplayName("getApiCallChain: API 节点不存在时返回空链")
    void testGetApiCallChain_apiNotFound_returnsEmpty() {
        when(neo4jGraphDao.findNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        GraphPathReadModel.PathChain chain = pathReadModel.getApiCallChain("p1", "v1", "nonexistent");

        assertNotNull(chain);
        assertTrue(chain.nodes.isEmpty());
        assertTrue(chain.edges.isEmpty());
    }

    @Test
    @DisplayName("getApiCallChain: maxDepth 参数生效，clamp ≤ 12")
    void testGetApiCallChain_maxDepthParameter() {
        when(neo4jGraphDao.findNode("p1", "v1", "ApiEndpoint", "key"))
                .thenReturn(Optional.of(apiNode));
        when(neo4jGraphDao.queryOutgoingEdges(eq("p1"), eq("v1"), anyCollection()))
                .thenReturn(List.of());

        // 传入 maxDepth=20，应被 clamp 到 12
        GraphPathReadModel.PathChain chain = pathReadModel.getApiCallChain("p1", "v1", "key", 20);
        assertNotNull(chain);
        assertEquals(1, chain.nodes.size());
    }

    @Test
    @DisplayName("getTableImpact: 反向 BFS 使用 queryIncomingEdges")
    void testGetTableImpact_usesIncomingEdges() {
        GraphNode tableNode = new GraphNode();
        tableNode.setId("tbl-1");
        tableNode.setNodeType("Table");
        tableNode.setNodeName("orders");
        when(neo4jGraphDao.queryNodes("p1", "v1", "Table", null, null, null, 200))
                .thenReturn(List.of(tableNode));
        when(neo4jGraphDao.queryIncomingEdges(eq("p1"), eq("v1"), anyCollection()))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryOutgoingEdges(eq("p1"), eq("v1"), anyCollection()))
                .thenReturn(List.of());

        GraphPathReadModel.PathChain chain = pathReadModel.getTableImpact("p1", "v1", "orders");

        assertNotNull(chain);
        assertEquals("tbl-1", chain.startNodeId);
        // L-16: 验证使用 queryIncomingEdges 而非 queryEdges(..., 500)
        verify(neo4jGraphDao, atLeastOnce()).queryIncomingEdges(eq("p1"), eq("v1"), anyCollection());
        verify(neo4jGraphDao, never()).queryEdges(anyString(), anyString(), any(), any(), eq(500));
    }
}
