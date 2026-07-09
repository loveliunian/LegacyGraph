package io.github.legacygraph.verification;

import io.github.legacygraph.builder.EvidenceGraphWriter;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ResultFusionEngine} 融合引擎测试。
 */
@ExtendWith(MockitoExtension.class)
class ResultFusionEngineTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @Mock
    private EvidenceGraphWriter evidenceGraphWriter;

    private ResultFusionEngine engine;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        engine = new ResultFusionEngine(neo4jGraphDao, evidenceGraphWriter);
    }

    @Test
    void fuse_emptyResults_returnsZeroStats() {
        ResultFusionEngine.FusionStats stats = engine.fuse("p1", "v1", List.of());

        assertEquals(0, stats.getConfirmedCount());
        assertEquals(0, stats.getMissingWritten());
        assertEquals(0, stats.getPropertiesWritten());
        assertEquals(0, stats.getSuspiciousMarked());
        assertEquals(0, stats.getErrors());
    }

    @Test
    void fuse_nullResults_returnsZeroStats() {
        ResultFusionEngine.FusionStats stats = engine.fuse("p1", "v1", null);

        assertEquals(0, stats.getConfirmedCount());
        assertEquals(0, stats.getErrors());
    }

    @Test
    void fuse_confirmedEdge_updatesConfidenceAndStatus() {
        // 本地有对应边
        GraphNode fromNode = new GraphNode();
        fromNode.setId("node-1");
        GraphNode toNode = new GraphNode();
        toNode.setId("node-2");
        GraphEdge localEdge = new GraphEdge();
        localEdge.setId("edge-1");

        when(neo4jGraphDao.findNode("p1", "v1", null, "fromKey"))
                .thenReturn(Optional.of(fromNode));
        when(neo4jGraphDao.findNode("p1", "v1", null, "toKey"))
                .thenReturn(Optional.of(toNode));
        when(neo4jGraphDao.findEdge("p1", "v1", "node-1", "node-2", "CALLS", null))
                .thenReturn(Optional.of(localEdge));

        VerifiedEdge confirmed = VerifiedEdge.builder()
                .fromNodeKey("fromKey")
                .toNodeKey("toKey")
                .edgeType("CALLS")
                .confidence(0.9)
                .sourceTool("mcp")
                .build();

        VerificationResult result = VerificationResult.builder()
                .adapterName("mcp")
                .confirmedEdges(List.of(confirmed))
                .totalChecked(1)
                .totalConfirmed(1)
                .build();

        ResultFusionEngine.FusionStats stats = engine.fuse("p1", "v1", List.of(result));

        assertEquals(1, stats.getConfirmedCount());
        verify(neo4jGraphDao).setEdgeProperty("edge-1", "confidence", 1.0);
        verify(neo4jGraphDao).setEdgeProperty("edge-1", "status", "CONFIRMED");
    }

    @Test
    void fuse_missingEdge_writesViaEvidenceGraphWriter() {
        GraphNode fromNode = new GraphNode();
        fromNode.setId("node-1");
        GraphNode toNode = new GraphNode();
        toNode.setId("node-2");

        when(neo4jGraphDao.findNode("p1", "v1", null, "fromKey"))
                .thenReturn(Optional.of(fromNode));
        when(neo4jGraphDao.findNode("p1", "v1", null, "toKey"))
                .thenReturn(Optional.of(toNode));

        VerifiedEdge missing = VerifiedEdge.builder()
                .fromNodeKey("fromKey")
                .toNodeKey("toKey")
                .edgeType("CALLS")
                .confidence(0.85)
                .sourceTool("mcp")
                .build();

        VerificationResult result = VerificationResult.builder()
                .adapterName("mcp")
                .missingEdges(List.of(missing))
                .build();

        ResultFusionEngine.FusionStats stats = engine.fuse("p1", "v1", List.of(result));

        assertEquals(1, stats.getMissingWritten());

        ArgumentCaptor<GraphEdgeClaim> captor = ArgumentCaptor.forClass(GraphEdgeClaim.class);
        verify(evidenceGraphWriter).upsertEdge(captor.capture());

        GraphEdgeClaim claim = captor.getValue();
        assertEquals("p1", claim.getProjectId());
        assertEquals("v1", claim.getVersionId());
        assertEquals("node-1", claim.getFromNodeId());
        assertEquals("node-2", claim.getToNodeId());
        assertEquals("CALLS", claim.getEdgeType());
        assertEquals("EXTERNAL_VERIFY", claim.getSourceType());
        assertEquals(0, new java.math.BigDecimal("0.85").compareTo(claim.getConfidence()));
        assertEquals("PENDING_CONFIRM", claim.getStatus());
    }

    @Test
    void fuse_missingEdge_nodeNotFound_skipsWithoutError() {
        when(neo4jGraphDao.findNode("p1", "v1", null, "missingKey"))
                .thenReturn(Optional.empty());

        VerifiedEdge missing = VerifiedEdge.builder()
                .fromNodeKey("missingKey")
                .toNodeKey("toKey")
                .edgeType("CALLS")
                .confidence(0.85)
                .sourceTool("mcp")
                .build();

        VerificationResult result = VerificationResult.builder()
                .adapterName("mcp")
                .missingEdges(List.of(missing))
                .build();

        ResultFusionEngine.FusionStats stats = engine.fuse("p1", "v1", List.of(result));

        assertEquals(0, stats.getMissingWritten());
        assertEquals(0, stats.getErrors());
        verify(evidenceGraphWriter, never()).upsertEdge(any());
    }

    @Test
    void fuse_nodeProperty_writesWithSourceToolPrefix() {
        GraphNode node = new GraphNode();
        node.setId("node-1");

        when(neo4jGraphDao.findNode("p1", "v1", null, "serviceKey"))
                .thenReturn(Optional.of(node));

        VerifiedNodeProperty prop = VerifiedNodeProperty.builder()
                .nodeKey("serviceKey")
                .propertyName("complexity")
                .propertyValue(15)
                .sourceTool("mcp")
                .build();

        VerificationResult result = VerificationResult.builder()
                .adapterName("mcp")
                .nodeProperties(List.of(prop))
                .build();

        ResultFusionEngine.FusionStats stats = engine.fuse("p1", "v1", List.of(result));

        assertEquals(1, stats.getPropertiesWritten());
        verify(neo4jGraphDao).setNodeProperty("node-1", "mcp.complexity", 15);
    }

    @Test
    void fuse_suspiciousEdge_downgradesStatus() {
        GraphNode fromNode = new GraphNode();
        fromNode.setId("node-1");
        GraphNode toNode = new GraphNode();
        toNode.setId("node-2");
        GraphEdge localEdge = new GraphEdge();
        localEdge.setId("edge-1");

        when(neo4jGraphDao.findNode("p1", "v1", null, "fromKey"))
                .thenReturn(Optional.of(fromNode));
        when(neo4jGraphDao.findNode("p1", "v1", null, "toKey"))
                .thenReturn(Optional.of(toNode));
        when(neo4jGraphDao.findEdge("p1", "v1", "node-1", "node-2", "CALLS", null))
                .thenReturn(Optional.of(localEdge));

        VerifiedEdge suspicious = VerifiedEdge.builder()
                .fromNodeKey("fromKey")
                .toNodeKey("toKey")
                .edgeType("CALLS")
                .confidence(0.5)
                .sourceTool("mcp")
                .build();

        VerificationResult result = VerificationResult.builder()
                .adapterName("mcp")
                .suspiciousEdges(List.of(suspicious))
                .build();

        ResultFusionEngine.FusionStats stats = engine.fuse("p1", "v1", List.of(result));

        assertEquals(1, stats.getSuspiciousMarked());
        verify(neo4jGraphDao).setEdgeProperty("edge-1", "status", "PENDING_CONFIRM");
    }

    @Test
    void fuse_exceptionInOneItem_doesNotAffectOthers() {
        // 第一条确认边抛异常（findNode 抛 RuntimeException）
        when(neo4jGraphDao.findNode("p1", "v1", null, "badKey"))
                .thenThrow(new RuntimeException("DB error"));

        // 第二条确认边正常
        GraphNode fromNode = new GraphNode();
        fromNode.setId("node-1");
        GraphNode toNode = new GraphNode();
        toNode.setId("node-2");
        GraphEdge localEdge = new GraphEdge();
        localEdge.setId("edge-1");

        when(neo4jGraphDao.findNode("p1", "v1", null, "goodFrom"))
                .thenReturn(Optional.of(fromNode));
        when(neo4jGraphDao.findNode("p1", "v1", null, "goodTo"))
                .thenReturn(Optional.of(toNode));
        when(neo4jGraphDao.findEdge("p1", "v1", "node-1", "node-2", "CALLS", null))
                .thenReturn(Optional.of(localEdge));

        VerifiedEdge badEdge = VerifiedEdge.builder()
                .fromNodeKey("badKey")
                .toNodeKey("toKey")
                .edgeType("CALLS")
                .confidence(0.9)
                .sourceTool("mcp")
                .build();

        VerifiedEdge goodEdge = VerifiedEdge.builder()
                .fromNodeKey("goodFrom")
                .toNodeKey("goodTo")
                .edgeType("CALLS")
                .confidence(0.9)
                .sourceTool("mcp")
                .build();

        VerificationResult result = VerificationResult.builder()
                .adapterName("mcp")
                .confirmedEdges(List.of(badEdge, goodEdge))
                .build();

        ResultFusionEngine.FusionStats stats = engine.fuse("p1", "v1", List.of(result));

        assertEquals(1, stats.getConfirmedCount());
        assertEquals(1, stats.getErrors());
    }

    @Test
    void fuse_nodeProperty_withoutSourceTool_writesWithoutPrefix() {
        GraphNode node = new GraphNode();
        node.setId("node-1");

        when(neo4jGraphDao.findNode("p1", "v1", null, "key"))
                .thenReturn(Optional.of(node));

        VerifiedNodeProperty prop = VerifiedNodeProperty.builder()
                .nodeKey("key")
                .propertyName("complexity")
                .propertyValue(10)
                .sourceTool(null)
                .build();

        VerificationResult result = VerificationResult.builder()
                .adapterName("test")
                .nodeProperties(List.of(prop))
                .build();

        engine.fuse("p1", "v1", List.of(result));

        verify(neo4jGraphDao).setNodeProperty("node-1", "complexity", 10);
    }
}
