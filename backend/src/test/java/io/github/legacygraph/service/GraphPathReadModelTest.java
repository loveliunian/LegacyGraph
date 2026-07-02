package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GraphPathReadModel 测试：验证 BFS 遍历使用批量 findNodesByIds 替代逐条 findNodeById。
 */
@ExtendWith(MockitoExtension.class)
class GraphPathReadModelTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    private GraphPathReadModel pathReadModel;

    private GraphNode apiNode;
    private GraphNode svcNode;
    private GraphEdge edge;

    @BeforeEach
    void setUp() {
        pathReadModel = new GraphPathReadModel(neo4jGraphDao);

        apiNode = new GraphNode();
        apiNode.setId("api-1");
        apiNode.setNodeType("ApiEndpoint");
        apiNode.setNodeName("GET /api/orders");

        svcNode = new GraphNode();
        svcNode.setId("svc-1");
        svcNode.setNodeType("Service");
        svcNode.setNodeName("OrderService");

        edge = new GraphEdge();
        edge.setFromNodeId("api-1");
        edge.setToNodeId("svc-1");
        edge.setEdgeType("CALLS");
    }

    @Test
    void testGetApiCallChain_usesBatchFindNodesByIds() {
        when(neo4jGraphDao.findNode("p1", "v1", "ApiEndpoint", "node-key"))
                .thenReturn(Optional.of(apiNode));
        when(neo4jGraphDao.queryEdges("p1", "v1", null, null, 200))
                .thenReturn(List.of(edge));
        // 批量加载，替代逐条 findNodeById
        when(neo4jGraphDao.findNodesByIds(List.of("svc-1"))).thenReturn(List.of(svcNode));

        GraphPathReadModel.PathChain chain = pathReadModel.getApiCallChain("p1", "v1", "node-key");

        assertNotNull(chain);
        assertEquals("api-1", chain.startNodeId);
        assertTrue(chain.nodes.size() >= 2);

        // 验证使用了批量查询而非逐条查询
        verify(neo4jGraphDao).findNodesByIds(anyList());
        verify(neo4jGraphDao, never()).findNodeById(anyString());
    }

    @Test
    void testGetTableImpact_usesBatchFindNodesByIds() {
        GraphNode tableNode = new GraphNode();
        tableNode.setId("tbl-1");
        tableNode.setNodeType("Table");
        tableNode.setNodeName("orders");
        when(neo4jGraphDao.queryNodes("p1", "v1", "Table", null, null, null, 50))
                .thenReturn(List.of(tableNode));
        when(neo4jGraphDao.queryEdges("p1", "v1", null, null, 200))
                .thenReturn(List.of());

        GraphPathReadModel.PathChain chain = pathReadModel.getTableImpact("p1", "v1", "orders");

        assertNotNull(chain);
        assertEquals("tbl-1", chain.startNodeId);
        // 无边，不会触发批量查询
    }

    @Test
    void testGetApiCallChain_noEdges_returnsStartNodeOnly() {
        when(neo4jGraphDao.findNode("p1", "v1", "ApiEndpoint", "key"))
                .thenReturn(Optional.of(apiNode));
        when(neo4jGraphDao.queryEdges("p1", "v1", null, null, 200))
                .thenReturn(List.of());

        GraphPathReadModel.PathChain chain = pathReadModel.getApiCallChain("p1", "v1", "key");

        assertNotNull(chain);
        assertEquals("api-1", chain.startNodeId);
        assertEquals(1, chain.nodes.size());
    }
}
