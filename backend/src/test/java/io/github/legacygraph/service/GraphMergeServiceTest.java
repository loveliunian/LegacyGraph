package io.github.legacygraph.service;

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
        edge1.setSourceNodeId("node-1");
        edge1.setTargetNodeId("node-2");
        edge1.setEdgeType("CALLS");
        edge1.setConfidence(BigDecimal.valueOf(0.85));
        edge1.setStatus("PENDING");

        mockEdges = Arrays.asList(edge1);
    }

    @Test
    void testMergeNodes_Success() {
        when(graphNodeRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(mockNodes);

        when(graphNodeRepository.updateById(any(GraphNode.class))).thenReturn(1);

        int mergedCount = graphMergeService.mergeNodes("project-1");

        assertTrue(mergedCount >= 0);
        verify(graphNodeRepository, atLeastOnce()).updateById(any(GraphNode.class));
    }

    @Test
    void testMergeEdges_Success() {
        when(graphEdgeRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphEdgeRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphEdgeRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(mockEdges);

        when(graphEdgeRepository.updateById(any(GraphEdge.class))).thenReturn(1);

        int mergedCount = graphMergeService.mergeEdges("project-1");

        assertTrue(mergedCount >= 0);
        verify(graphEdgeRepository, atLeastOnce()).updateById(any(GraphEdge.class));
    }

    @Test
    void testMergeNodes_EmptyList() {
        when(graphNodeRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphNodeRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(Collections.emptyList());

        int mergedCount = graphMergeService.mergeNodes("project-1");

        assertEquals(0, mergedCount);
        verify(graphNodeRepository, never()).updateById(any(GraphNode.class));
    }

    @Test
    void testMergeEdges_EmptyList() {
        when(graphEdgeRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphEdgeRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(graphEdgeRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(Collections.emptyList());

        int mergedCount = graphMergeService.mergeEdges("project-1");

        assertEquals(0, mergedCount);
        verify(graphEdgeRepository, never()).updateById(any(GraphEdge.class));
    }

    @Test
    void testCalculateCombinedConfidence() {
        List<BigDecimal> confidences = Arrays.asList(
                BigDecimal.valueOf(0.9),
                BigDecimal.valueOf(0.8),
                BigDecimal.valueOf(0.7)
        );

        BigDecimal result = graphMergeService.calculateCombinedConfidence(confidences);

        assertNotNull(result);
        assertTrue(result.compareTo(BigDecimal.ZERO) > 0);
        assertTrue(result.compareTo(BigDecimal.ONE) <= 0);
    }

    @Test
    void testCalculateCombinedConfidence_EmptyList() {
        BigDecimal result = graphMergeService.calculateCombinedConfidence(Collections.emptyList());

        assertNotNull(result);
        assertEquals(BigDecimal.ZERO, result);
    }

    @Test
    void testCalculateCombinedConfidence_SingleValue() {
        BigDecimal result = graphMergeService.calculateCombinedConfidence(
                Collections.singletonList(BigDecimal.valueOf(0.85))
        );

        assertNotNull(result);
        assertEquals(0, result.compareTo(BigDecimal.valueOf(0.85)));
    }
}
