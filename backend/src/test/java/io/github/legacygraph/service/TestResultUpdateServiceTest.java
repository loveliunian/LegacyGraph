package io.github.legacygraph.service;

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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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
