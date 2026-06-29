package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.Answers;
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
    private io.github.legacygraph.repository.NodeEvidenceRepository nodeEvidenceRepository;

    @Mock
    private MinioClient minioClient;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ReportExportService reportExportService;

    private ReportingService reportingService;

    private List<GraphNode> mockNodes;
    private List<GraphEdge> mockEdges;

    @BeforeEach
    void setUp() {
        reportingService = new ReportingService(
                reportRepository,
                nodeRepository,
                edgeRepository,
                testResultRepository,
                testCaseRepository,
                nodeEvidenceRepository,
                minioClient,
                objectMapper,
                reportExportService
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
        LambdaQueryChainWrapper<GraphNode> nodeChain = mock(LambdaQueryChainWrapper.class);
        when(nodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(mockNodes);

        LambdaQueryChainWrapper<GraphEdge> edgeChain = mock(LambdaQueryChainWrapper.class);
        when(edgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(mockEdges);

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
        LambdaQueryChainWrapper<GraphNode> nodeChain = mock(LambdaQueryChainWrapper.class);
        when(nodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(Collections.emptyList());

        LambdaQueryChainWrapper<GraphEdge> edgeChain = mock(LambdaQueryChainWrapper.class);
        when(edgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(Collections.emptyList());

        MigrationReadinessReport report = reportingService.generateMigrationReport("project-1");

        assertNotNull(report);
        assertEquals(0, report.getTotalNodes());
        assertEquals(0, report.getTotalEdges());
    }

    @Test
    void testGenerateTestCoverageReport_Success() {
        LambdaQueryChainWrapper<GraphNode> nodeChain = mock(LambdaQueryChainWrapper.class);
        when(nodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(mockNodes);

        LambdaQueryChainWrapper<GraphEdge> edgeChain = mock(LambdaQueryChainWrapper.class);
        when(edgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(mockEdges);

        LambdaQueryChainWrapper<TestResult> trChain = mock(LambdaQueryChainWrapper.class);
        when(testResultRepository.lambdaQuery()).thenReturn(trChain);
        when(trChain.eq(any(), any())).thenReturn(trChain);
        when(trChain.list()).thenReturn(Collections.emptyList());

        TestCoverageReport report = reportingService.generateTestCoverageReport("project-1", "v1");

        assertNotNull(report);
        assertEquals("project-1", report.getProjectId());
        assertTrue(report.getTotalNodes() >= 0);
        assertTrue(report.getCoveredNodes() >= 0);
        assertNotNull(report.getCoveragePercentage());
    }

    @Test
    void testGenerateGraphMetrics_ComputesAllRatios() {
        GraphNode confirmed = new GraphNode();
        confirmed.setId("n-confirmed");
        confirmed.setStatus("CONFIRMED");
        confirmed.setVerifiedScore(BigDecimal.valueOf(0.8)); // 运行时已验证

        GraphNode pending = new GraphNode();
        pending.setId("n-pending");
        pending.setStatus("PENDING_CONFIRM");

        when(nodeRepository.selectList(any())).thenReturn(Arrays.asList(confirmed, pending));
        when(edgeRepository.selectCount(any())).thenReturn(1L);

        // 仅 confirmed 节点有证据关联
        io.github.legacygraph.entity.NodeEvidence ne = new io.github.legacygraph.entity.NodeEvidence();
        ne.setNodeId("n-confirmed");
        when(nodeEvidenceRepository.selectList(any())).thenReturn(Collections.singletonList(ne));

        // 2 条测试结果，1 条通过
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
        // 覆盖率 1/2、待审核 1/2、运行时验证 1/2、证据完备 1/2、测试通过 1/2
        assertEquals(0, report.getCoverageRatio().compareTo(BigDecimal.valueOf(0.5)));
        assertEquals(0, report.getPendingReviewRatio().compareTo(BigDecimal.valueOf(0.5)));
        assertEquals(0, report.getRuntimeVerifiedRatio().compareTo(BigDecimal.valueOf(0.5)));
        assertEquals(0, report.getEvidenceCompletenessRatio().compareTo(BigDecimal.valueOf(0.5)));
        assertEquals(0, report.getTestPassRatio().compareTo(BigDecimal.valueOf(0.5)));
    }

    @Test
    void testGenerateGraphMetrics_EmptyGraph() {
        when(nodeRepository.selectList(any())).thenReturn(Collections.emptyList());
        when(edgeRepository.selectCount(any())).thenReturn(0L);
        when(testResultRepository.selectList(any())).thenReturn(Collections.emptyList());

        io.github.legacygraph.dto.report.GraphMetricsReport report =
                reportingService.generateGraphMetrics("project-1", "v1");

        assertNotNull(report);
        assertEquals(0, report.getTotalNodes());
        assertEquals(0, report.getCoverageRatio().compareTo(BigDecimal.ZERO));
        assertEquals(0, report.getTestPassRatio().compareTo(BigDecimal.ZERO));
    }
}
