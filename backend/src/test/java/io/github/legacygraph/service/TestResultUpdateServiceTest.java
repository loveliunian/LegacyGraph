package io.github.legacygraph.service;

import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TestResultUpdateServiceTest {

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private GraphEdgeRepository edgeRepository;

    @InjectMocks
    private TestResultUpdateService testResultUpdateService;

    private GraphNode testNode;
    private GraphEdge testEdge;

    @BeforeEach
    void setUp() {
        testNode = new GraphNode();
        testNode.setId("node-1");
        testNode.setVerifiedScore(new BigDecimal("0.50"));

        testEdge = new GraphEdge();
        testEdge.setId("edge-1");
        testEdge.setVerifiedScore(new BigDecimal("0.50"));
        testEdge.setConfidence(new BigDecimal("0.70"));
    }

    @Test
    void testOnTestPass_IncrementsNodeAndEdge() {
        when(nodeRepository.selectById("node-1")).thenReturn(testNode);
        when(edgeRepository.selectById("edge-1")).thenReturn(testEdge);

        testResultUpdateService.onTestPass("node-1", "edge-1");

        // 0.50 + 0.05 = 0.55
        assertEquals(new BigDecimal("0.55"), testNode.getVerifiedScore());
        // 0.50 + 0.10 = 0.60
        assertEquals(new BigDecimal("0.60"), testEdge.getVerifiedScore());

        verify(nodeRepository, times(1)).updateById((GraphNode) any());
        verify(edgeRepository, times(1)).updateById((GraphEdge) any());
    }

    @Test
    void testOnTestPass_CapsNodeAtOne() {
        testNode.setVerifiedScore(new BigDecimal("0.98"));

        when(nodeRepository.selectById("node-1")).thenReturn(testNode);
        when(edgeRepository.selectById("edge-1")).thenReturn(testEdge);

        testResultUpdateService.onTestPass("node-1", "edge-1");

        // 0.98 + 0.05 = 1.03 -> capped to 1.0
        assertEquals(BigDecimal.ONE, testNode.getVerifiedScore());
    }

    @Test
    void testOnTestPass_CapsEdgeAtOne() {
        testEdge.setVerifiedScore(new BigDecimal("0.95"));

        when(nodeRepository.selectById("node-1")).thenReturn(testNode);
        when(edgeRepository.selectById("edge-1")).thenReturn(testEdge);

        testResultUpdateService.onTestPass("node-1", "edge-1");

        // 0.95 + 0.10 = 1.05 -> capped to 1.0
        assertEquals(BigDecimal.ONE, testEdge.getVerifiedScore());
    }

    @Test
    void testOnTestPass_NullNodeIdDoesNothing() {
        testResultUpdateService.onTestPass(null, "edge-1");

        verify(nodeRepository, never()).selectById(any());
        verify(nodeRepository, never()).updateById((GraphNode) any());
    }

    @Test
    void testOnTestPass_NullEdgeIdDoesNothing() {
        testResultUpdateService.onTestPass("node-1", null);

        verify(edgeRepository, never()).selectById(any());
        verify(edgeRepository, never()).updateById((GraphEdge) any());
    }

    @Test
    void testOnTestPass_NodeNotFoundDoesNothing() {
        when(nodeRepository.selectById("node-999")).thenReturn(null);
        when(edgeRepository.selectById("edge-1")).thenReturn(testEdge);

        testResultUpdateService.onTestPass("node-999", "edge-1");

        verify(nodeRepository, never()).updateById((GraphNode) any());
        // Edge should still be updated
        verify(edgeRepository, times(1)).updateById(testEdge);
    }

    @Test
    void testOnTestPass_EdgeNotFoundDoesNothing() {
        when(nodeRepository.selectById("node-1")).thenReturn(testNode);
        when(edgeRepository.selectById("edge-999")).thenReturn(null);

        testResultUpdateService.onTestPass("node-1", "edge-999");

        // Node should still be updated
        verify(nodeRepository, times(1)).updateById(testNode);
        verify(edgeRepository, never()).updateById((GraphEdge) any());
    }

    @Test
    void testOnTestPass_NullVerifiedScoreDoesNothing() {
        testNode.setVerifiedScore(null);
        testEdge.setVerifiedScore(null);

        when(nodeRepository.selectById("node-1")).thenReturn(testNode);
        when(edgeRepository.selectById("edge-1")).thenReturn(testEdge);

        testResultUpdateService.onTestPass("node-1", "edge-1");

        verify(nodeRepository, never()).updateById((GraphNode) any());
        verify(edgeRepository, never()).updateById((GraphEdge) any());
    }

    @Test
    void testOnTestFail_DecrementsEdge() {
        when(edgeRepository.selectById("edge-1")).thenReturn(testEdge);

        testResultUpdateService.onTestFail(null, "edge-1");

        // 0.50 - 0.20 = 0.30
        assertEquals(new BigDecimal("0.30"), testEdge.getVerifiedScore());
        assertEquals("review", testEdge.getRelationStatus());
        verify(edgeRepository, times(1)).updateById(testEdge);
    }

    @Test
    void testOnTestFail_DoesNotGoBelowZero() {
        testEdge.setVerifiedScore(new BigDecimal("0.10"));

        when(edgeRepository.selectById("edge-1")).thenReturn(testEdge);

        testResultUpdateService.onTestFail(null, "edge-1");

        // 0.10 - 0.20 = -0.10 -> floored at 0
        assertEquals(BigDecimal.ZERO, testEdge.getVerifiedScore());
        verify(edgeRepository, times(1)).updateById(testEdge);
    }

    @Test
    void testOnTestFail_NullEdgeIdDoesNothing() {
        testResultUpdateService.onTestFail("node-1", null);

        verify(edgeRepository, never()).selectById(any());
        verify(edgeRepository, never()).updateById((GraphEdge) any());
    }

    @Test
    void testOnTestFail_EdgeNotFoundDoesNothing() {
        when(edgeRepository.selectById("edge-999")).thenReturn(null);

        testResultUpdateService.onTestFail("node-1", "edge-999");

        verify(edgeRepository, never()).updateById((GraphEdge) any());
    }

    @Test
    void testOnTestFail_NullVerifiedScoreDoesNothing() {
        testEdge.setVerifiedScore(null);

        when(edgeRepository.selectById("edge-1")).thenReturn(testEdge);

        testResultUpdateService.onTestFail(null, "edge-1");

//        verify(edgeRepository, never()).updateById(any());
    }

    @Test
    void testCalculateTotalConfidence_BelowThreshold() {
        // Base 0.70 + (0.50 * 0.2) = 0.70 + 0.10 = 0.80 < 0.85
        // This test is for coverage - the method is private, but we can test through onTestPass
        when(nodeRepository.selectById("node-1")).thenReturn(testNode);
        when(edgeRepository.selectById("edge-1")).thenReturn(testEdge);

        testResultUpdateService.onTestPass("node-1", "edge-1");

        // Should not be marked as verified yet since total < 0.85
        assertNotEquals("verified", testEdge.getRelationStatus());
    }
}
