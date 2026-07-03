package io.github.legacygraph.service;

import io.github.legacygraph.service.test.TestResultUpdateService;
import io.github.legacygraph.agent.TestFailureAnalysisAgent;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.TestFailureAnalysis;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TestResultUpdateServiceTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private TestResultRepository testResultRepository;
    @Mock
    private TestCaseRepository testCaseRepository;
    @Mock
    private ReviewRecordRepository reviewRecordRepository;
    @Mock
    private TestFailureAnalysisAgent testFailureAnalysisAgent;

    private TestResultUpdateService testResultUpdateService;

    @Test
    void testUpdateConfidenceByTestResults_usesBatchSelect() {
        testResultUpdateService = new TestResultUpdateService(
                neo4jGraphDao, testResultRepository,
                testCaseRepository, reviewRecordRepository, testFailureAnalysisAgent);

        TestResult r1 = new TestResult();
        r1.setId("r-1"); r1.setTestCaseId("case-1"); r1.setResultStatus("PASSED");
        TestResult r2 = new TestResult();
        r2.setId("r-2"); r2.setTestCaseId("case-2"); r2.setResultStatus("FAILED");
        r2.setErrorMessage("boom");
        when(testResultRepository.findByExecutionId("exec-1")).thenReturn(List.of(r1, r2));

        TestCase tc1 = new TestCase();
        tc1.setId("case-1"); tc1.setProjectId("p1"); tc1.setTargetNodeId("node-1");
        TestCase tc2 = new TestCase();
        tc2.setId("case-2"); tc2.setProjectId("p1"); tc2.setTargetNodeId("node-2");
        when(testCaseRepository.selectBatchIds(anyList())).thenReturn(List.of(tc1, tc2));

        // PASSED → onTestPass 也需要 findNodeById(node-1)
        // FAILED → onTestFail 需要 findNodeById(node-2)
        GraphNode node1 = new GraphNode();
        node1.setId("node-1"); node1.setProjectId("p1");
        node1.setNodeType("ApiEndpoint"); node1.setConfidence(BigDecimal.valueOf(0.8));
        GraphNode node2 = new GraphNode();
        node2.setId("node-2"); node2.setProjectId("p1");
        node2.setNodeType("Service"); node2.setConfidence(BigDecimal.valueOf(0.5));
        when(neo4jGraphDao.findNodeById("node-1")).thenReturn(Optional.of(node1));
        when(neo4jGraphDao.findNodeById("node-2")).thenReturn(Optional.of(node2));

        // PASSED: queryEdges for the passed node
        when(neo4jGraphDao.queryEdges(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        // Mock AI agent for failed test
        TestFailureAnalysis analysis = new TestFailureAnalysis();
        analysis.setSummary("分析结果");
        analysis.setTroubleshootingSteps(List.of());
        when(testFailureAnalysisAgent.analyze(any())).thenReturn(analysis);

        testResultUpdateService.updateConfidenceByTestResults("exec-1");

        // 应使用批量查询而非逐条 getById
        verify(testCaseRepository).selectBatchIds(anyList());
        verify(testCaseRepository, never()).getById(anyString());
    }

    @Test
    void testUpdateConfidenceByTestResults_emptyResults() {
        testResultUpdateService = new TestResultUpdateService(
                neo4jGraphDao, testResultRepository,
                testCaseRepository, reviewRecordRepository, testFailureAnalysisAgent);

        when(testResultRepository.findByExecutionId("exec-1")).thenReturn(Collections.emptyList());

        testResultUpdateService.updateConfidenceByTestResults("exec-1");

        // 空结果：不应调用任何 TestCase 查询
        verify(testCaseRepository, never()).selectBatchIds(anyList());
        verify(testCaseRepository, never()).getById(anyString());
    }

    @Test
    void testConstruction() {
        testResultUpdateService = new TestResultUpdateService(
                neo4jGraphDao, testResultRepository,
                testCaseRepository, reviewRecordRepository, testFailureAnalysisAgent);
        assertNotNull(testResultUpdateService);
    }

    @Test
    void testOnTestFail_AppendsAiFailureAnalysisToReviewRecord() {
        testResultUpdateService = new TestResultUpdateService(
                neo4jGraphDao, testResultRepository,
                testCaseRepository, reviewRecordRepository, testFailureAnalysisAgent);

        GraphNode node = new GraphNode();
        node.setId("node-1");
        node.setProjectId("project-1");
        node.setNodeType("ApiEndpoint");
        node.setNodeName("下单接口");
        node.setDisplayName("下单接口");
        node.setConfidence(BigDecimal.valueOf(0.8));
        when(neo4jGraphDao.findNodeById("node-1")).thenReturn(Optional.of(node));

        TestResult result = new TestResult();
        result.setId("result-1");
        result.setProjectId("project-1");
        result.setTestCaseId("case-1");
        result.setRequestData("{\"skuId\":\"SKU-1\"}");
        result.setResponseData("{\"code\":500}");
        result.setErrorMessage("库存不足");
        when(testResultRepository.selectById("result-1")).thenReturn(result);

        TestCase testCase = new TestCase();
        testCase.setCaseName("下单库存校验");
        when(testCaseRepository.selectById("case-1")).thenReturn(testCase);

        TestFailureAnalysis analysis = new TestFailureAnalysis();
        analysis.setSummary("库存校验规则与测试数据不一致");
        analysis.setTroubleshootingSteps(List.of("检查库存初始化", "复测下单接口"));
        when(testFailureAnalysisAgent.analyze(any())).thenReturn(analysis);

        testResultUpdateService.onTestFail("node-1", null, "result-1", "库存不足");

        ArgumentCaptor<TestFailureAnalysisAgent.FailureContext> contextCaptor =
                ArgumentCaptor.forClass(TestFailureAnalysisAgent.FailureContext.class);
        verify(testFailureAnalysisAgent).analyze(contextCaptor.capture());
        assertEquals("project-1", contextCaptor.getValue().getProjectId());
        assertEquals("下单库存校验", contextCaptor.getValue().getCaseName());
        assertEquals("下单接口", contextCaptor.getValue().getTargetNode());

        ArgumentCaptor<ReviewRecord> reviewCaptor = ArgumentCaptor.forClass(ReviewRecord.class);
        verify(reviewRecordRepository).insert(reviewCaptor.capture());
        assertEquals("PENDING", reviewCaptor.getValue().getStatus());
        assertTrue(reviewCaptor.getValue().getComment().contains("库存校验规则与测试数据不一致"));
        assertTrue(reviewCaptor.getValue().getComment().contains("检查库存初始化"));
    }
}
