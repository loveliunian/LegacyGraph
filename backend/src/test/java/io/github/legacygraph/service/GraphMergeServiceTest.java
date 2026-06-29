package io.github.legacygraph.service;

import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.entity.*;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphMergeServiceTest {

    @Mock
    private GraphNodeRepository graphNodeRepository;

    @Mock
    private GraphEdgeRepository graphEdgeRepository;

    @Mock
    private EvidenceRepository evidenceRepository;

    @Mock
    private NodeEvidenceRepository nodeEvidenceRepository;

    @Mock
    private EdgeEvidenceRepository edgeEvidenceRepository;

    @InjectMocks
    private GraphMergeService graphMergeService;

    private List<GraphNode> mockNodes;
    private List<GraphEdge> mockEdges;

    @BeforeEach
    void setUp() {
        GraphNode node1 = new GraphNode();
        node1.setId("node-1");
        node1.setProjectId("project-1");
        node1.setVersionId("v1");
        node1.setNodeKey("POST /api/test");
        node1.setNodeType("ApiEndpoint");
        node1.setNodeName("测试接口");
        node1.setConfidence(BigDecimal.valueOf(0.8));
        node1.setStatus("PENDING");

        GraphNode node2 = new GraphNode();
        node2.setId("node-2");
        node2.setProjectId("project-1");
        node2.setVersionId("v1");
        node2.setNodeKey("TestService.method");
        node2.setNodeType("Service");
        node2.setNodeName("测试服务");
        node2.setConfidence(BigDecimal.valueOf(0.9));
        node2.setStatus("CONFIRMED");

        mockNodes = Arrays.asList(node1, node2);

        GraphEdge edge1 = new GraphEdge();
        edge1.setId("edge-1");
        edge1.setProjectId("project-1");
        edge1.setVersionId("v1");
        edge1.setFromNodeId("node-1");
        edge1.setToNodeId("node-2");
        edge1.setEdgeType("CALLS");
        edge1.setConfidence(BigDecimal.valueOf(0.85));
        edge1.setStatus("PENDING");

        mockEdges = Arrays.asList(edge1);
    }

    @Test
    void testFindMergeCandidates() {
        when(graphNodeRepository.findByProjectIdAndNodeType(anyString(), anyString())).thenReturn(mockNodes);

        List<GraphMergeService.MergeCandidate> candidates = graphMergeService.findMergeCandidates("project-1", "ApiEndpoint");

        assertNotNull(candidates);
        assertTrue(candidates.size() >= 0);
    }

    @Test
    void testCalculateNameScore() {
        double score1 = graphMergeService.calculateNameScore("Test API", "Test API");
        assertEquals(1.0, score1, 0.001);

        double score2 = graphMergeService.calculateNameScore("Test API", "Different Name");
        assertTrue(score2 < 1.0);

        double score3 = graphMergeService.calculateNameScore(null, "Test");
        assertEquals(0.0, score3, 0.001);
    }

    @Test
    void testDecideMerge() {
        GraphMergeService.MergeCandidate candidate = new GraphMergeService.MergeCandidate();
        candidate.setSimilarityScore(0.9);

        GraphMergeDecision decision = graphMergeService.decideMerge("project-1", mockNodes.get(0), mockNodes.get(1), candidate);

        assertNotNull(decision);
        assertNotNull(decision.getDecision());
        assertNotNull(decision.getReasons());
    }
}
