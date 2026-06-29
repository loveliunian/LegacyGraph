package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphValidatorServiceTest {

    @Mock
    private GraphNodeRepository graphNodeRepository;

    @Mock
    private GraphEdgeRepository graphEdgeRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @InjectMocks
    private GraphValidatorService graphValidatorService;

    private TestResult passedResult;
    private TestResult failedResult;
    private TestCase testCase;
    private GraphEdge testEdge;
    private GraphNode testNode;

    @BeforeEach
    void setUp() {
        passedResult = new TestResult();
        passedResult.setVersionId("version-1");
        passedResult.setResultStatus("PASSED");
        passedResult.setTestCaseId("case-1");

        failedResult = new TestResult();
        failedResult.setVersionId("version-1");
        failedResult.setResultStatus("FAILED");
        failedResult.setTestCaseId("case-1");

        testCase = new TestCase();
        testCase.setId("case-1");
        testCase.setTargetNodeId("node-1");
        testCase.setCaseType("API");

        testEdge = new GraphEdge();
        testEdge.setId("edge-1");
        testEdge.setVersionId("version-1");
        testEdge.setConfidence(new BigDecimal("0.7000"));
        testEdge.setStatus("PENDING");

        testNode = new GraphNode();
        testNode.setId("node-1");
        testNode.setNodeType("ApiEndpoint");
        testNode.setStatus("PENDING");
        testNode.setConfidence(new BigDecimal("0.8000"));
    }

    @Test
    void testUpdateConfidenceByTestResults_EmptyResults() {
        LambdaQueryChainWrapper<TestResult> chain = mock(LambdaQueryChainWrapper.class);
        when(testResultRepository.lambdaQuery()).thenReturn(chain);
        when(chain.eq(any(), any())).thenReturn(chain);
        when(chain.list()).thenReturn(Collections.emptyList());

        graphValidatorService.updateConfidenceByTestResults("version-1");

        // No updates should occur
        verify(graphEdgeRepository, never()).updateById(any(GraphEdge.class));
        verify(graphNodeRepository, never()).updateById(any(GraphNode.class));
    }

    @Test
    void testUpdateConfidenceByTestResults_WithPassedResult() {
        LambdaQueryChainWrapper<TestResult> resultChain = mock(LambdaQueryChainWrapper.class);
        when(testResultRepository.lambdaQuery()).thenReturn(resultChain);
        when(resultChain.eq(any(), any())).thenReturn(resultChain);
        when(resultChain.list()).thenReturn(List.of(passedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(testCase);

        LambdaQueryChainWrapper<GraphEdge> edgeChain = mock(LambdaQueryChainWrapper.class);
        when(graphEdgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.and(any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(List.of(testEdge));

        graphValidatorService.updateConfidenceByTestResults("version-1");

        // Confidence should increase by 0.05
        assertEquals(new BigDecimal("0.7500"), testEdge.getConfidence());
        verify(graphEdgeRepository, times(1)).updateById(testEdge);
    }

    @Test
    void testUpdateConfidenceByTestResults_PassedResultIncreasesToOverZeroEight() {
        testEdge.setConfidence(new BigDecimal("0.7800"));

        LambdaQueryChainWrapper<TestResult> resultChain = mock(LambdaQueryChainWrapper.class);
        when(testResultRepository.lambdaQuery()).thenReturn(resultChain);
        when(resultChain.eq(any(), any())).thenReturn(resultChain);
        when(resultChain.list()).thenReturn(List.of(passedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(testCase);

        LambdaQueryChainWrapper<GraphEdge> edgeChain = mock(LambdaQueryChainWrapper.class);
        when(graphEdgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.and(any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(List.of(testEdge));

        graphValidatorService.updateConfidenceByTestResults("version-1");

        assertEquals(new BigDecimal("0.8300"), testEdge.getConfidence());
        assertEquals("CONFIRMED", testEdge.getStatus());
        verify(graphEdgeRepository, times(1)).updateById(testEdge);
    }

    @Test
    void testUpdateConfidenceByTestResults_CapsAtOne() {
        testEdge.setConfidence(BigDecimal.ONE);

        LambdaQueryChainWrapper<TestResult> resultChain = mock(LambdaQueryChainWrapper.class);
        when(testResultRepository.lambdaQuery()).thenReturn(resultChain);
        when(resultChain.eq(any(), any())).thenReturn(resultChain);
        when(resultChain.list()).thenReturn(List.of(passedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(testCase);

        LambdaQueryChainWrapper<GraphEdge> edgeChain = mock(LambdaQueryChainWrapper.class);
        when(graphEdgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.and(any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(List.of(testEdge));

        graphValidatorService.updateConfidenceByTestResults("version-1");

        // Should still be 1.0
        assertEquals(BigDecimal.ONE, testEdge.getConfidence());
        verify(graphEdgeRepository, times(1)).updateById(testEdge);
    }

    @Test
    void testUpdateConfidenceByTestResults_FailedApiResultWith404() {
        failedResult.setResponseData("{\"status\": 404, \"error\": \"Not Found\"}");

        LambdaQueryChainWrapper<TestResult> resultChain = mock(LambdaQueryChainWrapper.class);
        when(testResultRepository.lambdaQuery()).thenReturn(resultChain);
        when(resultChain.eq(any(), any())).thenReturn(resultChain);
        when(resultChain.list()).thenReturn(List.of(failedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(testCase);
        when(graphNodeRepository.getById("node-1")).thenReturn(testNode);

        graphValidatorService.updateConfidenceByTestResults("version-1");

        assertEquals("INVALID_CANDIDATE", testNode.getStatus());
        verify(graphNodeRepository, times(1)).updateById(testNode);
    }

    @Test
    void testUpdateConfidenceByTestResults_FailedApiResultNot404() {
        failedResult.setResponseData("{\"status\": 500, \"error\": \"Server Error\"}");

        LambdaQueryChainWrapper<TestResult> resultChain = mock(LambdaQueryChainWrapper.class);
        when(testResultRepository.lambdaQuery()).thenReturn(resultChain);
        when(resultChain.eq(any(), any())).thenReturn(resultChain);
        when(resultChain.list()).thenReturn(List.of(failedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(testCase);

        LambdaQueryChainWrapper<GraphEdge> edgeChain = mock(LambdaQueryChainWrapper.class);
        when(graphEdgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.in(any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(List.of(testEdge));

        graphValidatorService.updateConfidenceByTestResults("version-1");

        // 0.7 - 0.1 = 0.6
        assertEquals(new BigDecimal("0.6000"), testEdge.getConfidence());
        assertEquals("PENDING_CONFIRM", testEdge.getStatus());
        verify(graphEdgeRepository, times(1)).updateById(testEdge);
    }

    @Test
    void testUpdateConfidenceByTestResults_FailedDbAssertion() {
        testCase.setCaseType("DB_ASSERTION");

        LambdaQueryChainWrapper<TestResult> resultChain = mock(LambdaQueryChainWrapper.class);
        when(testResultRepository.lambdaQuery()).thenReturn(resultChain);
        when(resultChain.eq(any(), any())).thenReturn(resultChain);
        when(resultChain.list()).thenReturn(List.of(failedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(testCase);

        LambdaQueryChainWrapper<GraphEdge> edgeChain = mock(LambdaQueryChainWrapper.class);
        when(graphEdgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.in(any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(List.of(testEdge));

        graphValidatorService.updateConfidenceByTestResults("version-1");

        // 0.7 - 0.15 = 0.55
        assertEquals(new BigDecimal("0.5500"), testEdge.getConfidence());
        verify(graphEdgeRepository, times(1)).updateById(testEdge);
    }

    @Test
    void testUpdateConfidenceByTestResults_FailedPermissionAssertion() {
        testCase.setCaseType("PERMISSION");

        LambdaQueryChainWrapper<TestResult> resultChain = mock(LambdaQueryChainWrapper.class);
        when(testResultRepository.lambdaQuery()).thenReturn(resultChain);
        when(resultChain.eq(any(), any())).thenReturn(resultChain);
        when(resultChain.list()).thenReturn(List.of(failedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(testCase);

        LambdaQueryChainWrapper<GraphEdge> edgeChain = mock(LambdaQueryChainWrapper.class);
        when(graphEdgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(List.of(testEdge));

        graphValidatorService.updateConfidenceByTestResults("version-1");

        // 0.7 - 0.2 = 0.5
        assertEquals(new BigDecimal("0.5000"), testEdge.getConfidence());
        verify(graphEdgeRepository, times(1)).updateById(testEdge);
    }

    @Test
    void testConfirmNode_NodeExists() {
        when(graphNodeRepository.getById("node-1")).thenReturn(testNode);

        graphValidatorService.confirmNode("node-1", "tester");

        assertEquals("CONFIRMED", testNode.getStatus());
        assertEquals(BigDecimal.ONE, testNode.getConfidence());
        verify(graphNodeRepository, times(1)).updateById(testNode);
    }

    @Test
    void testConfirmNode_NodeNotFound() {
        when(graphNodeRepository.getById("node-999")).thenReturn(null);

        graphValidatorService.confirmNode("node-999", "tester");

        verify(graphNodeRepository, never()).updateById(any(GraphNode.class));
    }

    @Test
    void testRejectNode_NodeExists() {
        when(graphNodeRepository.getById("node-1")).thenReturn(testNode);

        graphValidatorService.rejectNode("node-1", "tester");

        assertEquals("REJECTED", testNode.getStatus());
        verify(graphNodeRepository, times(1)).updateById(testNode);
    }

    @Test
    void testConfirmEdge_EdgeExists() {
        when(graphEdgeRepository.getById("edge-1")).thenReturn(testEdge);

        graphValidatorService.confirmEdge("edge-1", "tester");

        assertEquals("CONFIRMED", testEdge.getStatus());
        assertEquals(BigDecimal.ONE, testEdge.getConfidence());
        verify(graphEdgeRepository, times(1)).updateById(testEdge);
    }

    @Test
    void testRejectEdge_EdgeExists() {
        when(graphEdgeRepository.getById("edge-1")).thenReturn(testEdge);

        graphValidatorService.rejectEdge("edge-1", "tester");

        assertEquals("REJECTED", testEdge.getStatus());
        verify(graphEdgeRepository, times(1)).updateById(testEdge);
    }

    @Test
    void testGetValidationReport_EmptyVersion() {
        LambdaQueryChainWrapper<GraphNode> nodeChain = mock(LambdaQueryChainWrapper.class);
        when(graphNodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.count()).thenReturn(0L);

        LambdaQueryChainWrapper<GraphEdge> edgeChain = mock(LambdaQueryChainWrapper.class);
        when(graphEdgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.count()).thenReturn(0L);

        LambdaQueryChainWrapper<TestResult> resultChain = mock(LambdaQueryChainWrapper.class);
        when(testResultRepository.lambdaQuery()).thenReturn(resultChain);
        when(resultChain.eq(any(), any())).thenReturn(resultChain);
        when(resultChain.eq(any(), any()).eq(any(), any())).thenReturn(resultChain);
        when(resultChain.count()).thenReturn(0L);

        GraphValidatorService.ValidationReport report = graphValidatorService.getValidationReport("version-1");

        assertNotNull(report);
        assertEquals("version-1", report.getVersionId());
        assertEquals(0, report.getTotalNodes());
        assertEquals(0, report.getConfirmedNodes());
        assertEquals(0, report.getPendingNodes());
        assertEquals(0, report.getTotalEdges());
        assertEquals(0, report.getConfirmedEdges());
        assertEquals(0, report.getPendingEdges());
        assertEquals(0, report.getPassedTests());
        assertEquals(0, report.getFailedTests());
        assertEquals(BigDecimal.ZERO, report.getOverallConfidence());
    }

    @Test
    void testGetValidationReport_WithData() {
        LambdaQueryChainWrapper<GraphNode> nodeChain = mock(LambdaQueryChainWrapper.class);
        when(graphNodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.count()).thenReturn(10L);

        LambdaQueryChainWrapper<GraphNode> confirmedNodeChain = mock(LambdaQueryChainWrapper.class);
        when(graphNodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any()).eq(any(), any())).thenReturn(confirmedNodeChain);
        when(confirmedNodeChain.count()).thenReturn(5L);

        LambdaQueryChainWrapper<GraphNode> pendingNodeChain = mock(LambdaQueryChainWrapper.class);
        when(graphNodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any()).eq(any(), any()).eq(any(), any())).thenReturn(pendingNodeChain);
        when(pendingNodeChain.count()).thenReturn(3L);
        when(nodeChain.list()).thenReturn(List.of(testNode));

        LambdaQueryChainWrapper<GraphEdge> edgeChain = mock(LambdaQueryChainWrapper.class);
        when(graphEdgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.count()).thenReturn(20L);

        LambdaQueryChainWrapper<GraphEdge> confirmedEdgeChain = mock(LambdaQueryChainWrapper.class);
        when(graphEdgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any()).eq(any(), any())).thenReturn(confirmedEdgeChain);
        when(confirmedEdgeChain.count()).thenReturn(10L);

        LambdaQueryChainWrapper<GraphEdge> pendingEdgeChain = mock(LambdaQueryChainWrapper.class);
        when(graphEdgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any()).eq(any(), any()).eq(any(), any())).thenReturn(pendingEdgeChain);
        when(pendingEdgeChain.count()).thenReturn(5L);

        LambdaQueryChainWrapper<TestResult> passedChain = mock(LambdaQueryChainWrapper.class);
        when(testResultRepository.lambdaQuery()).thenReturn(passedChain);
        when(passedChain.eq(any(), any())).thenReturn(passedChain);
        when(passedChain.eq(any(), any()).eq(any(), any())).thenReturn(passedChain);
        when(passedChain.count()).thenReturn(3L);

        LambdaQueryChainWrapper<TestResult> failedChain = mock(LambdaQueryChainWrapper.class);
        when(testResultRepository.lambdaQuery()).thenReturn(failedChain);
        when(failedChain.eq(any(), any())).thenReturn(failedChain);
        when(failedChain.eq(any(), any()).eq(any(), any()).eq(any(), any())).thenReturn(failedChain);
        when(failedChain.count()).thenReturn(2L);

        GraphValidatorService.ValidationReport report = graphValidatorService.getValidationReport("version-1");

        assertNotNull(report);
        assertEquals("version-1", report.getVersionId());
        assertEquals(10, report.getTotalNodes());
        assertEquals(5, report.getConfirmedNodes());
        assertEquals(3, report.getPendingNodes());
        assertEquals(20, report.getTotalEdges());
        assertEquals(10, report.getConfirmedEdges());
        assertEquals(5, report.getPendingEdges());
        assertEquals(3, report.getPassedTests());
        assertEquals(2, report.getFailedTests());
        assertNotNull(report.getOverallConfidence());
    }

    @Test
    void testUpdateConfidenceByTestResults_NullTestCaseDoesNothing() {
        LambdaQueryChainWrapper<TestResult> resultChain = mock(LambdaQueryChainWrapper.class);
        when(testResultRepository.lambdaQuery()).thenReturn(resultChain);
        when(resultChain.eq(any(), any())).thenReturn(resultChain);
        when(resultChain.list()).thenReturn(List.of(passedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(null);

        graphValidatorService.updateConfidenceByTestResults("version-1");

        verify(graphEdgeRepository, never()).updateById(any(GraphEdge.class));
    }

    @Test
    void testUpdateConfidenceByTestResults_NullTargetNodeIdDoesNothing() {
        testCase.setTargetNodeId(null);

        LambdaQueryChainWrapper<TestResult> resultChain = mock(LambdaQueryChainWrapper.class);
        when(testResultRepository.lambdaQuery()).thenReturn(resultChain);
        when(resultChain.eq(any(), any())).thenReturn(resultChain);
        when(resultChain.list()).thenReturn(List.of(passedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(testCase);

        graphValidatorService.updateConfidenceByTestResults("version-1");

        verify(graphEdgeRepository, never()).updateById(any(GraphEdge.class));
    }
}
