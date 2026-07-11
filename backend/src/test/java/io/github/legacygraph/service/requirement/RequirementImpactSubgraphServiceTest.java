package io.github.legacygraph.service.requirement;

import io.github.legacygraph.common.FlowDirection;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.requirement.ImpactNode;
import io.github.legacygraph.dto.requirement.ImpactPath;
import io.github.legacygraph.dto.requirement.ImpactResult;
import io.github.legacygraph.dto.requirement.LinkedTarget;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ImpactSubgraphService}（requirement 包）单元测试（mock Neo4jGraphDao）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RequirementImpactSubgraphServiceTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @Mock
    private RiskScorer riskScorer;

    @Mock
    private ImpactGraphWriter impactGraphWriter;

    private ImpactSubgraphService service;

    private static final String PROJECT_ID = "proj-001";
    private static final String VERSION_ID = "ver-001";

    @BeforeEach
    void setUp() {
        service = new ImpactSubgraphService(neo4jGraphDao, riskScorer, impactGraphWriter);
        // 默认：findPathsDirected 返回空
        when(neo4jGraphDao.findPathsDirected(any(), any(), any(), anyList(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        // 默认：riskScorer 返回固定分数
        when(riskScorer.buildFactor(anyString(), anyInt(), anyString(), anyBoolean(),
                anyDouble(), anyDouble()))
                .thenReturn(new io.github.legacygraph.dto.requirement.RiskFactor());
        when(riskScorer.score(any())).thenReturn(1.0);
    }

    private LinkedTarget target(String nodeId, String nodeKey, String nodeName, String nodeType) {
        return new LinkedTarget(nodeId, nodeKey, nodeName, nodeType,
                "EXACT_REFERENCE", BigDecimal.ONE, "CONFIRMED", "R1", "text");
    }

    private GraphNode node(String id, String key, String name, String type) {
        GraphNode n = new GraphNode();
        n.setId(id);
        n.setNodeKey(key);
        n.setNodeName(name);
        n.setNodeType(type);
        return n;
    }

    private GraphEdge edge(String id, String type) {
        GraphEdge e = new GraphEdge();
        e.setId(id);
        e.setEdgeType(type);
        return e;
    }

    @Test
    void extract_emptyTargets_returnsEmpty() {
        ImpactResult result = service.extract(PROJECT_ID, VERSION_ID, List.of());
        assertNotNull(result.getImpactedNodes());
        assertTrue(result.getImpactedNodes().isEmpty());
        assertTrue(result.getPaths().isEmpty());
    }

    @Test
    void extract_nullTargets_returnsEmpty() {
        ImpactResult result = service.extract(PROJECT_ID, VERSION_ID, null);
        assertTrue(result.getImpactedNodes().isEmpty());
    }

    @Test
    void extract_noPaths_addsOnlyDirectNodes() {
        LinkedTarget t = target("t1", "public.t_order", "t_order", "Table");
        ImpactResult result = service.extract(PROJECT_ID, VERSION_ID, List.of(t));

        assertEquals(1, result.getImpactedNodes().size());
        ImpactNode n = result.getImpactedNodes().get(0);
        assertEquals("t1", n.getNodeId());
        assertEquals("public.t_order", n.getNodeKey());
        assertEquals(0, n.getDepth());
        assertEquals("DIRECT", n.getImpactType());
        assertTrue(result.getPaths().isEmpty());
    }

    @Test
    void extract_withPaths_collectsNodesAndPaths() {
        LinkedTarget t = target("t1", "public.t_order", "t_order", "Table");

        // 路径：t1 <- READS <- sql1 <- EXECUTES <- mapper1
        GraphNode t1 = node("t1", "public.t_order", "t_order", "Table");
        GraphNode sql1 = node("sql1", "sql:1", "selectOrders", "SqlStatement");
        GraphNode mapper1 = node("m1", "mapper:1", "OrderMapper", "Mapper");
        GraphEdge readsEdge = edge("e1", "READS");
        GraphEdge executesEdge = edge("e2", "EXECUTES");

        Neo4jGraphDao.GraphPath path = new Neo4jGraphDao.GraphPath(
                List.of(t1, sql1, mapper1),
                List.of(readsEdge, executesEdge),
                List.of("READS", "EXECUTES"),
                null, null, null, null, null, null);

        when(neo4jGraphDao.findPathsDirected(eq(PROJECT_ID), eq(VERSION_ID),
                eq("public.t_order"), eq(ImpactSubgraphService.EDGE_WHITELIST),
                eq(FlowDirection.INBOUND), eq(3), anyInt()))
                .thenReturn(List.of(path));

        ImpactResult result = service.extract(PROJECT_ID, VERSION_ID, List.of(t));

        // 3 个受影响节点
        assertEquals(3, result.getImpactedNodes().size());
        ImpactNode direct = result.getImpactedNodes().stream()
                .filter(n -> n.getNodeId().equals("t1")).findFirst().orElseThrow();
        assertEquals(0, direct.getDepth());
        assertEquals("DIRECT", direct.getImpactType());
        ImpactNode sqlNode = result.getImpactedNodes().stream()
                .filter(n -> n.getNodeId().equals("sql1")).findFirst().orElseThrow();
        assertEquals(1, sqlNode.getDepth());
        assertEquals("READS", sqlNode.getImpactType());
        ImpactNode mapperNode = result.getImpactedNodes().stream()
                .filter(n -> n.getNodeId().equals("m1")).findFirst().orElseThrow();
        assertEquals(2, mapperNode.getDepth());
        assertEquals("EXECUTES", mapperNode.getImpactType());

        // 1 条路径
        assertEquals(1, result.getPaths().size());
        ImpactPath p = result.getPaths().get(0);
        assertEquals("public.t_order", p.getStartNodeKey());
        assertEquals("mapper:1", p.getEndNodeKey());
        assertEquals(3, p.getPathNodes().size());
        assertEquals("public.t_order", p.getPathNodes().get(0));
        assertEquals("mapper:1", p.getPathNodes().get(2));
        assertEquals("READS", p.getImpactType());
        assertEquals(2, p.getDepth());
    }

    @Test
    void extract_dedupNodes_keepsMinDepth() {
        LinkedTarget t1 = target("t1", "public.t_order", "t_order", "Table");
        LinkedTarget t2 = target("t2", "public.t_settlement", "t_settlement", "Table");

        // t1 路径含 sql1（depth=1）
        GraphNode n1 = node("t1", "public.t_order", "t_order", "Table");
        GraphNode sql1 = node("sql1", "sql:1", "s1", "SqlStatement");
        Neo4jGraphDao.GraphPath path1 = new Neo4jGraphDao.GraphPath(
                List.of(n1, sql1), List.of(edge("e1", "READS")),
                List.of("READS"), null, null, null, null, null, null);

        // t2 路径也含 sql1（depth=2，应被忽略，保留 depth=1）
        GraphNode n2 = node("t2", "public.t_settlement", "t_settlement", "Table");
        GraphNode mid = node("mid", "sql:2", "s2", "SqlStatement");
        GraphNode sql1Again = node("sql1", "sql:1", "s1", "SqlStatement");
        Neo4jGraphDao.GraphPath path2 = new Neo4jGraphDao.GraphPath(
                List.of(n2, mid, sql1Again),
                List.of(edge("e2", "READS"), edge("e3", "EXECUTES")),
                List.of("READS", "EXECUTES"), null, null, null, null, null, null);

        when(neo4jGraphDao.findPathsDirected(eq(PROJECT_ID), eq(VERSION_ID),
                eq("public.t_order"), anyList(), eq(FlowDirection.INBOUND), anyInt(), anyInt()))
                .thenReturn(List.of(path1));
        when(neo4jGraphDao.findPathsDirected(eq(PROJECT_ID), eq(VERSION_ID),
                eq("public.t_settlement"), anyList(), eq(FlowDirection.INBOUND), anyInt(), anyInt()))
                .thenReturn(List.of(path2));

        ImpactResult result = service.extract(PROJECT_ID, VERSION_ID, List.of(t1, t2));

        // sql1 出现在两条路径，去重后只保留 depth=1
        ImpactNode sqlNode = result.getImpactedNodes().stream()
                .filter(n -> n.getNodeId().equals("sql1")).findFirst().orElseThrow();
        assertEquals(1, sqlNode.getDepth());
        assertEquals("READS", sqlNode.getImpactType());
    }

    @Test
    void extract_findPathsDirectedThrows_skipsAndKeepsDirectNode() {
        LinkedTarget t = target("t1", "public.t_order", "t_order", "Table");
        when(neo4jGraphDao.findPathsDirected(any(), any(), any(), anyList(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("Neo4j connection error"));

        ImpactResult result = service.extract(PROJECT_ID, VERSION_ID, List.of(t));

        // 异常被捕获，起点节点仍保留
        assertEquals(1, result.getImpactedNodes().size());
        assertEquals("t1", result.getImpactedNodes().get(0).getNodeId());
        assertTrue(result.getPaths().isEmpty());
    }

    @Test
    void extract_nullNodeKey_skipsPathsButAddsDirectNode() {
        // nodeKey 为空的 target，跳过路径查询
        LinkedTarget t = new LinkedTarget("t1", null, "t_order", "Table",
                "SEMANTIC", BigDecimal.valueOf(0.9), "PENDING_CONFIRM", "R1", "text");

        ImpactResult result = service.extract(PROJECT_ID, VERSION_ID, List.of(t));

        assertEquals(1, result.getImpactedNodes().size());
        assertEquals("DIRECT", result.getImpactedNodes().get(0).getImpactType());
        // findPathsDirected 不应被调用（nodeKey 为空跳过）
        verify(neo4jGraphDao, never()).findPathsDirected(
                any(), any(), any(), anyList(), any(), anyInt(), anyInt());
    }
}
