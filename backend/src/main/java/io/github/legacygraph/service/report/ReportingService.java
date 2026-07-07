package io.github.legacygraph.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.ReportInsightAgent;
import io.github.legacygraph.dto.ReportInsight;
import io.github.legacygraph.dto.report.ConfidenceTrendReport;
import io.github.legacygraph.dto.report.GraphMetricsReport;
import io.github.legacygraph.dto.report.GraphQualityReport;
import io.github.legacygraph.dto.report.MigrationReadinessReport;
import io.github.legacygraph.dto.report.TestCoverageReport;
import io.github.legacygraph.service.graph.GapFinderService;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.Report;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.repository.ReportRepository;
import io.github.legacygraph.repository.TestResultRepository;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 报告生成服务
 * 生成各种分析报告：迁移就绪度、置信度趋势、测试覆盖率、图谱质量
 *
 * <p>⚠️ TODO B-H11：本类承担 5 种报告 + LLM 洞察 + MinIO 上传 + 缓存，依赖 9 个 Bean。
 * 建议按报告类型拆分为 MigrationReportService / QualityReportService / InsightReportService。</p>
 */
@Slf4j
@Service
public class ReportingService {

    private final ReportRepository reportRepository;
    private final Neo4jGraphDao neo4jGraphDao;
    private final TestResultRepository testResultRepository;
    private final io.github.legacygraph.repository.TestCaseRepository testCaseRepository;
    private final io.github.legacygraph.repository.NodeEvidenceRepository nodeEvidenceRepository;
    private final MinioClient minioClient;
    private final ObjectMapper objectMapper;
    private final ReportExportService reportExportService;
    private final ReportInsightAgent reportInsightAgent;
    private final KnowledgeClaimService knowledgeClaimService;
    private final GapFinderService gapFinderService;

    // B-M7：原为 "legacygraph-reports"，与 application.yml 的 minio.bucket-name 不一致，统一为 legacy-graph
    private final String bucketName = "legacy-graph";
    @Autowired
    public ReportingService(ReportRepository reportRepository,
                           Neo4jGraphDao neo4jGraphDao,
                           TestResultRepository testResultRepository,
                           io.github.legacygraph.repository.TestCaseRepository testCaseRepository,
                           io.github.legacygraph.repository.NodeEvidenceRepository nodeEvidenceRepository,
                           @Autowired(required = false) MinioClient minioClient,
                           ObjectMapper objectMapper,
                           ReportExportService reportExportService,
                           ReportInsightAgent reportInsightAgent,
                           KnowledgeClaimService knowledgeClaimService,
                           GapFinderService gapFinderService) {
        this.reportRepository = reportRepository;
        this.neo4jGraphDao = neo4jGraphDao;
        this.testResultRepository = testResultRepository;
        this.testCaseRepository = testCaseRepository;
        this.nodeEvidenceRepository = nodeEvidenceRepository;
        this.minioClient = minioClient;
        this.objectMapper = objectMapper;
        this.reportExportService = reportExportService;
        this.reportInsightAgent = reportInsightAgent;
        this.knowledgeClaimService = knowledgeClaimService;
        this.gapFinderService = gapFinderService;
    }

    /**
     * 生成迁移就绪度报告
     */
    private static final int RISK_ITEMS_LIMIT = 200;

    @Transactional
    @Cacheable(cacheNames = "report-migration-readiness", key = "#projectId")
    public MigrationReadinessReport generateMigrationReport(String projectId) {
        log.info("Generating migration readiness report for project: {}", projectId);

        // ① 聚合统计：单次 Cypher 替代全量节点+边加载，避免超时
        Map<String, Object> stats = neo4jGraphDao.graphStats(projectId);
        long totalNodes = toLong(stats.get("totalNodes"));
        long confirmedNodes = toLong(stats.get("confirmedNodes"));
        long pendingNodes = toLong(stats.get("pendingNodes"));
        long totalEdges = toLong(stats.get("totalEdges"));
        long confirmedEdges = toLong(stats.get("confirmedEdges"));
        long pendingEdges = toLong(stats.get("pendingEdges"));
        double avgConfidence = toDouble(stats.get("avgConfidence"));

        MigrationReadinessReport report = new MigrationReadinessReport();
        report.setProjectId(projectId);
        report.setGeneratedAt(LocalDateTime.now());
        report.setTotalNodes(totalNodes);
        report.setConfirmedNodes(confirmedNodes);
        report.setPendingNodes(pendingNodes);
        report.setTotalEdges(totalEdges);
        report.setConfirmedEdges(confirmedEdges);
        report.setPendingEdges(pendingEdges);

        // ② 按节点类型统计：Cypher 聚合查询
        List<Map<String, Object>> typeRows = neo4jGraphDao.nodeTypeStats(projectId);
        List<MigrationReadinessReport.NodeTypeStat> typeStats = new ArrayList<>();
        for (Map<String, Object> row : typeRows) {
            MigrationReadinessReport.NodeTypeStat stat = new MigrationReadinessReport.NodeTypeStat();
            String nodeType = Objects.toString(row.get("nodeType"), "");
            stat.setNodeType(nodeType);
            stat.setDisplayName(getNodeTypeDisplayName(nodeType));
            stat.setTotal(toLong(row.get("total")));
            stat.setConfirmed(toLong(row.get("confirmed")));
            stat.setAverageConfidence(BigDecimal.valueOf(toDouble(row.get("avgConfidence"))));
            typeStats.add(stat);
        }
        report.setNodeTypeStats(typeStats);

        // ③ 风险项：限制返回数量，避免全量扫描
        List<MigrationReadinessReport.RiskItem> risks = new ArrayList<>();
        for (GraphNode node : neo4jGraphDao.queryLowConfidenceNodes(projectId, RISK_ITEMS_LIMIT)) {
            MigrationReadinessReport.RiskItem risk = new MigrationReadinessReport.RiskItem();
            risk.setRiskType("LOW_CONFIDENCE");
            risk.setDescription("节点置信度低于 0.5，需要人工审核");
            risk.setAffectedNodeId(node.getId());
            risk.setAffectedNodeName(node.getNodeName());
            risk.setRiskLevel(node.getConfidence() != null
                    ? BigDecimal.ONE.subtract(node.getConfidence()) : BigDecimal.ONE);
            risks.add(risk);
        }
        if (totalNodes > 1) {
            for (GraphNode node : neo4jGraphDao.queryDisconnectedNodes(projectId, RISK_ITEMS_LIMIT)) {
                MigrationReadinessReport.RiskItem risk = new MigrationReadinessReport.RiskItem();
                risk.setRiskType("DISCONNECTED");
                risk.setDescription("节点孤立，没有连接关系");
                risk.setAffectedNodeId(node.getId());
                risk.setAffectedNodeName(node.getNodeName());
                risk.setRiskLevel(BigDecimal.ONE);
                risks.add(risk);
            }
        }
        report.setRiskItems(risks);

        // ④ 计算得分（avgConfidence 来自 graphStats 聚合）
        BigDecimal overallConfidence = BigDecimal.valueOf(avgConfidence);
        report.setConfidenceLevel(overallConfidence.multiply(BigDecimal.valueOf(100)));

        BigDecimal architectureScore = BigDecimal.valueOf(confirmedNodes)
                .divide(BigDecimal.valueOf(totalNodes == 0 ? 1 : totalNodes), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        BigDecimal businessScore = overallConfidence.multiply(BigDecimal.valueOf(100));
        report.setArchitectureUnderstandingScore(architectureScore);
        report.setBusinessKnowledgeScore(businessScore);
        report.setTestCoverageScore(BigDecimal.ZERO);

        // 整体得分加权
        BigDecimal overallScore = architectureScore.multiply(BigDecimal.valueOf(0.4))
                .add(businessScore.multiply(BigDecimal.valueOf(0.4)))
                .add(report.getConfidenceLevel().multiply(BigDecimal.valueOf(0.2)));
        report.setOverallScore(overallScore);

        // ⑤ 建议
        List<String> recommendations = generateRecommendations(report);
        report.setRecommendations(recommendations);

        // 保存报告记录
        saveReport(projectId, "MIGRATION_READINESS", "迁移就绪度报告");

        log.info("Migration readiness report generated for project {}: overall score {}, nodes={}, edges={}",
                projectId, overallScore, totalNodes, totalEdges);

        return report;
    }

    private long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }

    private double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private LocalDate toLocalDate(Object val) {
        if (val instanceof LocalDate d) return d;
        if (val instanceof String s) {
            try {
                return LocalDate.parse(s);
            } catch (Exception e) {
                // B-M11：原实现静默吞掉解析异常并回退 LocalDate.now()，会产出错误日期且无迹可查。
                // 改为记录告警，仍回退到当天以保证图表不空，但异常可见。
                log.warn("无法解析日期字符串，回退为当天: raw={}", s);
            }
        }
        return LocalDate.now();
    }

    /**
     * 生成置信度趋势报告
     */
    @Cacheable(cacheNames = "report-confidence-trend", key = "#projectId + ':' + #versionId")
    public ConfidenceTrendReport generateConfidenceTrend(String projectId, String versionId) {
        log.info("Generating confidence trend report for project {}, version {}", projectId, versionId);

        ConfidenceTrendReport report = new ConfidenceTrendReport();
        report.setProjectId(projectId);
        report.setVersionId(versionId);

        // 使用 Cypher 聚合查询替代全量节点加载
        List<Map<String, Object>> dailyRows = neo4jGraphDao.confidenceTrendDaily(projectId, versionId);

        if (dailyRows.isEmpty()) {
            report.setDailyData(Collections.emptyList());
            report.setStartingAverageConfidence(BigDecimal.ZERO);
            report.setEndingAverageConfidence(BigDecimal.ZERO);
            report.setTotalImprovement(BigDecimal.ZERO);
            report.setTrendDirection("FLAT");
            return report;
        }

        List<ConfidenceTrendReport.DailyData> dailyDataList = new ArrayList<>();
        BigDecimal startConfidence = null;
        BigDecimal endConfidence = null;

        for (Map<String, Object> row : dailyRows) {
            ConfidenceTrendReport.DailyData dailyData = new ConfidenceTrendReport.DailyData();
            dailyData.setDate(toLocalDate(row.get("date")));
            dailyData.setAverageConfidence(BigDecimal.valueOf(toDouble(row.get("avgConfidence"))));
            dailyData.setConfirmedNodes(toLong(row.get("confirmedNodes")));
            dailyData.setNewNodes(toLong(row.get("newNodes")));
            dailyDataList.add(dailyData);

            if (startConfidence == null) {
                startConfidence = dailyData.getAverageConfidence();
            }
            endConfidence = dailyData.getAverageConfidence();
        }

        report.setDailyData(dailyDataList);
        report.setStartingAverageConfidence(startConfidence != null ? startConfidence : BigDecimal.ZERO);
        report.setEndingAverageConfidence(endConfidence != null ? endConfidence : BigDecimal.ZERO);
        BigDecimal improvement = report.getEndingAverageConfidence().subtract(report.getStartingAverageConfidence());
        report.setTotalImprovement(improvement);

        // 判断趋势
        if (improvement.compareTo(BigDecimal.valueOf(0.05)) > 0) {
            report.setTrendDirection("UP");
        } else if (improvement.compareTo(BigDecimal.valueOf(-0.05)) < 0) {
            report.setTrendDirection("DOWN");
        } else {
            report.setTrendDirection("FLAT");
        }

        // 保存报告记录
        saveReport(projectId, "CONFIDENCE_TREND", "置信度趋势报告");

        log.info("Confidence trend report generated: trend direction = {}", report.getTrendDirection());
        return report;
    }

    /**
     * 生成测试覆盖率报告
     */
    @Cacheable(cacheNames = "report-test-coverage", key = "#projectId + ':' + #versionId")
    public TestCoverageReport generateTestCoverageReport(String projectId, String versionId) {
        log.info("Generating test coverage report for project {}, version {}", projectId, versionId);

        TestCoverageReport report = new TestCoverageReport();
        report.setProjectId(projectId);
        report.setVersionId(versionId);

        // 总量统计：聚合查询替代全量加载
        Map<String, Object> stats = neo4jGraphDao.versionGraphStats(projectId, versionId);
        long totalNodes = toLong(stats.get("totalNodes"));
        long totalEdges = toLong(stats.get("totalEdges"));
        report.setTotalNodes(totalNodes);
        report.setTotalEdges(totalEdges);

        // 统计：有通过测试结果关联到目标节点的算覆盖
        Set<String> coveredNodeIds = new HashSet<>();
        List<TestResult> testResults = testResultRepository.lambdaQuery()
                .eq(TestResult::getVersionId, versionId)
                .eq(TestResult::getResultStatus, "PASSED")
                .list();

        // 批量加载 TestCase，避免 N+1
        if (!testResults.isEmpty()) {
            List<String> testCaseIds = testResults.stream()
                    .map(TestResult::getTestCaseId)
                    .distinct()
                    .toList();
            List<TestCase> testCases = testCaseRepository.selectBatchIds(testCaseIds);
            Map<String, TestCase> testCaseMap = new HashMap<>();
            for (TestCase tc : testCases) {
                testCaseMap.put(tc.getId(), tc);
            }
            for (TestResult result : testResults) {
                TestCase testCase = testCaseMap.get(result.getTestCaseId());
                if (testCase != null && testCase.getTargetNodeId() != null) {
                    coveredNodeIds.add(testCase.getTargetNodeId());
                }
            }
        }

        long coveredNodes = coveredNodeIds.size();
        // 边覆盖率：通过 Cypher 直接统计连接已覆盖节点的边数
        long coveredEdges = neo4jGraphDao.countEdgesConnectedToNodes(
                projectId, versionId, new ArrayList<>(coveredNodeIds));

        report.setCoveredNodes(coveredNodes);
        report.setCoveredEdges(coveredEdges);

        BigDecimal nodeCoverage = totalNodes == 0 ? BigDecimal.ZERO :
                BigDecimal.valueOf(coveredNodes)
                        .divide(BigDecimal.valueOf(totalNodes), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
        BigDecimal edgeCoverage = totalEdges == 0 ? BigDecimal.ZERO :
                BigDecimal.valueOf(coveredEdges)
                        .divide(BigDecimal.valueOf(totalEdges), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));

        report.setCoveragePercentage(nodeCoverage);
        report.setEdgeCoveragePercentage(edgeCoverage);

        // 高置信度但未覆盖的节点：限制返回数量
        List<TestCoverageReport.UncoveredItem> highConfUncovered = new ArrayList<>();
        List<String> coveredList = new ArrayList<>(coveredNodeIds);
        for (GraphNode node : neo4jGraphDao.queryNodes(projectId, versionId, null, null, null, null, 300)) {
            if (node.getConfidence() != null
                    && node.getConfidence().compareTo(BigDecimal.valueOf(0.8)) >= 0
                    && !coveredNodeIds.contains(node.getId())) {
                TestCoverageReport.UncoveredItem item = new TestCoverageReport.UncoveredItem();
                item.setNodeId(node.getId());
                item.setNodeName(node.getNodeName());
                item.setNodeType(node.getNodeType());
                item.setConfidence(node.getConfidence());
                highConfUncovered.add(item);
            }
        }
        report.setHighConfidenceUncovered(highConfUncovered);

        saveReport(projectId, "TEST_COVERAGE", "测试覆盖率报告");

        return report;
    }

    /**
     * 生成图谱质量报告
     */
    @Cacheable(cacheNames = "report-graph-quality", key = "#projectId + ':' + #versionId")
    public GraphQualityReport generateGraphQualityReport(String projectId, String versionId) {
        log.info("Generating graph quality report for project {}, version {}", projectId, versionId);

        GraphQualityReport report = new GraphQualityReport();
        report.setProjectId(projectId);
        report.setVersionId(versionId);

        // 聚合统计替代全量加载
        Map<String, Object> stats = neo4jGraphDao.versionGraphStats(projectId, versionId);
        long totalNodes = toLong(stats.get("totalNodes"));
        long totalEdges = toLong(stats.get("totalEdges"));
        report.setTotalNodes(totalNodes);
        report.setTotalEdges(totalEdges);

        // 平均置信度
        BigDecimal avgConfidence = BigDecimal.valueOf(toDouble(stats.get("avgConfidence")));
        report.setAverageConfidence(avgConfidence);

        // 平均节点度数：Cypher 聚合
        report.setAverageNodeDegree(BigDecimal.valueOf(neo4jGraphDao.averageNodeDegree(projectId, versionId)));

        // 置信度分布：Cypher 聚合
        List<Map<String, Object>> distRows = neo4jGraphDao.confidenceDistribution(projectId, versionId);
        List<GraphQualityReport.ConfidenceBin> bins = new ArrayList<>();
        if (!distRows.isEmpty()) {
            Map<String, Object> row = distRows.get(0);
            double[] bounds = {0.0, 0.2, 0.4, 0.6, 0.8};
            for (int i = 0; i < bounds.length; i++) {
                GraphQualityReport.ConfidenceBin bin = new GraphQualityReport.ConfidenceBin();
                bin.setLowerBound(BigDecimal.valueOf(bounds[i]));
                bin.setUpperBound(BigDecimal.valueOf(bounds[i] + 0.2));
                bin.setNodeCount(toLong(row.get("bin" + i)));
                bins.add(bin);
            }
        }
        report.setConfidenceDistribution(bins);

        // 质量问题：限制返回数量
        List<GraphQualityReport.QualityIssue> issues = new ArrayList<>();
        for (GraphNode node : neo4jGraphDao.queryLowConfidenceNodes(projectId, versionId, 0.3, 200)) {
            GraphQualityReport.QualityIssue issue = new GraphQualityReport.QualityIssue();
            issue.setIssueType("LOW_CONFIDENCE");
            issue.setDescription("节点置信度极低 (" + (node.getConfidence() != null ? node.getConfidence() : "N/A") + ")");
            issue.setNodeId(node.getId());
            issue.setNodeName(node.getNodeName());
            issue.setImpact(node.getConfidence() != null ? BigDecimal.ONE.subtract(node.getConfidence()) : BigDecimal.ONE);
            issues.add(issue);
        }
        if (totalNodes > 1) {
            for (GraphNode node : neo4jGraphDao.queryDisconnectedNodes(projectId, versionId, 200)) {
                GraphQualityReport.QualityIssue issue = new GraphQualityReport.QualityIssue();
                issue.setIssueType("ISOLATED");
                issue.setDescription("节点孤立，没有任何连接关系");
                issue.setNodeId(node.getId());
                issue.setNodeName(node.getNodeName());
                issue.setImpact(BigDecimal.ONE);
                issues.add(issue);
            }
        }
        report.setQualityIssues(issues);

        // 质量评级
        BigDecimal qualityScore = avgConfidence.multiply(BigDecimal.valueOf(100))
                .subtract(BigDecimal.valueOf(issues.size() * 2L));
        if (qualityScore.compareTo(BigDecimal.valueOf(80)) >= 0) {
            report.setQualityRating("A");
        } else if (qualityScore.compareTo(BigDecimal.valueOf(60)) >= 0) {
            report.setQualityRating("B");
        } else if (qualityScore.compareTo(BigDecimal.valueOf(40)) >= 0) {
            report.setQualityRating("C");
        } else {
            report.setQualityRating("D");
        }

        // Claim/Gap 指标（Phase M1-M3）
        try {
            Map<String, Long> claimStatusCounts = knowledgeClaimService.countClaimsByStatus(projectId, versionId);
            report.setClaimCount(claimStatusCounts.values().stream().mapToLong(Long::longValue).sum());
            report.setConfirmedClaimCount(claimStatusCounts.getOrDefault("CONFIRMED", 0L));
            report.setPendingClaimCount(claimStatusCounts.getOrDefault("PENDING_CONFIRM", 0L));
            report.setConflictedClaimCount(claimStatusCounts.getOrDefault("CONFLICTED", 0L));
            report.setAiOnlyClaimCount(knowledgeClaimService.countAiOnlyClaims(projectId, versionId));

            Map<String, Long> gapStatusCounts = gapFinderService.countGapsByStatus(projectId, versionId);
            report.setGapCount(gapStatusCounts.values().stream().mapToLong(Long::longValue).sum());
            report.setOpenGapCount(gapStatusCounts.getOrDefault("OPEN", 0L)
                    + gapStatusCounts.getOrDefault("REOPENED", 0L));
            report.setHighSeverityGapCount(gapFinderService.countHighSeverityGaps(projectId, versionId));
            report.setGapCountByType(gapFinderService.countGapsByType(projectId, versionId));
        } catch (Exception e) {
            log.warn("Failed to load Claim/Gap metrics for quality report: projectId={}, versionId={}, err={}",
                    projectId, versionId, e.getMessage());
        }

        saveReport(projectId, "GRAPH_QUALITY", "图谱质量报告");

        return report;
    }

    /**
     * 失效所有报告缓存（图谱发生写入：合并/审核确认/重新扫描后调用）。
     * 报告为重聚合且变更频率远低于读取，整体清空成本可接受。
     */
    @org.springframework.cache.annotation.CacheEvict(cacheNames = {
            "report-confidence-trend", "report-test-coverage",
            "report-graph-quality", "report-graph-metrics",
            "report-migration-readiness"}, allEntries = true)
    public void evictReportCaches() {
        log.debug("Report caches evicted due to graph mutation");
    }

    /**
     * 生成图谱质量度量汇总（P2-3）
     * 输出覆盖率、证据完备度、待审核比例、测试通过率、运行时验证比例。
     */
    @Cacheable(cacheNames = "report-graph-metrics", key = "#projectId + ':' + #versionId")
    public GraphMetricsReport generateGraphMetrics(String projectId, String versionId) {
        log.info("Generating graph metrics for project {}, version {}", projectId, versionId);

        GraphMetricsReport report = new GraphMetricsReport();
        report.setProjectId(projectId);
        report.setVersionId(versionId);

        // 使用聚合查询替代全量节点加载
        Map<String, Object> stats = neo4jGraphDao.versionGraphStats(projectId, versionId);
        long total = toLong(stats.get("totalNodes"));
        report.setTotalNodes(total);
        report.setTotalEdges(toLong(stats.get("totalEdges")));

        if (total == 0) {
            report.setCoverageRatio(BigDecimal.ZERO);
            report.setEvidenceCompletenessRatio(BigDecimal.ZERO);
            report.setPendingReviewRatio(BigDecimal.ZERO);
            report.setRuntimeVerifiedRatio(BigDecimal.ZERO);
            report.setTestPassRatio(computeTestPassRatio(projectId, versionId));
            return report;
        }

        long confirmed = toLong(stats.get("confirmedNodes"));
        long pending = toLong(stats.get("pendingNodes"));
        long runtimeVerified = toLong(stats.get("runtimeVerifiedCount"));
        long withEvidence = toLong(stats.get("withEvidenceCount"));

        report.setCoverageRatio(ratio(confirmed, total));
        report.setPendingReviewRatio(ratio(pending, total));
        report.setRuntimeVerifiedRatio(ratio(runtimeVerified, total));
        report.setEvidenceCompletenessRatio(ratio(withEvidence, total));
        report.setTestPassRatio(computeTestPassRatio(projectId, versionId));

        return report;
    }

    /**
     * 生成报告洞察与行动建议。
     * 将图谱指标、低置信节点、孤立节点、高置信未覆盖节点整理为 LLM 输入。
     */
    public ReportInsight generateReportInsights(String projectId, String versionId) {
        // 使用聚合查询替代全量节点/边加载
        Map<String, Object> stats = neo4jGraphDao.versionGraphStats(projectId, versionId);
        long totalNodes = toLong(stats.get("totalNodes"));
        long totalEdges = toLong(stats.get("totalEdges"));
        long pending = toLong(stats.get("pendingNodes"));

        Set<String> coveredNodeIds = findCoveredNodeIds(projectId, versionId);

        // 高置信未覆盖节点：限制查询数量
        List<Map<String, Object>> highConfidenceUncovered = new ArrayList<>();
        for (GraphNode node : neo4jGraphDao.queryNodes(projectId, versionId, null, null, null, null, 100)) {
            if (node.getConfidence() != null
                    && node.getConfidence().compareTo(BigDecimal.valueOf(0.8)) >= 0
                    && !coveredNodeIds.contains(node.getId())) {
                if (highConfidenceUncovered.size() >= 50) break;
                highConfidenceUncovered.add(nodeSummary(node));
            }
        }

        // 低置信节点
        List<Map<String, Object>> lowConfidenceNodes = new ArrayList<>();
        for (GraphNode node : neo4jGraphDao.queryLowConfidenceNodes(projectId, versionId, 0.5, 50)) {
            lowConfidenceNodes.add(nodeSummary(node));
        }

        // 孤立节点
        List<Map<String, Object>> isolatedNodes = new ArrayList<>();
        if (totalNodes > 1) {
            for (GraphNode node : neo4jGraphDao.queryDisconnectedNodes(projectId, versionId, 50)) {
                isolatedNodes.add(nodeSummary(node));
            }
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalNodes", totalNodes);
        metrics.put("totalEdges", totalEdges);
        metrics.put("averageConfidence", stats.get("avgConfidence"));
        metrics.put("coveredNodeCount", coveredNodeIds.size());
        metrics.put("pendingReviewRatio", ratio(pending, totalNodes));
        metrics.put("testPassRatio", computeTestPassRatio(projectId, versionId));

        Map<String, Object> gaps = new LinkedHashMap<>();
        gaps.put("highConfidenceUncovered", highConfidenceUncovered);
        gaps.put("lowConfidenceNodes", lowConfidenceNodes);
        gaps.put("isolatedNodes", isolatedNodes);

        try {
            return reportInsightAgent.generateInsights(projectId,
                    objectMapper.writeValueAsString(metrics),
                    objectMapper.writeValueAsString(gaps));
        } catch (Exception e) {
            log.warn("ReportInsightAgent failed, using rule-based fallback: projectId={}, versionId={}, error={}",
                    projectId, versionId, e.getMessage());
            return fallbackReportInsight(highConfidenceUncovered, lowConfidenceNodes, isolatedNodes);
        }
    }

    private Set<String> findCoveredNodeIds(String projectId, String versionId) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TestResult> trQuery =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        trQuery.eq(TestResult::getProjectId, projectId)
                .eq(TestResult::getVersionId, versionId)
                .eq(TestResult::getResultStatus, "PASSED");
        List<TestResult> testResults = testResultRepository.selectList(trQuery);
        Set<String> coveredNodeIds = new HashSet<>();
        if (!testResults.isEmpty()) {
            List<String> testCaseIds = testResults.stream()
                    .map(TestResult::getTestCaseId)
                    .distinct()
                    .toList();
            List<TestCase> testCases = testCaseRepository.selectBatchIds(testCaseIds);
            Map<String, String> targetNodeMap = new HashMap<>();
            for (TestCase tc : testCases) {
                if (tc.getTargetNodeId() != null) {
                    targetNodeMap.put(tc.getId(), tc.getTargetNodeId());
                }
            }
            for (TestResult result : testResults) {
                String targetNodeId = targetNodeMap.get(result.getTestCaseId());
                if (targetNodeId != null) {
                    coveredNodeIds.add(targetNodeId);
                }
            }
        }
        return coveredNodeIds;
    }

    private Map<String, Object> nodeSummary(GraphNode node) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", node.getId());
        summary.put("name", node.getNodeName());
        summary.put("type", node.getNodeType());
        summary.put("confidence", node.getConfidence());
        summary.put("status", node.getStatus());
        return summary;
    }

    private ReportInsight fallbackReportInsight(List<Map<String, Object>> highConfidenceUncovered,
                                                List<Map<String, Object>> lowConfidenceNodes,
                                                List<Map<String, Object>> isolatedNodes) {
        ReportInsight insight = new ReportInsight();
        insight.setSummary("AI 洞察暂不可用，已按图谱缺口生成规则建议");
        List<ReportInsight.ActionItem> actions = new ArrayList<>();
        if (!highConfidenceUncovered.isEmpty()) {
            actions.add(action("补充高置信节点测试", "GENERATE_TEST", "HIGH",
                    "高置信节点尚未被通过用例覆盖", highConfidenceUncovered));
        }
        if (!lowConfidenceNodes.isEmpty()) {
            actions.add(action("优先审核低置信节点", "REVIEW_LOW_CONFIDENCE", "HIGH",
                    "低置信节点会拉低迁移就绪度", lowConfidenceNodes));
        }
        if (!isolatedNodes.isEmpty()) {
            actions.add(action("补全孤立节点关系", "FIX_GRAPH_LINKS", "MEDIUM",
                    "孤立节点缺少上下游关系，影响影响面分析", isolatedNodes));
        }
        insight.setActions(actions);
        return insight;
    }

    private ReportInsight.ActionItem action(String title, String actionType, String priority,
                                            String rationale, List<Map<String, Object>> nodes) {
        ReportInsight.ActionItem action = new ReportInsight.ActionItem();
        action.setTitle(title);
        action.setActionType(actionType);
        action.setPriority(priority);
        action.setSource("rule-fallback");
        action.setRationale(rationale);
        action.setExpectedBenefit("提升报告可信度和后续迁移决策质量");
        action.setTargets(nodes.stream()
                .limit(5)
                .map(n -> String.valueOf(n.get("name")))
                .toList());
        return action;
    }

    private BigDecimal computeTestPassRatio(String projectId, String versionId) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TestResult> trQuery =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        trQuery.eq(TestResult::getProjectId, projectId).eq(TestResult::getVersionId, versionId);
        List<TestResult> results = testResultRepository.selectList(trQuery);
        if (results.isEmpty()) {
            return BigDecimal.ZERO;
        }
        long passed = results.stream().filter(r -> "PASSED".equals(r.getResultStatus())).count();
        return ratio(passed, results.size());
    }

    private BigDecimal ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    /**
     * 获取图谱构建过程统计数据
     * 用于 GRAPH_BUILD_DETAIL 报告类型的生成
     */
    public Map<String, Object> getGraphBuildStats(String projectId, String versionId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        try {
            Map<String, Object> gs = versionId != null && !versionId.isBlank()
                    ? neo4jGraphDao.versionGraphStats(projectId, versionId)
                    : neo4jGraphDao.graphStats(projectId);

            stats.put("totalNodes", gs.getOrDefault("totalNodes", 0L));
            stats.put("totalEdges", gs.getOrDefault("totalEdges", 0L));
            stats.put("aiNodes", gs.getOrDefault("aiOnlyNodes", 0L));
            stats.put("aiEdges", gs.getOrDefault("aiOnlyEdges", 0L));
            stats.put("evidenceCount", gs.getOrDefault("withEvidenceCount", 0L));
        } catch (Exception e) {
            log.warn("Failed to load graph stats for build detail: projectId={}, versionId={}", projectId, versionId, e);
            stats.put("totalNodes", 0L);
            stats.put("totalEdges", 0L);
            stats.put("aiNodes", 0L);
            stats.put("aiEdges", 0L);
            stats.put("evidenceCount", 0L);
        }

        // 扫描相关统计（从 report 表获取最近报告记录）
        try {
            long scanTaskCount = reportRepository.lambdaQuery()
                    .eq(io.github.legacygraph.entity.Report::getProjectId, projectId)
                    .count();
            stats.put("scanTaskCount", scanTaskCount);
        } catch (Exception e) {
            stats.put("scanTaskCount", 0L);
        }

        stats.putIfAbsent("codeFileCount", 0L);
        stats.putIfAbsent("sqlFileCount", 0L);
        stats.putIfAbsent("docFileCount", 0L);
        stats.putIfAbsent("extractorCount", 0L);
        stats.putIfAbsent("extractorDetails", Collections.emptyList());

        return stats;
    }

    /**
     * 导出报告到文件（委托给 ReportExportService 实现多格式导出）
     */
    public byte[] exportReport(String reportId, String format) throws Exception {
        log.info("ReportingService.exportReport delegating to ReportExportService: reportId={}, format={}",
                reportId, format);

        Report report = reportRepository.selectById(reportId);
        if (report == null) {
            throw new IllegalArgumentException("Report not found: " + reportId);
        }

        if (isMarkdown(format) && report.getFilePath() != null && !report.getFilePath().isBlank()) {
            Path reportPath = Path.of(report.getFilePath());
            if (Files.isRegularFile(reportPath)) {
                return Files.readAllBytes(reportPath);
            }
            log.debug("Report filePath is not a local file, fallback to dynamic export: {}", report.getFilePath());
        }

        // 委托给 ReportExportService
        ReportExportService.ReportType reportType;
        try {
            reportType = ReportExportService.ReportType.valueOf(report.getReportType());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown report type: " + report.getReportType(), e);
        }

        String normalizedFormat = format == null || format.isBlank() ? "MD" : format;
        ReportExportService.ExportFormat exportFormat = switch (normalizedFormat.toUpperCase()) {
            case "MD", "MARKDOWN" -> ReportExportService.ExportFormat.MD;
            case "PDF" -> ReportExportService.ExportFormat.PDF;
            case "EXCEL", "XLSX" -> ReportExportService.ExportFormat.EXCEL;
            default -> ReportExportService.ExportFormat.MD;
        };

        return reportExportService.exportReport(report.getProjectId(), report.getVersionId(), reportType, exportFormat);
    }

    private boolean isMarkdown(String format) {
        return format == null || format.isBlank()
                || "MD".equalsIgnoreCase(format)
                || "MARKDOWN".equalsIgnoreCase(format);
    }

    /**
     * 保存报告记录到数据库，并上传到MinIO
     */
    @Transactional
    protected Report saveReport(String projectId, String reportType, String reportName) {
        Report report = new Report();
        report.setId(UUID.randomUUID().toString());
        report.setProjectId(projectId);
        report.setReportType(reportType);
        report.setReportName(reportName);
        report.setStatus("COMPLETED");
        report.setGeneratedAt(LocalDateTime.now());
        report.setCompletedAt(LocalDateTime.now());

        reportRepository.insert(report);

        // Upload report metadata to MinIO. Do not regenerate report data here:
        // generation methods call saveReport(), so regenerating would recurse.
        try {
            if (minioClient == null) {
                log.debug("MinIO client not available, skipping report upload");
                return report;
            }
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
                minioClient.makeBucket(io.minio.MakeBucketArgs.builder()
                        .bucket(bucketName)
                        .build());
            }

            byte[] jsonBytes = objectMapper.writeValueAsBytes(report);
            String objectName = String.format("%s/%s/%s-metadata.json", projectId, report.getId(), reportType);

            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(new ByteArrayInputStream(jsonBytes), (long) jsonBytes.length, -1L)
                    .contentType("application/json")
                    .build());

            log.info("Report metadata uploaded to MinIO: {}", objectName);
        } catch (Exception e) {
            log.error("Failed to upload report to MinIO", e);
        }

        return report;
    }

    /**
     * 获取项目报告列表
     */
    public List<Report> listReports(String projectId) {
        return reportRepository.findByProjectId(projectId);
    }

    // ========== 辅助方法 ==========

    private String getNodeTypeDisplayName(String nodeType) {
        return switch (nodeType) {
            case "ApiEndpoint" -> "API接口";
            case "DatabaseTable" -> "数据库表";
            case "BusinessObject" -> "业务对象";
            case "BusinessProcess" -> "业务流程";
            case "BusinessRule" -> "业务规则";
            default -> nodeType;
        };
    }

    private List<String> generateRecommendations(MigrationReadinessReport report) {
        List<String> recommendations = new ArrayList<>();

        BigDecimal overall = report.getOverallScore();
        if (overall.compareTo(BigDecimal.valueOf(80)) >= 0) {
            recommendations.add("项目整体就绪度良好，可以开始迁移准备");
        } else if (overall.compareTo(BigDecimal.valueOf(60)) >= 0) {
            recommendations.add("项目需要补充部分低置信度节点的人工审核");
            recommendations.add("建议增加测试用例提升覆盖率");
        } else {
            recommendations.add("项目理解度较低，建议先完成更多扫描和人工审核");
            recommendations.add("重点解决低置信度节点和孤立节点");
        }

        if (report.getRiskItems().size() > 10) {
            recommendations.add("存在大量风险项，建议分批处理");
        }

        if (report.getPendingNodes() > report.getTotalNodes() * 0.3) {
            recommendations.add("超过 30% 节点待确认，建议安排人工审核");
        }

        return recommendations;
    }
}
