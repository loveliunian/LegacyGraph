package io.github.legacygraph.integration.graphify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dto.graph.EvidenceRecord;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.dto.graph.GraphWriteIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Graphify canonical mapper 测试。
 */
class GraphifyCanonicalMapperTest {

    private final GraphifyCanonicalMapper mapper = new GraphifyCanonicalMapper(new ObjectMapper());

    @Test
    void mapsJavaControllerNode() {
        GraphifyGraphJson graph = new GraphifyGraphJson(
                true,
                List.of(new GraphifyGraphJson.Node(
                        "src_user_controller",
                        "UserController",
                        "code",
                        "backend/src/main/java/demo/UserController.java",
                        "L12",
                        1,
                        "User API",
                        null
                )),
                null,
                null,
                null,
                "abc123"
        );

        GraphifyCanonicalMapper.MapResult result = mapper.map(graph, "proj1", "v1");

        assertNotNull(result.intent());
        assertEquals(1, result.intent().getNodeClaims().size());
        assertEquals(NodeType.Controller.name(), result.intent().getNodeClaims().get(0).getNodeType());
        assertEquals("GRAPHIFY_AST", result.intent().getNodeClaims().get(0).getSourceType());
        assertEquals(NodeStatus.CONFIRMED.name(), result.intent().getNodeClaims().get(0).getStatus());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void mapsVuePageNode() {
        GraphifyGraphJson graph = new GraphifyGraphJson(
                true,
                List.of(new GraphifyGraphJson.Node(
                        "src_user_list",
                        "UserList.vue",
                        "vue",
                        "frontend/src/views/UserList.vue",
                        "L1",
                        null,
                        null,
                        null
                )),
                null,
                null,
                null,
                "abc123"
        );

        GraphifyCanonicalMapper.MapResult result = mapper.map(graph, "proj1", "v1");

        assertNotNull(result.intent());
        assertEquals(1, result.intent().getNodeClaims().size());
        assertEquals(NodeType.Page.name(), result.intent().getNodeClaims().get(0).getNodeType());
        assertEquals("GRAPHIFY_AST", result.intent().getNodeClaims().get(0).getSourceType());
    }

    @Test
    void mapsSqlTableNode() {
        GraphifyGraphJson graph = new GraphifyGraphJson(
                true,
                List.of(new GraphifyGraphJson.Node(
                        "table_orders",
                        "orders",
                        "sql",
                        "backend/src/main/resources/db/migration/V1__init.sql",
                        "L10",
                        null,
                        null,
                        null
                )),
                null,
                List.of(new GraphifyGraphJson.Edge(
                        "table_orders",
                        "table_users",
                        "reads_from",
                        "EXTRACTED",
                        null,
                        null,
                        null
                )),
                null,
                "abc123"
        );

        GraphifyCanonicalMapper.MapResult result = mapper.map(graph, "proj1", "v1");

        assertNotNull(result.intent());
        assertEquals(1, result.intent().getNodeClaims().size());
        assertEquals(NodeType.Table.name(), result.intent().getNodeClaims().get(0).getNodeType());
        assertTrue(result.intent().getEdgeClaims().isEmpty()); // target node not in graph
        assertEquals(1, result.warnings().size());
    }

    @Test
    void mapsInferredEdgeWithConfidenceScore() {
        GraphifyGraphJson graph = new GraphifyGraphJson(
                true,
                List.of(
                        new GraphifyGraphJson.Node("n1", "Node1", "code", "a.java", "L1", null, null, null),
                        new GraphifyGraphJson.Node("n2", "Node2", "code", "b.java", "L1", null, null, null)
                ),
                List.of(new GraphifyGraphJson.Edge(
                        "n1",
                        "n2",
                        "calls",
                        "INFERRED",
                        0.85,
                        "a.java",
                        "L10"
                )),
                null,
                null,
                "abc123"
        );

        GraphifyCanonicalMapper.MapResult result = mapper.map(graph, "proj1", "v1");

        assertNotNull(result.intent());
        assertEquals(1, result.intent().getEdgeClaims().size());
        assertEquals(0.85, result.intent().getEdgeClaims().get(0).getConfidence().doubleValue(), 0.001);
        assertEquals(NodeStatus.PENDING_CONFIRM.name(), result.intent().getEdgeClaims().get(0).getStatus());
        assertEquals("GRAPHIFY_SEMANTIC", result.intent().getEdgeClaims().get(0).getSourceType());
    }

    @Test
    void mapsAmbiguousEdgeWithLowConfidence() {
        GraphifyGraphJson graph = new GraphifyGraphJson(
                true,
                List.of(
                        new GraphifyGraphJson.Node("n1", "Node1", "code", "a.java", "L1", null, null, null),
                        new GraphifyGraphJson.Node("n2", "Node2", "code", "b.java", "L1", null, null, null)
                ),
                List.of(new GraphifyGraphJson.Edge(
                        "n1",
                        "n2",
                        "similar_to",
                        "AMBIGUOUS",
                        null,
                        "a.java",
                        "L10"
                )),
                null,
                null,
                "abc123"
        );

        GraphifyCanonicalMapper.MapResult result = mapper.map(graph, "proj1", "v1");

        assertNotNull(result.intent());
        assertEquals(1, result.intent().getEdgeClaims().size());
        assertEquals(0.45, result.intent().getEdgeClaims().get(0).getConfidence().doubleValue(), 0.001);
        assertEquals(NodeStatus.PENDING_CONFIRM.name(), result.intent().getEdgeClaims().get(0).getStatus());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void generatesEvidenceRecords() {
        GraphifyGraphJson graph = new GraphifyGraphJson(
                true,
                List.of(new GraphifyGraphJson.Node("n1", "Node1", "code", "a.java", "L1", null, null, null)),
                List.of(new GraphifyGraphJson.Edge("n1", "n1", "calls", "EXTRACTED", null, "a.java", "L10")),
                null,
                null,
                "abc123"
        );

        GraphifyCanonicalMapper.MapResult result = mapper.map(graph, "proj1", "v1");

        assertNotNull(result.intent());
        assertEquals(2, result.intent().getEvidenceRecords().size()); // 1 node + 1 edge
        assertEquals("GRAPHIFY_NODE", result.intent().getEvidenceRecords().get(0).getEvidenceType());
        assertEquals("GRAPHIFY_EDGE", result.intent().getEvidenceRecords().get(1).getEvidenceType());
    }
}
