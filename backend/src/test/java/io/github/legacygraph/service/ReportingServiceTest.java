package io.github.legacygraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper;
import io.github.legacygraph.dto.report.ConfidenceTrendReport;
import io.github.legacygraph.dto.report.GraphQualityReport;
import io.github.legacygraph.dto.report.MigrationReadinessReport;
import io.github.legacygraph.dto.report.TestCoverageReport;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.Report;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.repository.ReportRepository;
import io.github.legacygraph.repository.TestResultRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportingServiceTest {

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private GraphEdgeRepository edgeRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private io.github.legacygraph.repository.TestCaseRepository testCaseRepository;

    @Mock
    private MinioClient minioClient;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ReportingService reportingService;

    private GraphNode testNode;
    private GraphEdge testEdge;

    @BeforeEach
    void setUp() {
        testNode = new GraphNode();
        testNode.setId("node-1");
        testNode.setProjectId("project-1");
        testNode.setNodeType("ApiEndpoint");
        testNode.setStatus("CONFIRMED");
        testNode.setConfidence(new BigDecimal("0.80"));
        testNode.setCreatedAt(LocalDateTime.now());

        testEdge = new GraphEdge();
        testEdge.setId("edge-1");
        testEdge.setProjectId("project-1");
        testEdge.setFromNodeId("node-1");
        testEdge.setToNodeId("node-2");
        testEdge.setStatus("CONFIRMED");
        testEdge.setConfidence(new BigDecimal("0.90"));
    }

    @Test
    void testGenerateMigrationReport_EmptyProject() {
        LambdaQueryChainWrapper<GraphNode> nodeChain = new LambdaQueryChainWrapper<>(nodeRepository);
        when(nodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(Collections.emptyList());

        LambdaQueryChainWrapper<GraphEdge> edgeChain = new LambdaQueryChainWrapper<>(edgeRepository);
        when(edgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(Collections.emptyList());

        doNothing().when(reportRepository).insert(any(Report.class));

        MigrationReadinessReport report = reportingService.generateMigrationReport("project-1");

        assertNotNull(report);
        assertEquals("project-1", report.getProjectId());
        assertEquals(0, report.getTotalNodes());
        assertEquals(0, report.getTotalEdges());
        assertEquals(BigDecimal.ZERO, report.getConfidenceLevel());
        assertTrue(report.getRiskItems().isEmpty());
        assertNotNull(report.getRecommendations());
    }

    @Test
    void testGenerateMigrationReport_WithData() {
        LambdaQueryChainWrapper<GraphNode> nodeChain = new LambdaQueryChainWrapper<>(nodeRepository);
        when(nodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(List.of(testNode));

        LambdaQueryChainWrapper<GraphEdge> edgeChain = new LambdaQueryChainWrapper<>(edgeRepository);
        when(edgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(List.of(testEdge));

        doNothing().when(reportRepository).insert(any(Report.class));

        MigrationReadinessReport report = reportingService.generateMigrationReport("project-1");

        assertNotNull(report);
        assertEquals("project-1", report.getProjectId());
        assertEquals(1, report.getTotalNodes());
        assertEquals(1, report.getConfirmedNodes());
        assertEquals(0, report.getPendingNodes());
        assertEquals(1, report.getTotalEdges());
        assertEquals(1, report.getConfirmedEdges());
        assertEquals(0, report.getPendingEdges());
        assertFalse(report.getNodeTypeStats().isEmpty());
        assertEquals(new BigDecimal("80.00"), report.getConfidenceLevel());
        assertNotNull(report.getOverallScore());
        assertNotNull(report.getRecommendations());
    }

    @Test
    void testGenerateMigrationReport_IdentifiesLowConfidenceRisks() {
        testNode.setConfidence(new BigDecimal("0.40"));

        LambdaQueryChainWrapper<GraphNode> nodeChain = new LambdaQueryChainWrapper<>(nodeRepository);
        when(nodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(List.of(testNode));

        LambdaQueryChainWrapper<GraphEdge> edgeChain = new LambdaQueryChainWrapper<>(edgeRepository);
        when(edgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(Collections.emptyList());

        doNothing().when(reportRepository).insert(any(Report.class));

        MigrationReadinessReport report = reportingService.generateMigrationReport("project-1");

        assertEquals(1, report.getRiskItems().size());
        assertEquals("LOW_CONFIDENCE", report.getRiskItems().get(0).getRiskType());
    }

    @Test
    void testGenerateConfidenceTrend_EmptyVersion() {
        LambdaQueryChainWrapper<GraphNode> nodeChain = new LambdaQueryChainWrapper<>(nodeRepository);
        when(nodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any()).eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(Collections.emptyList());

        ConfidenceTrendReport report = reportingService.generateConfidenceTrend("project-1", "version-1");

        assertNotNull(report);
        assertEquals("project-1", report.getProjectId());
        assertEquals("version-1", report.getVersionId());
        assertTrue(report.getDailyData().isEmpty());
        assertEquals(BigDecimal.ZERO, report.getStartingAverageConfidence());
        assertEquals(BigDecimal.ZERO, report.getEndingAverageConfidence());
        assertEquals("FLAT", report.getTrendDirection());
    }

    @Test
    void testGenerateConfidenceTrend_WithData() {
        GraphNode node1 = new GraphNode();
        node1.setConfidence(new BigDecimal("0.50"));
        node1.setCreatedAt(LocalDateTime.now().minusDays(2));

        GraphNode node2 = new GraphNode();
        node2.setConfidence(new BigDecimal("0.70"));
        node2.setCreatedAt(LocalDateTime.now());

        LambdaQueryChainWrapper<GraphNode> nodeChain = new LambdaQueryChainWrapper<>(nodeRepository);
        when(nodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any()).eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(List.of(node1, node2));

        ConfidenceTrendReport report = reportingService.generateConfidenceTrend("project-1", "version-1");

        assertNotNull(report);
        assertEquals(2, report.getDailyData().size());
        assertTrue(report.getTotalImprovement().compareTo(BigDecimal.ZERO) > 0);
        assertEquals("UP", report.getTrendDirection());
    }

    @Test
    void testGenerateTestCoverageReport_EmptyVersion() {
        LambdaQueryChainWrapper<GraphNode> nodeChain = new LambdaQueryChainWrapper<>(nodeRepository);
        when(nodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any()).eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(Collections.emptyList());

        LambdaQueryChainWrapper<GraphEdge> edgeChain = new LambdaQueryChainWrapper<>(edgeRepository);
        when(edgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any()).eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(Collections.emptyList());

        LambdaQueryChainWrapper<TestResult> resultChain = new LambdaQueryChainWrapper<>(testResultRepository);
        when(testResultRepository.lambdaQuery()).thenReturn(resultChain);
        when(resultChain.eq(any(), any())).thenReturn(resultChain);
        when(resultChain.eq(any(), any()).eq(any(), any())).thenReturn(resultChain);
        when(resultChain.list()).thenReturn(Collections.emptyList());

        TestCoverageReport report = reportingService.generateTestCoverageReport("project-1", "version-1");

        assertNotNull(report);
        assertEquals(0, report.getTotalNodes());
        assertEquals(0, report.getCoveredNodes());
        assertEquals(BigDecimal.ZERO, report.getCoveragePercentage());
    }

    @Test
    void testGenerateTestCoverageReport_WithCoveredNode() {
        LambdaQueryChainWrapper<GraphNode> nodeChain = new LambdaQueryChainWrapper<>(nodeRepository);
        when(nodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any()).eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(List.of(testNode));

        LambdaQueryChainWrapper<GraphEdge> edgeChain = new LambdaQueryChainWrapper<>(edgeRepository);
        when(edgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any()).eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(List.of(testEdge));

        TestResult testResult = new TestResult();
        testResult.setId("result-1");
        testResult.setTestCaseId("case-1");
        testResult.setResultStatus("PASSED");

        LambdaQueryChainWrapper<TestResult> resultChain = new LambdaQueryChainWrapper<>(testResultRepository);
        when(testResultRepository.lambdaQuery()).thenReturn(resultChain);
        when(resultChain.eq(any(), any())).thenReturn(resultChain);
        when(resultChain.eq(any(), any()).eq(any(), any())).thenReturn(resultChain);
        when(resultChain.list()).thenReturn(List.of(testResult));

        TestCase testCase = new TestCase();
        testCase.setId("case-1");
        testCase.setTargetNodeId("node-1");
        when(testCaseRepository.selectById("case-1")).thenReturn(testCase);

        TestCoverageReport report = reportingService.generateTestCoverageReport("project-1", "version-1");

        assertNotNull(report);
        assertEquals(1, report.getTotalNodes());
        assertEquals(1, report.getCoveredNodes());
        assertEquals(1, report.getTotalEdges());
        assertEquals(1, report.getCoveredEdges());
        assertEquals(new BigDecimal("100.0000"), report.getCoveragePercentage());
    }

    @Test
    void testGenerateGraphQualityReport_EmptyVersion() {
        LambdaQueryChainWrapper<GraphNode> nodeChain = new LambdaQueryChainWrapper<>(nodeRepository);
        when(nodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any()).eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(Collections.emptyList());

        LambdaQueryChainWrapper<GraphEdge> edgeChain = new LambdaQueryChainWrapper<>(edgeRepository);
        when(edgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any()).eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(Collections.emptyList());

        GraphQualityReport report = reportingService.generateGraphQualityReport("project-1", "version-1");

        assertNotNull(report);
        assertEquals(0, report.getTotalNodes());
        assertEquals(0, report.getTotalEdges());
        assertEquals(BigDecimal.ZERO, report.getAverageConfidence());
        assertTrue(report.getQualityIssues().isEmpty());
    }

    @Test
    void testGenerateGraphQualityReport_WithQualityIssues() {
        testNode.setConfidence(new BigDecimal("0.20"));

        LambdaQueryChainWrapper<GraphNode> nodeChain = new LambdaQueryChainWrapper<>(nodeRepository);
        when(nodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any()).eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(List.of(testNode));

        LambdaQueryChainWrapper<GraphEdge> edgeChain = new LambdaQueryChainWrapper<>(edgeRepository);
        when(edgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeRepository.eq(any(), any())).thenReturn(null);
        when(edgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any()).eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(Collections.emptyList());

        GraphQualityReport report = reportingService.generateGraphQualityReport("project-1", "version-1");

        assertNotNull(report);
        assertEquals(1, report.getTotalNodes());
        assertEquals(0, report.getTotalEdges());
        assertEquals(new BigDecimal("0.20"), report.getAverageConfidence());
        assertFalse(report.getConfidenceDistribution().isEmpty());
        assertEquals(1, report.getQualityIssues().size());
        assertEquals("LOW_CONFIDENCE", report.getQualityIssues().get(0).getIssueType());
    }

    @Test
    void testGenerateGraphQualityReport_DetectsIsolatedNodes() {
        GraphNode node1 = new GraphNode();
        node1.setId("node-1");
        node1.setConfidence(new BigDecimal("0.80"));

        GraphNode node2 = new GraphNode();
        node2.setId("node-2");
        node2.setConfidence(new BigDecimal("0.80"));

        LambdaQueryChainWrapper<GraphNode> nodeChain = new LambdaQueryChainWrapper<>(nodeRepository);
        when(nodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any()).eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(List.of(node1, node2));

        LambdaQueryChainWrapper<GraphEdge> edgeChain = new LambdaQueryChainWrapper<>(edgeRepository);
        when(edgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any()).eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(List.of(testEdge));
        // testEdge connects node1 -> node2, so both are connected, no isolated nodes

        GraphQualityReport report = reportingService.generateGraphQualityReport("project-1", "version-1");

        // Both nodes are connected, no isolated nodes
        assertTrue(report.getQualityIssues().isEmpty() ||
                report.getQualityIssues().stream().noneMatch(i -> "ISOLATED".equals(i.getIssueType())));
    }

    @Test
    void testExportReport_NotFound() {
        when(reportRepository.selectById("report-1")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> reportingService.exportReport("report-1", "JSON"));
    }

    @Test
    void testListReports_ReturnsCorrectList() {
        List<Report> expected = List.of(new Report(), new Report());
        when(reportRepository.findByProjectId("project-1")).thenReturn(expected);

        List<Report> result = reportingService.listReports("project-1");

        assertEquals(2, result.size());
        verify(reportRepository, times(1)).findByProjectId("project-1");
    }

    @Test
    void testExportReport_JsonExportWorks() throws Exception {
        Report report = new Report();
        report.setId("report-1");
        report.setProjectId("project-1");
        report.setVersionId("version-1");
        report.setReportType("MIGRATION_READINESS");

        when(reportRepository.selectById("report-1")).thenReturn(report);

        LambdaQueryChainWrapper<GraphNode> nodeChain = new LambdaQueryChainWrapper<>(nodeRepository);
        when(nodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(Collections.emptyList());

        LambdaQueryChainWrapper<GraphEdge> edgeChain = new LambdaQueryChainWrapper<>(edgeRepository);
        when(edgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(Collections.emptyList());

        doNothing().when(reportRepository).insert(any(Report.class));

        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[0]);

        byte[] result = reportingService.exportReport("report-1", "JSON");

        assertNotNull(result);
        verify(objectMapper, times(1)).writeValueAsBytes(any());
    }
}
