package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.Report;
import io.github.legacygraph.repository.ReportRepository;
import io.github.legacygraph.repository.TestResultRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.report.MigrationReportService;

/**
 * 迁移就绪度报告服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class MigrationReportServiceTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private TestResultRepository testResultRepository;

    @InjectMocks
    private MigrationReportService migrationReportService;

    @Test
    void testGenerateMigrationReport_Success() {
        String projectId = "test-project";

        // Mock 图谱统计数据
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalNodes", 100L);
        stats.put("totalEdges", 200L);
        stats.put("confirmedNodes", 80L);
        stats.put("confirmedEdges", 150L);
        stats.put("pendingNodes", 20L);
        stats.put("pendingEdges", 50L);
        stats.put("avgConfidence", 0.85);
        stats.put("withEvidenceCount", 75L);

        when(neo4jGraphDao.graphStats(projectId)).thenReturn(stats);
        when(neo4jGraphDao.nodeTypeStats(projectId)).thenReturn(List.of());
        when(reportRepository.insert(any(Report.class))).thenReturn(1);

        Report report = migrationReportService.generateMigrationReport(projectId);

        assertNotNull(report);
        assertEquals(projectId, report.getProjectId());
        assertEquals("MIGRATION_READINESS", report.getReportType());
        assertEquals("COMPLETED", report.getStatus());
        assertNotNull(report.getGeneratedAt());
        assertNotNull(report.getId());

        // 验证就绪度评分：80/100 * 100 = 80.0
        assertTrue(report.getReportName().contains("80.0%"));
        assertTrue(report.getReportName().contains("80/100"));

        verify(reportRepository).insert(any(Report.class));
    }

    @Test
    void testGenerateMigrationReport_EmptyProject() {
        String projectId = "empty-project";

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalNodes", 0L);
        stats.put("confirmedNodes", 0L);
        stats.put("avgConfidence", 0.0);

        when(neo4jGraphDao.graphStats(projectId)).thenReturn(stats);
        when(neo4jGraphDao.nodeTypeStats(projectId)).thenReturn(List.of());
        when(reportRepository.insert(any(Report.class))).thenReturn(1);

        Report report = migrationReportService.generateMigrationReport(projectId);

        assertNotNull(report);
        assertEquals("MIGRATION_READINESS", report.getReportType());
        // 无节点时 readinessScore 为 0.0
        assertTrue(report.getReportName().contains("0.0%"));
        verify(reportRepository).insert(any(Report.class));
    }

    @Test
    void testGenerateMigrationReport_WithNodeTypeStats() {
        String projectId = "typed-project";

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalNodes", 50L);
        stats.put("confirmedNodes", 45L);
        stats.put("avgConfidence", 0.92);

        List<Map<String, Object>> nodeTypeStats = new ArrayList<>();
        Map<String, Object> typeStat = new HashMap<>();
        typeStat.put("type", "Service");
        typeStat.put("count", 30L);
        nodeTypeStats.add(typeStat);

        when(neo4jGraphDao.graphStats(projectId)).thenReturn(stats);
        when(neo4jGraphDao.nodeTypeStats(projectId)).thenReturn(nodeTypeStats);
        when(reportRepository.insert(any(Report.class))).thenReturn(1);

        Report report = migrationReportService.generateMigrationReport(projectId);

        assertNotNull(report);
        // 45/50 * 100 = 90.0
        assertTrue(report.getReportName().contains("90.0%"));
        assertTrue(report.getReportName().contains("45/50"));
        verify(reportRepository).insert(any(Report.class));
    }

    @Test
    void testGenerateMigrationReport_AllConfirmed() {
        String projectId = "fully-confirmed";

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalNodes", 30L);
        stats.put("confirmedNodes", 30L);
        stats.put("avgConfidence", 1.0);

        when(neo4jGraphDao.graphStats(projectId)).thenReturn(stats);
        when(neo4jGraphDao.nodeTypeStats(projectId)).thenReturn(List.of());
        when(reportRepository.insert(any(Report.class))).thenReturn(1);

        Report report = migrationReportService.generateMigrationReport(projectId);

        assertNotNull(report);
        // 100% 就绪
        assertTrue(report.getReportName().contains("100.0%"));
        verify(reportRepository).insert(any(Report.class));
    }
}
