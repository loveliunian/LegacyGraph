package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.graph.GraphProjectionReadModel;

/**
 * GraphProjectionReadModel 单元测试。
 * <p>
 * 验证功能视图（Feature View）和业务视图（Business View）的投影查询逻辑，
 * 以及图谱统计信息的计算。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class GraphProjectionReadModelTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @InjectMocks
    private GraphProjectionReadModel graphProjectionReadModel;

    private GraphNode featureNode;
    private GraphNode apiNode;
    private GraphEdge sampleEdge;

    @BeforeEach
    void setUp() {
        featureNode = new GraphNode();
        featureNode.setId("feat-1");
        featureNode.setNodeType("Feature");
        featureNode.setNodeName("用户管理");
        featureNode.setDisplayName("用户管理功能");
        featureNode.setConfidence(BigDecimal.valueOf(0.95));
        featureNode.setStatus("ACTIVE");

        apiNode = new GraphNode();
        apiNode.setId("api-1");
        apiNode.setNodeType("ApiEndpoint");
        apiNode.setNodeName("GET /api/users");
        apiNode.setConfidence(BigDecimal.valueOf(0.9));
        apiNode.setStatus("ACTIVE");

        sampleEdge = new GraphEdge();
        sampleEdge.setId("edge-1");
        sampleEdge.setFromNodeId("feat-1");
        sampleEdge.setToNodeId("api-1");
        sampleEdge.setEdgeType("EXPOSED_BY");
        sampleEdge.setConfidence(BigDecimal.valueOf(0.85));
        sampleEdge.setStatus("ACTIVE");
    }

    /**
     * 测试：获取功能视图 — 返回命中的 Feature 和 ApiEndpoint 节点及边。
     */
    @Test
    void testGetFeatureView_WithNodes() {
        // queryNodes 参数：(projectId, versionId, nodeType, sourceType, minConfidence, status, limit)
        // 先用通用 stub 兜底（其余类型返回空）— lenient 避免 UnnecessaryStubbing
        lenient().when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), anyString(),
                isNull(), isNull(), isNull(), eq(200)))
                .thenReturn(Collections.emptyList());
        // 具体类型 stub 覆盖
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq("Feature"),
                isNull(), isNull(), isNull(), eq(200)))
                .thenReturn(List.of(featureNode));
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq("ApiEndpoint"),
                isNull(), isNull(), isNull(), eq(200)))
                .thenReturn(List.of(apiNode));
        when(neo4jGraphDao.queryEdges(eq("proj-1"), eq("v1"),
                isNull(), isNull(), eq(500)))
                .thenReturn(List.of(sampleEdge));

        GraphProjectionReadModel.ProjectionView view =
                graphProjectionReadModel.getFeatureView("proj-1", "v1", null);

        assertNotNull(view);
        assertEquals("proj-1", view.projectId);
        assertEquals("v1", view.versionId);
        // 至少包含 Feature 和 ApiEndpoint 两个节点
        assertTrue(view.nodes.size() >= 2);
        // 至少包含一条边
        assertTrue(view.edges.size() >= 1);
        // 验证查询了边
        verify(neo4jGraphDao).queryEdges(eq("proj-1"), eq("v1"),
                isNull(), isNull(), eq(500));
    }

    /**
     * 测试：获取业务视图 — 按 domain 过滤业务节点。
     */
    @Test
    void testGetBusinessView_WithDomainFilter() {
        GraphNode bizDomain = new GraphNode();
        bizDomain.setId("bd-1");
        bizDomain.setNodeType("BusinessDomain");
        bizDomain.setNodeName("订单域");

        lenient().when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), anyString(),
                isNull(), isNull(), isNull(), eq(200)))
                .thenReturn(Collections.emptyList());
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq("BusinessDomain"),
                isNull(), isNull(), isNull(), eq(200)))
                .thenReturn(List.of(bizDomain));
        when(neo4jGraphDao.queryEdges(eq("proj-1"), eq("v1"),
                isNull(), isNull(), eq(500)))
                .thenReturn(Collections.emptyList());

        GraphProjectionReadModel.ProjectionView view =
                graphProjectionReadModel.getBusinessView("proj-1", "v1", "订单");

        assertNotNull(view);
        assertEquals("proj-1", view.projectId);
        assertEquals("v1", view.versionId);
        // 包含 "订单" 的节点应被保留
        assertTrue(view.nodes.size() >= 1);
    }

    /**
     * 测试：获取图谱统计 — 正确解析 totalNodes 和 confirmedNodes。
     */
    @Test
    void testGetGraphStats() {
        Map<String, Object> statsMap = Map.of(
                "totalNodes", 150L,
                "confirmedNodes", 120L
        );
        when(neo4jGraphDao.versionGraphStats("proj-1", "v1")).thenReturn(statsMap);

        GraphProjectionReadModel.GraphStats stats =
                graphProjectionReadModel.getGraphStats("proj-1", "v1");

        assertNotNull(stats);
        assertEquals(150L, stats.totalNodes());
        assertEquals(120L, stats.confirmedNodes());
        verify(neo4jGraphDao).versionGraphStats("proj-1", "v1");
    }

    /**
     * 测试：空图谱的功能视图 — 所有类型返回空，视图节点和边为空。
     */
    @Test
    void testGetFeatureView_EmptyGraph() {
        lenient().when(neo4jGraphDao.queryNodes(anyString(), anyString(), anyString(),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());
        lenient().when(neo4jGraphDao.queryEdges(anyString(), anyString(),
                isNull(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());

        GraphProjectionReadModel.ProjectionView view =
                graphProjectionReadModel.getFeatureView("proj-1", "v1", null);

        assertNotNull(view);
        assertEquals(0, view.nodes.size());
        assertEquals(0, view.edges.size());
    }
}
