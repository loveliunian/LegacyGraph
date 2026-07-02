package io.github.legacygraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.report.MigrationReadinessReport;
import io.github.legacygraph.dto.report.TestCoverageReport;
import io.github.legacygraph.dto.report.GraphQualityReport;
import io.github.legacygraph.dto.report.ConfidenceTrendReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportExportServiceTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ReportingService reportingService;

    @Mock
    private ChangeReportService changeReportService;

    @Mock
    private ScanResearchReportService scanResearchReportService;

    private ReportExportService reportExportService;

    @BeforeEach
    void setUp() {
        reportExportService = new ReportExportService(objectMapper, reportingService, changeReportService, scanResearchReportService);
    }

    @Test
    void testExportMigrationMarkdown_ContainsReportData() {
        // given
        MigrationReadinessReport mockReport = new MigrationReadinessReport();
        mockReport.setProjectId("project-1");
        mockReport.setTotalNodes(10L);
        mockReport.setConfirmedNodes(7L);
        mockReport.setPendingNodes(3L);
        mockReport.setTotalEdges(15L);
        mockReport.setConfirmedEdges(10L);
        mockReport.setPendingEdges(5L);
        mockReport.setOverallScore(new java.math.BigDecimal("75.50"));
        mockReport.setArchitectureUnderstandingScore(new java.math.BigDecimal("80.00"));
        mockReport.setBusinessKnowledgeScore(new java.math.BigDecimal("70.00"));
        mockReport.setTestCoverageScore(new java.math.BigDecimal("65.00"));
        mockReport.setConfidenceLevel(new java.math.BigDecimal("72.00"));
        mockReport.setNodeTypeStats(java.util.Collections.emptyList());
        mockReport.setRiskItems(java.util.Collections.emptyList());
        mockReport.setRecommendations(java.util.Collections.emptyList());

        when(reportingService.generateMigrationReport("project-1")).thenReturn(mockReport);

        // when
        byte[] result = reportExportService.exportToMarkdown("project-1", "v1",
                ReportExportService.ReportType.MIGRATION_READINESS);

        // then
        assertNotNull(result);
        String content = new String(result, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("迁移就绪度报告"), "Markdown 应包含报告标题");
        assertTrue(content.contains("project-1"), "Markdown 应包含项目ID");
        assertTrue(content.contains("75.50"), "Markdown 应包含整体得分");
        assertTrue(content.contains("10"), "Markdown 应包含节点总数");
        assertTrue(content.contains("15"), "Markdown 应包含关系总数");
    }

    @Test
    void testExportConfidenceTrendMarkdown_ContainsTrendData() {
        // given
        ConfidenceTrendReport mockReport = new ConfidenceTrendReport();
        mockReport.setProjectId("project-1");
        mockReport.setVersionId("v1");
        mockReport.setTrendDirection("UP");
        mockReport.setStartingAverageConfidence(new java.math.BigDecimal("0.50"));
        mockReport.setEndingAverageConfidence(new java.math.BigDecimal("0.85"));
        mockReport.setTotalImprovement(new java.math.BigDecimal("0.35"));
        mockReport.setDailyData(java.util.Collections.emptyList());

        when(reportingService.generateConfidenceTrend("project-1", "v1")).thenReturn(mockReport);

        // when
        byte[] result = reportExportService.exportToMarkdown("project-1", "v1",
                ReportExportService.ReportType.CONFIDENCE_TREND);

        // then
        assertNotNull(result);
        String content = new String(result, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("置信度趋势报告"));
        assertTrue(content.contains("UP"));
        assertTrue(content.contains("50.00") || content.contains("50"), "应包含起始置信度");
    }

    @Test
    void testExportTestCoverageMarkdown_ContainsCoverageData() {
        // given
        TestCoverageReport mockReport = new TestCoverageReport();
        mockReport.setProjectId("project-1");
        mockReport.setVersionId("v1");
        mockReport.setTotalNodes(20L);
        mockReport.setCoveredNodes(12L);
        mockReport.setTotalEdges(30L);
        mockReport.setCoveredEdges(15L);
        mockReport.setCoveragePercentage(new java.math.BigDecimal("60.00"));
        mockReport.setEdgeCoveragePercentage(new java.math.BigDecimal("50.00"));
        mockReport.setHighConfidenceUncovered(java.util.Collections.emptyList());

        when(reportingService.generateTestCoverageReport("project-1", "v1")).thenReturn(mockReport);

        // when
        byte[] result = reportExportService.exportToMarkdown("project-1", "v1",
                ReportExportService.ReportType.TEST_COVERAGE);

        // then
        assertNotNull(result);
        String content = new String(result, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("测试覆盖率报告"));
        assertTrue(content.contains("60.00") || content.contains("60"), "应包含覆盖率");
    }

    @Test
    void testExportGraphQualityMarkdown_ContainsQualityData() {
        // given
        GraphQualityReport mockReport = new GraphQualityReport();
        mockReport.setProjectId("project-1");
        mockReport.setVersionId("v1");
        mockReport.setTotalNodes(50L);
        mockReport.setTotalEdges(80L);
        mockReport.setAverageConfidence(new java.math.BigDecimal("0.75"));
        mockReport.setAverageNodeDegree(new java.math.BigDecimal("3.20"));
        mockReport.setDensity(new java.math.BigDecimal("0.05"));
        mockReport.setDuplicateCandidateRatio(new java.math.BigDecimal("0.10"));
        mockReport.setDisconnectedComponents(2L);
        mockReport.setConfidenceDistribution(java.util.Collections.emptyList());
        mockReport.setQualityIssues(java.util.Collections.emptyList());

        when(reportingService.generateGraphQualityReport("project-1", "v1")).thenReturn(mockReport);

        // when
        byte[] result = reportExportService.exportToMarkdown("project-1", "v1",
                ReportExportService.ReportType.GRAPH_QUALITY);

        // then
        assertNotNull(result);
        String content = new String(result, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("图谱质量报告"));
        assertTrue(content.contains("50"), "应包含节点总数");
    }

    @Test
    void testExportExcel_ReturnsNonEmptyBytes() {
        MigrationReadinessReport mockReport = new MigrationReadinessReport();
        mockReport.setProjectId("project-1");
        mockReport.setTotalNodes(10L);
        mockReport.setConfirmedNodes(7L);
        mockReport.setPendingNodes(3L);
        mockReport.setTotalEdges(15L);
        mockReport.setConfirmedEdges(10L);
        mockReport.setPendingEdges(5L);
        mockReport.setOverallScore(new java.math.BigDecimal("80.0"));
        mockReport.setArchitectureUnderstandingScore(new java.math.BigDecimal("80.0"));
        mockReport.setBusinessKnowledgeScore(new java.math.BigDecimal("80.0"));
        mockReport.setTestCoverageScore(new java.math.BigDecimal("80.0"));
        mockReport.setConfidenceLevel(new java.math.BigDecimal("80.0"));
        mockReport.setNodeTypeStats(java.util.Collections.emptyList());
        mockReport.setRiskItems(java.util.Collections.emptyList());
        mockReport.setRecommendations(java.util.Collections.emptyList());

        when(reportingService.generateMigrationReport("project-1")).thenReturn(mockReport);

        byte[] result = reportExportService.exportToExcel("project-1", "v1",
                ReportExportService.ReportType.MIGRATION_READINESS);

        assertNotNull(result);
        assertTrue(result.length > 100, "Excel 应包含足够的字节内容");
    }

    @Test
    void testExportToExcel_ConfidenceTrend() {
        ConfidenceTrendReport mockReport = new ConfidenceTrendReport();
        mockReport.setProjectId("project-1");
        mockReport.setVersionId("v1");
        mockReport.setTrendDirection("FLAT");
        mockReport.setStartingAverageConfidence(java.math.BigDecimal.ZERO);
        mockReport.setEndingAverageConfidence(java.math.BigDecimal.ZERO);
        mockReport.setTotalImprovement(java.math.BigDecimal.ZERO);
        mockReport.setDailyData(java.util.Collections.emptyList());

        when(reportingService.generateConfidenceTrend("project-1", "v1")).thenReturn(mockReport);

        byte[] result = reportExportService.exportToExcel("project-1", "v1",
                ReportExportService.ReportType.CONFIDENCE_TREND);

        assertNotNull(result);
        assertTrue(result.length > 100);
    }

    @Test
    void testExportFormatEnum_AllValues() {
        assertEquals("迁移就绪度报告", ReportExportService.ReportType.MIGRATION_READINESS.getDisplayName());
        assertEquals("置信度趋势报告", ReportExportService.ReportType.CONFIDENCE_TREND.getDisplayName());
        assertEquals("测试覆盖率报告", ReportExportService.ReportType.TEST_COVERAGE.getDisplayName());
        assertEquals("图谱质量报告", ReportExportService.ReportType.GRAPH_QUALITY.getDisplayName());
    }

    @Test
    void testExportMarkdown_EmptyRiskItems() {
        MigrationReadinessReport mockReport = new MigrationReadinessReport();
        mockReport.setProjectId("project-1");
        mockReport.setTotalNodes(0L);
        mockReport.setConfirmedNodes(0L);
        mockReport.setPendingNodes(0L);
        mockReport.setTotalEdges(0L);
        mockReport.setConfirmedEdges(0L);
        mockReport.setPendingEdges(0L);
        mockReport.setOverallScore(java.math.BigDecimal.ZERO);
        mockReport.setArchitectureUnderstandingScore(java.math.BigDecimal.ZERO);
        mockReport.setBusinessKnowledgeScore(java.math.BigDecimal.ZERO);
        mockReport.setTestCoverageScore(java.math.BigDecimal.ZERO);
        mockReport.setConfidenceLevel(java.math.BigDecimal.ZERO);
        mockReport.setNodeTypeStats(java.util.Collections.emptyList());
        mockReport.setRiskItems(java.util.Collections.emptyList());
        mockReport.setRecommendations(java.util.Collections.emptyList());

        when(reportingService.generateMigrationReport("project-1")).thenReturn(mockReport);

        byte[] result = reportExportService.exportToMarkdown("project-1", "v1",
                ReportExportService.ReportType.MIGRATION_READINESS);

        String content = new String(result, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("当前无风险项"), "无风险项时应有友好提示");
        assertTrue(content.contains("无特殊建议"), "无建议时应有友好提示");
    }
}
