package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphValidatorServiceTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private TestCaseRepository testCaseRepository;
    @Mock
    private TestResultRepository testResultRepository;
    @Mock
    private GraphCacheInvalidator graphCacheInvalidator;

    private GraphValidatorService graphValidatorService;

    private TestResult passedResult;
    private TestResult failedResult;
    private TestCase testCase;
    private GraphEdge testEdge;
    private GraphNode testNode;

    @BeforeEach
    void setUp() {
        graphValidatorService = new GraphValidatorService(neo4jGraphDao, testCaseRepository, testResultRepository, graphCacheInvalidator);

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
        testCase.setProjectId("p1");
        testCase.setTargetNodeId("node-1");
        testCase.setCaseType("API");

        testEdge = new GraphEdge();
        testEdge.setId("edge-1");
        testEdge.setToNodeId("node-1");
        testEdge.setVersionId("version-1");
        testEdge.setConfidence(new BigDecimal("0.7000"));
        testEdge.setStatus("PENDING");
        testEdge.setEdgeType("IMPLEMENTED_BY");

        testNode = new GraphNode();
        testNode.setId("node-1");
        testNode.setNodeType("ApiEndpoint");
        testNode.setStatus("PENDING");
        testNode.setConfidence(new BigDecimal("0.8000"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void testUpdateConfidenceByTestResults_EmptyResults() {
        when(testResultRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(Collections.emptyList());

        graphValidatorService.updateConfidenceByTestResults("version-1");
        verify(neo4jGraphDao, never()).updateEdge(any());
        verify(neo4jGraphDao, never()).updateNode(any());
    }

    @Test
    void testUpdateConfidenceByTestResults_WithPassedResult() {
        when(testResultRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(List.of(passedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(testCase);
        when(neo4jGraphDao.queryEdges(isNull(), eq("version-1"), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(testEdge));

        graphValidatorService.updateConfidenceByTestResults("version-1");

        assertEquals(new BigDecimal("0.7500"), testEdge.getConfidence());
        verify(neo4jGraphDao, times(1)).updateEdge(testEdge);
    }

    @Test
    void testUpdateConfidenceByTestResults_PassedResultIncreasesToOverZeroEight() {
        testEdge.setConfidence(new BigDecimal("0.7800"));

        when(testResultRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(List.of(passedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(testCase);
        when(neo4jGraphDao.queryEdges(isNull(), eq("version-1"), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(testEdge));

        graphValidatorService.updateConfidenceByTestResults("version-1");

        assertEquals(new BigDecimal("0.8300"), testEdge.getConfidence());
        assertEquals("CONFIRMED", testEdge.getStatus());
        verify(neo4jGraphDao, times(1)).updateEdge(testEdge);
    }

    @Test
    void testUpdateConfidenceByTestResults_CapsAtOne() {
        testEdge.setConfidence(BigDecimal.ONE);

        when(testResultRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(List.of(passedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(testCase);
        when(neo4jGraphDao.queryEdges(isNull(), eq("version-1"), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(testEdge));

        graphValidatorService.updateConfidenceByTestResults("version-1");

        assertEquals(0, BigDecimal.ONE.compareTo(testEdge.getConfidence()));
        verify(neo4jGraphDao, times(1)).updateEdge(testEdge);
    }

    @Test
    void testUpdateConfidenceByTestResults_FailedApiResultWith404() {
        failedResult.setResponseData("{\"status\": 404, \"error\": \"Not Found\"}");

        when(testResultRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(List.of(failedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(testCase);
        when(neo4jGraphDao.findNodeById("node-1")).thenReturn(Optional.of(testNode));

        graphValidatorService.updateConfidenceByTestResults("version-1");

        assertEquals("INVALID_CANDIDATE", testNode.getStatus());
        verify(neo4jGraphDao, times(1)).updateNode(testNode);
    }

    @Test
    void testUpdateConfidenceByTestResults_FailedApiResultNot404() {
        failedResult.setResponseData("{\"status\": 500, \"error\": \"Server Error\"}");

        when(testResultRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(List.of(failedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(testCase);
        when(neo4jGraphDao.queryEdges(eq("p1"), eq("version-1"), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(testEdge));

        graphValidatorService.updateConfidenceByTestResults("version-1");

        assertEquals(new BigDecimal("0.6000"), testEdge.getConfidence());
        verify(neo4jGraphDao, times(1)).updateEdge(testEdge);
    }

    @Test
    void testConfirmNode_NodeExists() {
        when(neo4jGraphDao.findNodeById("node-1")).thenReturn(Optional.of(testNode));
        graphValidatorService.confirmNode("node-1", "tester");
        assertEquals("CONFIRMED", testNode.getStatus());
        assertEquals(BigDecimal.ONE, testNode.getConfidence());
        verify(neo4jGraphDao, times(1)).updateNode(testNode);
    }

    @Test
    void testConfirmNode_NodeNotFound() {
        when(neo4jGraphDao.findNodeById("node-999")).thenReturn(Optional.empty());
        graphValidatorService.confirmNode("node-999", "tester");
        verify(neo4jGraphDao, never()).updateNode(any());
    }

    @Test
    void testRejectNode_NodeExists() {
        when(neo4jGraphDao.findNodeById("node-1")).thenReturn(Optional.of(testNode));
        graphValidatorService.rejectNode("node-1", "tester");
        assertEquals("REJECTED", testNode.getStatus());
        verify(neo4jGraphDao, times(1)).updateNode(testNode);
    }

    @Test
    void testConfirmEdge_EdgeExists() {
        when(neo4jGraphDao.queryEdges(isNull(), isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(testEdge));
        graphValidatorService.confirmEdge("edge-1", "tester");
        assertEquals("CONFIRMED", testEdge.getStatus());
        assertEquals(BigDecimal.ONE, testEdge.getConfidence());
        verify(neo4jGraphDao, times(1)).updateEdge(testEdge);
    }

    @Test
    void testRejectEdge_EdgeExists() {
        when(neo4jGraphDao.queryEdges(isNull(), isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(testEdge));
        graphValidatorService.rejectEdge("edge-1", "tester");
        assertEquals("REJECTED", testEdge.getStatus());
        verify(neo4jGraphDao, times(1)).updateEdge(testEdge);
    }

    @Test
    void testGetValidationReport_EmptyVersion() {
        when(neo4jGraphDao.countNodes(any(), eq("version-1"), any())).thenReturn(0L);
        when(neo4jGraphDao.countEdges(any(), eq("version-1"), any())).thenReturn(0L);
        when(testResultRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).eq(any(), any()).count()).thenReturn(0L);
        when(neo4jGraphDao.queryNodes(any(), eq("version-1"), any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

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
        when(neo4jGraphDao.countNodes(isNull(), eq("version-1"), isNull())).thenReturn(10L);
        when(neo4jGraphDao.countNodes(isNull(), eq("version-1"), eq("CONFIRMED"))).thenReturn(5L);
        when(neo4jGraphDao.countNodes(isNull(), eq("version-1"), eq("PENDING_CONFIRM"))).thenReturn(3L);
        when(neo4jGraphDao.countEdges(isNull(), eq("version-1"), isNull())).thenReturn(20L);
        when(neo4jGraphDao.countEdges(isNull(), eq("version-1"), eq("CONFIRMED"))).thenReturn(10L);
        when(neo4jGraphDao.countEdges(isNull(), eq("version-1"), eq("PENDING_CONFIRM"))).thenReturn(5L);
        when(neo4jGraphDao.queryNodes(isNull(), eq("version-1"), isNull(), isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(testNode));

        when(testResultRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).eq(any(), any()).count()).thenReturn(3L, 2L);

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
        when(testResultRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(List.of(passedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(null);

        graphValidatorService.updateConfidenceByTestResults("version-1");
        verify(neo4jGraphDao, never()).updateEdge(any());
    }

    @Test
    void testUpdateConfidenceByTestResults_NullTargetNodeIdDoesNothing() {
        testCase.setTargetNodeId(null);

        when(testResultRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(List.of(passedResult));
        when(testCaseRepository.getById("case-1")).thenReturn(testCase);

        graphValidatorService.updateConfidenceByTestResults("version-1");
        verify(neo4jGraphDao, never()).updateEdge(any());
    }
}
