package io.github.legacygraph.service.report;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.Report;
import io.github.legacygraph.repository.ReportRepository;
import io.github.legacygraph.repository.TestResultRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 迁移就绪度报告服务 — 从 ReportingService 拆分。
 * 负责迁移就绪度分析和报告生成。
 *
 * <p>⚠️ TODO B-H11：持续从 ReportingService 提取其他报告类型：</p>
 * <ul>
 *   <li>{@code QualityReportService} — 图谱质量报告</li>
 *   <li>{@code InsightReportService} — LLM 洞察报告</li>
 * </ul>
 */
@Slf4j
@Service
public class MigrationReportService {

    private final Neo4jGraphDao neo4jGraphDao;
    private final ReportRepository reportRepository;
    private final TestResultRepository testResultRepository;

    public MigrationReportService(Neo4jGraphDao neo4jGraphDao,
                                  ReportRepository reportRepository,
                                  TestResultRepository testResultRepository) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.reportRepository = reportRepository;
        this.testResultRepository = testResultRepository;
    }

    /**
     * 生成迁移就绪度报告。
     */
    public Report generateMigrationReport(String projectId) {
        Report report = new Report();
        report.setId(UUID.randomUUID().toString());
        report.setProjectId(projectId);
        report.setReportType("MIGRATION_READINESS");
        report.setGeneratedAt(LocalDateTime.now());

        Map<String, Object> stats = neo4jGraphDao.graphStats(projectId);
        List<Map<String, Object>> nodeTypeStats = neo4jGraphDao.nodeTypeStats(projectId);

        long totalNodes = ((Number) stats.getOrDefault("totalNodes", 0L)).longValue();
        long confirmedNodes = ((Number) stats.getOrDefault("confirmedNodes", 0L)).longValue();
        double avgConfidence = ((Number) stats.getOrDefault("avgConfidence", 0.0)).doubleValue();

        double readinessScore = totalNodes > 0
                ? (double) confirmedNodes / totalNodes * 100.0
                : 0.0;
        readinessScore = BigDecimal.valueOf(readinessScore)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("readinessScore", readinessScore);
        data.put("totalNodes", totalNodes);
        data.put("confirmedNodes", confirmedNodes);
        data.put("avgConfidence", avgConfidence);
        data.put("nodeTypeStats", nodeTypeStats);
        report.setReportName("迁移就绪度: " + readinessScore + "% (" + confirmedNodes + "/" + totalNodes + " 节点已确认)");
        report.setStatus("COMPLETED");
        report.setGeneratedAt(LocalDateTime.now());

        reportRepository.insert(report);
        log.info("Migration readiness report generated: projectId={}, score={}%", projectId, readinessScore);
        return report;
    }
}
