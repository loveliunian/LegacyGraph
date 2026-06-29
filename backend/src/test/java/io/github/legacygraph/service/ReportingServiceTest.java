package io.github.legacygraph.service;

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
    private MinioClient minioClient;

    @Mock
    private ObjectMapper objectMapper;

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
                minioClient,
                objectMapper
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
        when(nodeRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(nodeRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(nodeRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(mockNodes);

        when(edgeRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(edgeRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(edgeRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(mockEdges);

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
        when(nodeRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(nodeRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(nodeRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(Collections.emptyList());

        when(edgeRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(edgeRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(edgeRepository.lambdaQuery().eq(any(), any()).list()).thenReturn(Collections.emptyList());

        MigrationReadinessReport report = reportingService.generateMigrationReport("project-1");

        assertNotNull(report);
        assertEquals(0, report.getTotalNodes());
        assertEquals(0, report.getTotalEdges());
    }

    @Test
    void testGenerateTestCoverageReport_Success() {
        when(nodeRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(nodeRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(nodeRepository.lambdaQuery().eq(any(), any()).eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(nodeRepository.lambdaQuery().eq(any(), any()).eq(any(), any()).count()).thenReturn(5L);

        when(testCaseRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(testCaseRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(testCaseRepository.lambdaQuery().eq(any(), any()).count()).thenReturn(3L);

        when(testResultRepository.lambdaQuery()).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).eq(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).eq(any(), any()).count()).thenReturn(2L);
        when(testResultRepository.lambdaQuery().eq(any(), any()).eq(any(), any()).in(any(), any())).thenReturn(mock(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryChainWrapper.class));
        when(testResultRepository.lambdaQuery().eq(any(), any()).eq(any(), any()).in(any(), any()).count()).thenReturn(1L);

        TestCoverageReport report = reportingService.generateTestCoverageReport("project-1");

        assertNotNull(report);
        assertEquals("project-1", report.getProjectId());
        assertEquals(5, report.getTotalTestableNodes());
        assertEquals(3, report.getGeneratedTests());
        assertEquals(2, report.getExecutedTests());
        assertEquals(1, report.getPassedTests());
    }
}
