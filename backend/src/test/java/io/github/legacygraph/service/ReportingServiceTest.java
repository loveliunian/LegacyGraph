package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.ReportInsightAgent;
import io.github.legacygraph.dto.ReportInsight;
import io.github.legacygraph.dto.report.GraphQualityReport;
import io.github.legacygraph.dto.report.MigrationReadinessReport;
import io.github.legacygraph.dto.report.TestCoverageReport;
import io.github.legacygraph.entity.Report;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.repository.ReportRepository;
import io.github.legacygraph.repository.TestResultRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.graph.GapFinderService;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.service.report.ReportExportService;
import io.github.legacygraph.service.report.ReportingService;

@ExtendWith(MockitoExtension.class)
class ReportingServiceTest {

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private io.github.legacygraph.repository.TestCaseRepository testCaseRepository;

    @Mock
    private io.github.legacygraph.repository.NodeEvidenceRepository nodeEvidenceRepository;

    @Mock
    private MinioClient minioClient;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ReportExportService reportExportService;

    @Mock
    private ReportInsightAgent reportInsightAgent;

    @Mock
    private KnowledgeClaimService knowledgeClaimService;

    @Mock
    private GapFinderService gapFinderService;

    private ReportingService reportingService;

    private List<GraphNode> mockNodes;
    private List<GraphEdge> mockEdges;

    @BeforeEach
    void setUp() {
        reportingService = new ReportingService(
                reportRepository,
                neo4jGraphDao,
                testResultRepository,
                testCaseRepository,
                nodeEvidenceRepository,
                minioClient,
                objectMapper,
                reportExportService,
                reportInsightAgent,
                knowledgeClaimService,
                gapFinderService
        );

        GraphNode node1 = new GraphNode();
        node1.setId("node-1");
        node1.setProjectId("project-1");
        node1.setNodeType("ApiEndpoint");
        node1.setNodeName("GET /api/test");
        node1.setStatus("CONFIRMED");
        node1.setConfidence(BigDecimal.valueOf(0.95));

        GraphNode node2 = new GraphNode();
        node2.setId("node-2");
        node2.setProjectId("project-1");
        node2.setNodeType("Service");
        node2.setNodeName("TestService");
        node2.setStatus("PENDING");
        node2.setConfidence(BigDecimal.valueOf(0.45));

        GraphNode node3 = new GraphNode();
        node3.setId("node-3");
        node3.setProjectId("project-1");
        node3.setNodeType("Table");
        node3.setNodeName("t_user");
        node3.setStatus("CONFIRMED");
        node3.setConfidence(BigDecimal.valueOf(0.85));

        mockNodes = Arrays.asList(node1, node2, node3);

        GraphEdge edge1 = new GraphEdge();
        edge1.setId("edge-1");
        edge1.setProjectId("project-1");
        edge1.setStatus("CONFIRMED");
        edge1.setConfidence(BigDecimal.valueOf(0.9));

        GraphEdge edge2 = new GraphEdge();
        edge2.setId("edge-2");
        edge2.setProjectId("project-1");
        edge2.setStatus("PENDING");
        edge2.setConfidence(BigDecimal.valueOf(0.5));

        mockEdges = Arrays.asList(edge1, edge2);
    }

    @Test
    void testGenerateMigrationReport_Success() {
        // 重构后使用聚合 API：graphStats + nodeTypeStats + 风险节点查询
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalNodes", 3L);
        stats.put("confirmedNodes", 2L);
        stats.put("pendingNodes", 1L);
        stats.put("totalEdges", 2L);
        stats.put("confirmedEdges", 1L);
        stats.put("pendingEdges", 1L);
        stats.put("avgConfidence", 0.8);
        when(neo4jGraphDao.graphStats("project-1")).thenReturn(stats);

        Map<String, Object> typeRow = new HashMap<>();
        typeRow.put("nodeType", "ApiEndpoint");
        typeRow.put("total", 2L);
        typeRow.put("confirmed", 2L);
        typeRow.put("avgConfidence", 0.9);
        when(neo4jGraphDao.nodeTypeStats("project-1")).thenReturn(List.of(typeRow));

        when(neo4jGraphDao.queryLowConfidenceNodes(eq("project-1"), anyInt()))
                .thenReturn(Collections.emptyList());
        when(neo4jGraphDao.queryDisconnectedNodes(eq("project-1"), anyInt()))
                .thenReturn(Collections.emptyList());

        MigrationReadinessReport report = reportingService.generateMigrationReport("project-1");

        assertNotNull(report);
        assertEquals("project-1", report.getProjectId());
        assertEquals(3, report.getTotalNodes());
        assertEquals(2, report.getConfirmedNodes());
        assertEquals(1, report.getPendingNodes());
        assertEquals(2, report.getTotalEdges());
        assertEquals(1, report.getConfirmedEdges());
        assertEquals(1, report.getPendingEdges());
        assertNotNull(report.getNodeTypeStats());
        assertFalse(report.getNodeTypeStats().isEmpty());
        assertNotNull(report.getConfidenceLevel());
    }

    @Test
    void testGenerateMigrationReport_EmptyNodes() {
        when(neo4jGraphDao.graphStats("project-1")).thenReturn(Collections.emptyMap());
        when(neo4jGraphDao.nodeTypeStats("project-1")).thenReturn(Collections.emptyList());
        when(neo4jGraphDao.queryLowConfidenceNodes(eq("project-1"), anyInt()))
                .thenReturn(Collections.emptyList());

        MigrationReadinessReport report = reportingService.generateMigrationReport("project-1");

        assertNotNull(report);
        assertEquals(0, report.getTotalNodes());
        assertEquals(0, report.getTotalEdges());
    }

    @Test
    void testGenerateTestCoverageReport_Success() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalNodes", 3L);
        stats.put("totalEdges", 2L);
        stats.put("avgConfidence", 0.8);
        when(neo4jGraphDao.versionGraphStats("project-1", "v1")).thenReturn(stats);

        when(testResultRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).eq(any(), any()).list()).thenReturn(Collections.emptyList());

        when(neo4jGraphDao.countEdgesConnectedToNodes(eq("project-1"), eq("v1"), anyList())).thenReturn(0L);
        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("v1"), isNull(), isNull(), isNull(), isNull(), eq(300)))
                .thenReturn(mockNodes);

        TestCoverageReport report = reportingService.generateTestCoverageReport("project-1", "v1");

        assertNotNull(report);
        assertEquals("project-1", report.getProjectId());
        assertTrue(report.getTotalNodes() >= 0);
        assertTrue(report.getCoveredNodes() >= 0);
        assertNotNull(report.getCoveragePercentage());
    }

    @Test
    void testGenerateGraphMetrics_ComputesAllRatios() {
        // 重构后使用 versionGraphStats 聚合：total=2, confirmed=1, pending=1, runtimeVerified=1, withEvidence=1
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalNodes", 2L);
        stats.put("totalEdges", 1L);
        stats.put("confirmedNodes", 1L);
        stats.put("pendingNodes", 1L);
        stats.put("runtimeVerifiedCount", 1L);
        stats.put("withEvidenceCount", 1L);
        stats.put("avgConfidence", 0.6);
        when(neo4jGraphDao.versionGraphStats("project-1", "v1")).thenReturn(stats);

        TestResult pass = new TestResult();
        pass.setResultStatus("PASSED");
        TestResult fail = new TestResult();
        fail.setResultStatus("FAILED");
        when(testResultRepository.selectList(any())).thenReturn(Arrays.asList(pass, fail));

        io.github.legacygraph.dto.report.GraphMetricsReport report =
                reportingService.generateGraphMetrics("project-1", "v1");

        assertNotNull(report);
        assertEquals(2, report.getTotalNodes());
        assertEquals(1, report.getTotalEdges());
        assertEquals(0, report.getCoverageRatio().compareTo(BigDecimal.valueOf(0.5)));
        assertEquals(0, report.getPendingReviewRatio().compareTo(BigDecimal.valueOf(0.5)));
        assertEquals(0, report.getRuntimeVerifiedRatio().compareTo(BigDecimal.valueOf(0.5)));
        assertEquals(0, report.getEvidenceCompletenessRatio().compareTo(BigDecimal.valueOf(0.5)));
        assertEquals(0, report.getTestPassRatio().compareTo(BigDecimal.valueOf(0.5)));
    }

    @Test
    void testGenerateGraphMetrics_EmptyGraph() {
        when(neo4jGraphDao.versionGraphStats("project-1", "v1")).thenReturn(Collections.emptyMap());
        when(testResultRepository.selectList(any())).thenReturn(Collections.emptyList());

        io.github.legacygraph.dto.report.GraphMetricsReport report =
                reportingService.generateGraphMetrics("project-1", "v1");

        assertNotNull(report);
        assertEquals(0, report.getTotalNodes());
        assertEquals(0, report.getCoverageRatio().compareTo(BigDecimal.ZERO));
        assertEquals(0, report.getTestPassRatio().compareTo(BigDecimal.ZERO));
    }

    @Test
    void testGenerateGraphQualityReport_IncludesClaimAndGapMetrics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalNodes", 3L);
        stats.put("totalEdges", 2L);
        stats.put("avgConfidence", 0.7);
        when(neo4jGraphDao.versionGraphStats("project-1", "v1")).thenReturn(stats);
        when(neo4jGraphDao.averageNodeDegree("project-1", "v1")).thenReturn(1.5);
        when(neo4jGraphDao.confidenceDistribution("project-1", "v1")).thenReturn(Collections.emptyList());
        when(neo4jGraphDao.queryLowConfidenceNodes(eq("project-1"), eq("v1"), eq(0.3), anyInt()))
                .thenReturn(Collections.emptyList());
        when(neo4jGraphDao.queryDisconnectedNodes(eq("project-1"), eq("v1"), anyInt()))
                .thenReturn(Collections.emptyList());
        when(knowledgeClaimService.countClaimsByStatus("project-1", "v1"))
                .thenReturn(Map.of("CONFIRMED", 2L, "PENDING_CONFIRM", 3L));
        when(knowledgeClaimService.countAiOnlyClaims("project-1", "v1")).thenReturn(4L);
        when(gapFinderService.countGapsByStatus("project-1", "v1"))
                .thenReturn(Map.of("OPEN", 1L, "REOPENED", 1L));
        when(gapFinderService.countHighSeverityGaps("project-1", "v1")).thenReturn(1L);
        when(gapFinderService.countGapsByType("project-1", "v1"))
                .thenReturn(Map.of("doc_only_feature", 2L));

        GraphQualityReport report = reportingService.generateGraphQualityReport("project-1", "v1");

        assertEquals(5L, report.getClaimCount());
        assertEquals(2L, report.getConfirmedClaimCount());
        assertEquals(3L, report.getPendingClaimCount());
        assertEquals(4L, report.getAiOnlyClaimCount());
        assertEquals(2L, report.getGapCount());
        assertEquals(2L, report.getOpenGapCount());
        assertEquals(1L, report.getHighSeverityGapCount());
        assertEquals(Map.of("doc_only_feature", 2L), report.getGapCountByType());
    }

    @Test
    void testGenerateReportInsights_UsesGraphMetricsAndGapSummary() throws Exception {
        // 重构后：versionGraphStats 聚合 + queryNodes(limit=100) 找高置信未覆盖 + queryLowConfidenceNodes + queryDisconnectedNodes
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalNodes", 2L);
        stats.put("totalEdges", 0L);
        stats.put("pendingNodes", 1L);
        stats.put("avgConfidence", 0.6);
        when(neo4jGraphDao.versionGraphStats("project-1", "v1")).thenReturn(stats);

        GraphNode highConfidenceApi = new GraphNode();
        highConfidenceApi.setId("api-1");
        highConfidenceApi.setNodeType("ApiEndpoint");
        highConfidenceApi.setNodeName("下单接口");
        highConfidenceApi.setStatus("CONFIRMED");
        highConfidenceApi.setConfidence(BigDecimal.valueOf(0.95));

        GraphNode lowConfidenceNode = new GraphNode();
        lowConfidenceNode.setId("svc-1");
        lowConfidenceNode.setNodeType("Service");
        lowConfidenceNode.setNodeName("库存服务");
        lowConfidenceNode.setStatus("PENDING_CONFIRM");
        lowConfidenceNode.setConfidence(BigDecimal.valueOf(0.3));

        // generateReportInsights 用 limit=100 查询节点（注意不是 0）
        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("v1"), isNull(), isNull(), isNull(), isNull(), eq(100)))
                .thenReturn(List.of(highConfidenceApi, lowConfidenceNode));
        when(neo4jGraphDao.queryLowConfidenceNodes(eq("project-1"), eq("v1"), eq(0.5), eq(50)))
                .thenReturn(List.of(lowConfidenceNode));
        when(neo4jGraphDao.queryDisconnectedNodes(eq("project-1"), eq("v1"), eq(50)))
                .thenReturn(Collections.emptyList());

        // findCoveredNodeIds + computeTestPassRatio 都走 testResultRepository.selectList
        when(testResultRepository.selectList(any())).thenReturn(Collections.emptyList());
        when(objectMapper.writeValueAsString(any())).thenAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            return String.valueOf(value);
        });

        ReportInsight insight = new ReportInsight();
        insight.setSummary("优先补高置信 API 测试");
        ReportInsight.ActionItem action = new ReportInsight.ActionItem();
        action.setTitle("补充下单接口测试");
        action.setPriority("HIGH");
        insight.setActions(List.of(action));
        when(reportInsightAgent.generateInsights(eq("project-1"), anyString(), anyString())).thenReturn(insight);

        ReportInsight result = reportingService.generateReportInsights("project-1", "v1");

        assertEquals("优先补高置信 API 测试", result.getSummary());
        verify(reportInsightAgent).generateInsights(eq("project-1"),
                argThat(metrics -> metrics.contains("totalNodes=2")),
                argThat(gaps -> gaps.contains("highConfidenceUncovered") && gaps.contains("isolatedNodes")));
    }

    @Test
    void exportReport_supportsPersistedSystemOverviewReport() throws Exception {
        Report report = new Report();
        report.setId("report-1");
        report.setProjectId("project-1");
        report.setVersionId("version-1");
        report.setReportType("SYSTEM_OVERVIEW");
        when(reportRepository.selectById("report-1")).thenReturn(report);
        when(reportExportService.exportReport(
                "project-1",
                "version-1",
                ReportExportService.ReportType.SYSTEM_OVERVIEW,
                ReportExportService.ExportFormat.MD))
                .thenReturn("# 系统关系总览".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        byte[] result = reportingService.exportReport("report-1", "MD");

        assertEquals("# 系统关系总览", new String(result, java.nio.charset.StandardCharsets.UTF_8));
        verify(reportExportService).exportReport(
                "project-1",
                "version-1",
                ReportExportService.ReportType.SYSTEM_OVERVIEW,
                ReportExportService.ExportFormat.MD);
    }

    @Test
    void exportReport_readsPersistedMarkdownFileBeforeDynamicExport() throws Exception {
        Path markdown = Files.createTempFile("system-overview-", ".md");
        Files.writeString(markdown, "# 扫描完成关系总结", StandardCharsets.UTF_8);
        try {
            Report report = new Report();
            report.setId("report-file-1");
            report.setProjectId("project-1");
            report.setVersionId("version-1");
            report.setReportType("SYSTEM_OVERVIEW");
            report.setFilePath(markdown.toString());
            when(reportRepository.selectById("report-file-1")).thenReturn(report);

            byte[] result = reportingService.exportReport("report-file-1", null);

            assertEquals("# 扫描完成关系总结", new String(result, StandardCharsets.UTF_8));
            verify(reportExportService, never()).exportReport(anyString(), anyString(), any(), any());
        } finally {
            Files.deleteIfExists(markdown);
        }
    }
}
