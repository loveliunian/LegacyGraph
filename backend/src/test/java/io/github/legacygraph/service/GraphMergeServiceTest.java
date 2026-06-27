package io.github.legacygraph.service;

import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphMergeServiceTest {

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private GraphEdgeRepository edgeRepository;

    @InjectMocks
    private GraphMergeService graphMergeService;

    private String testProjectId = "test-project-1";

    @Test
    void testFindMergeCandidates_EmptyList() {
        when(nodeRepository.findByProjectIdAndNodeType(testProjectId, "ENTITY"))
                .thenReturn(new ArrayList<>());

        List<GraphMergeService.MergeCandidate> candidates =
                graphMergeService.findMergeCandidates(testProjectId, "ENTITY");

        assertNotNull(candidates);
        assertTrue(candidates.isEmpty());
    }

    @Test
    void testFindMergeCandidates_SingleNode() {
        List<GraphNode> nodes = new ArrayList<>();
        GraphNode node = new GraphNode();
        node.setId("node-1");
        nodes.add(node);

        when(nodeRepository.findByProjectIdAndNodeType(testProjectId, "ENTITY"))
                .thenReturn(nodes);

        List<GraphMergeService.MergeCandidate> candidates =
                graphMergeService.findMergeCandidates(testProjectId, "ENTITY");

        assertTrue(candidates.isEmpty()); // 需要至少两个节点才能生成候选对
    }

    @Test
    void testFindMergeCandidates_TwoNodes() {
        List<GraphNode> nodes = new ArrayList<>();
        GraphNode node1 = new GraphNode();
        node1.setId("node-1");
        node1.setNodeName("UserService");
        node1.setNodeType("ENTITY");
        node1.setDeleted(0);
        nodes.add(node1);

        GraphNode node2 = new GraphNode();
        node2.setId("node-2");
        node2.setNodeName("UserMgr");
        node2.setNodeType("ENTITY");
        node2.setDeleted(0);
        nodes.add(node2);

        when(nodeRepository.findByProjectIdAndNodeType(testProjectId, "ENTITY"))
                .thenReturn(nodes);

        List<GraphMergeService.MergeCandidate> candidates =
                graphMergeService.findMergeCandidates(testProjectId, "ENTITY");

        assertEquals(1, candidates.size());
        GraphMergeService.MergeCandidate candidate = candidates.get(0);
        assertEquals("node-1", candidate.getNodeAId());
        assertEquals("node-2", candidate.getNodeBId());
        assertTrue(candidate.getSimilarityScore() > 0);
    }

    @Test
    void testFindMergeCandidates_SkipDeletedNodes() {
        List<GraphNode> nodes = new ArrayList<>();
        GraphNode node1 = new GraphNode();
        node1.setId("node-1");
        node1.setNodeName("UserService");
        node1.setDeleted(1); // deleted
        nodes.add(node1);

        GraphNode node2 = new GraphNode();
        node2.setId("node-2");
        node2.setNodeName("UserMgr");
        node2.setDeleted(0);
        nodes.add(node2);

        when(nodeRepository.findByProjectIdAndNodeType(testProjectId, "ENTITY"))
                .thenReturn(nodes);

        List<GraphMergeService.MergeCandidate> candidates =
                graphMergeService.findMergeCandidates(testProjectId, "ENTITY");

        assertTrue(candidates.isEmpty());
    }

    @Test
    void testCalculateNameScore_ExactMatch() {
        double score = graphMergeService.calculateNameScore("UserService", "UserService");
        assertEquals(1.0, score, 0.001);
    }

    @Test
    void testCalculateNameScore_PartialMatch() {
        double score = graphMergeService.calculateNameScore("UserService", "UserMgr");
        assertTrue(score > 0.3); // Some similarity
        assertTrue(score < 1.0);
    }

    @Test
    void testCalculateNameScore_CompletelyDifferent() {
        double score = graphMergeService.calculateNameScore("UserService", "OrderProcessor");
        assertTrue(score < 0.5);
    }

    @Test
    void testCalculateNameScore_Empty() {
        double score = graphMergeService.calculateNameScore("", "UserService");
        assertEquals(0, score, 0.001);
    }

    @Test
    void testMergeCandidateStructure() {
        GraphMergeService.MergeCandidate candidate = new GraphMergeService.MergeCandidate();
        candidate.setNodeAId("a");
        candidate.setNodeBId("b");
        candidate.setSimilarityScore(0.85);
        candidate.setNameScore(0.9);
        candidate.setSemanticScore(0.8);

        assertEquals("a", candidate.getNodeAId());
        assertEquals("b", candidate.getNodeBId());
        assertEquals(0.85, candidate.getSimilarityScore(), 0.001);
        assertEquals(0.9, candidate.getNameScore(), 0.001);
        assertEquals(0.8, candidate.getSemanticScore(), 0.001);
    }

    @Test
    void testDecideMerge_NodesExist() {
        GraphNode a = new GraphNode();
        a.setId("node-1");
        a.setNodeName("UserService");

        GraphNode b = new GraphNode();
        b.setId("node-2");
        b.setNodeName("UserMgr");

        // We don't mock the agent, just test the service method signature
        GraphMergeDecision decision = graphMergeService.decideMerge(testProjectId, a, b,
                new GraphMergeService.MergeCandidate());

        // The decision might be null if LLM is not configured, but method should return something
        // or throw. We just check it doesn't crash.
        assertNotNull(decision);
    }
}
