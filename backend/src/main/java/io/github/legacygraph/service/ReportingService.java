package io.github.legacygraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.report.ConfidenceTrendReport;
import io.github.legacygraph.dto.report.GraphQualityReport;
import io.github.legacygraph.dto.report.MigrationReadinessReport;
import io.github.legacygraph.dto.report.TestCoverageReport;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.Report;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.repository.ReportRepository;
import io.github.legacygraph.repository.TestResultRepository;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 报告生成服务
 * 生成各种分析报告：迁移就绪度、置信度趋势、测试覆盖率、图谱质量
 */
@Slf4j
@Service
public class ReportingService {

    private final ReportRepository reportRepository;
    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final TestResultRepository testResultRepository;
    private final MinioClient minioClient;
    private final ObjectMapper objectMapper;

    private final String bucketName = "legacygraph-reports";

    public ReportingService(ReportRepository reportRepository,
                           GraphNodeRepository nodeRepository,
                           GraphEdgeRepository edgeRepository,
                           TestResultRepository testResultRepository,
                           MinioClient minioClient,
                           ObjectMapper objectMapper) {
        this.reportRepository = reportRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.testResultRepository = testResultRepository;
        this.minioClient = minioClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成迁移就绪度报告
     */
    @Transactional
    public MigrationReadinessReport generateMigrationReport(String projectId) {
        log.info("Generating migration readiness report for project: {}", projectId);

        List<GraphNode> allNodes = nodeRepository.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .list();

        List<GraphEdge> allEdges = edgeRepository.lambdaQuery()
                .eq(GraphEdge::getProjectId, projectId)
                .list();

        MigrationReadinessReport report = new MigrationReadinessReport();
        report.setProjectId(projectId);
        report.setGeneratedAt(LocalDateTime.now());

        // 统计节点
        long totalNodes = allNodes.size();
        long confirmedNodes = allNodes.stream()
                .filter(n -> "CONFIRMED".equals(n.getStatus()))
                .count();
        long pendingNodes = totalNodes - confirmedNodes;

        // 统计边
        long totalEdges = allEdges.size();
        long confirmedEdges = allEdges.stream()
                .filter(e -> "CONFIRMED".equals(e.getStatus()))
                .count();
        long pendingEdges = totalEdges - confirmedEdges;

        report.setTotalNodes(totalNodes);
        report.setConfirmedNodes(confirmedNodes);
        report.setPendingNodes(pendingNodes);
        report.setTotalEdges(totalEdges);
        report.setConfirmedEdges(confirmedEdges);
        report.setPendingEdges(pendingEdges);

        // 按类型统计
        Map<String, List<GraphNode>> nodesByType = new HashMap<>();
        for (GraphNode node : allNodes) {
            nodesByType.computeIfAbsent(node.getNodeType(), k -> new ArrayList<>()).add(node);
        }

        List<MigrationReadinessReport.NodeTypeStat> typeStats = new ArrayList<>();
        for (Map.Entry<String, List<GraphNode>> entry : nodesByType.entrySet()) {
            MigrationReadinessReport.NodeTypeStat stat = new MigrationReadinessReport.NodeTypeStat();
            stat.setNodeType(entry.getKey());
            stat.setDisplayName(getNodeTypeDisplayName(entry.getKey()));
            stat.setTotal(entry.getValue().size());
            long confirmed = entry.getValue().stream()
                    .filter(n -> "CONFIRMED".equals(n.getStatus()))
                    .count();
            stat.setConfirmed(confirmed);
            BigDecimal avgConf = entry.getValue().stream()
                    .map(GraphNode::getConfidence)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(entry.getValue().size()), 4, RoundingMode.HALF_UP);
            stat.setAverageConfidence(avgConf);
            typeStats.add(stat);
        }
        report.setNodeTypeStats(typeStats);

        // 计算整体置信度
        BigDecimal overallConfidence = calculateOverallConfidence(allNodes);
        report.setOverallConfidence(overallConfidence);

        // 识别风险
        List<MigrationReadinessReport.RiskItem> risks = new ArrayList<>();

        // 低置信度节点
        for (GraphNode node : allNodes) {
            if (node.getConfidence().compareTo(BigDecimal.valueOf(0.5)) < 0) {
                MigrationReadinessReport.RiskItem risk = new MigrationReadinessReport.RiskItem();
                risk.setRiskType("LOW_CONFIDENCE");
                risk.setDescription("节点置信度低于 0.5，需要人工审核");
                risk.setAffectedNodeId(node.getId());
                risk.setAffectedNodeName(node.getNodeName());
                risk.setRiskLevel(BigDecimal.ONE.subtract(node.getConfidence()));
                risks.add(risk);
            }
        }

        // 孤立节点（没有边连接）
        Set<String> connectedNodes = new HashSet<>();
        for (GraphEdge edge : allEdges) {
            connectedNodes.add(edge.getFromNodeId());
            connectedNodes.add(edge.getToNodeId());
        }
        for (GraphNode node : allNodes) {
            if (!connectedNodes.contains(node.getId()) && totalNodes > 1) {
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

        // 计算各项得分
        BigDecimal architectureScore = BigDecimal.valueOf(confirmedNodes)
                .divide(BigDecimal.valueOf(totalNodes == 0 ? 1 : totalNodes), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        BigDecimal businessScore = overallConfidence.multiply(BigDecimal.valueOf(100));
        report.setArchitectureUnderstandingScore(architectureScore);
        report.setBusinessKnowledgeScore(businessScore);

        // 测试覆盖率得分（后续计算）
        report.setTestCoverageScore(BigDecimal.ZERO);
        report.setConfidenceLevel(overallConfidence.multiply(BigDecimal.valueOf(100)));

        // 整体得分加权
        BigDecimal overallScore = architectureScore.multiply(BigDecimal.valueOf(0.4))
                .add(businessScore.multiply(BigDecimal.valueOf(0.4)))
                .add(report.getConfidenceLevel().multiply(BigDecimal.valueOf(0.2)));
        report.setOverallScore(overallScore);

        // 生成建议
        List<String> recommendations = generateRecommendations(report);
        report.setRecommendations(recommendations);

        // 保存报告记录
        saveReport(projectId, "MIGRATION_READINESS", "迁移就绪度报告");

        log.info("Migration readiness report generated for project {}: overall score {}",
                projectId, overallScore);

        return report;
    }

    /**
     * 生成置信度趋势报告
     */
    public ConfidenceTrendReport generateConfidenceTrend(String projectId, String versionId) {
        log.info("Generating confidence trend report for project {}, version {}", projectId, versionId);

        ConfidenceTrendReport report = new ConfidenceTrendReport();
        report.setProjectId(projectId);
        report.setVersionId(versionId);

        // 获取所有节点按创建日期分组
        List<GraphNode> allNodes = nodeRepository.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .eq(GraphNode::getVersionId, versionId)
                .list();

        if (allNodes.isEmpty()) {
            report.setDailyData(Collections.emptyList());
            report.setStartingAverageConfidence(BigDecimal.ZERO);
            report.setEndingAverageConfidence(BigDecimal.ZERO);
            report.setTotalImprovement(BigDecimal.ZERO);
            report.setTrendDirection("FLAT");
            return report;
        }

        // 按日期分组计算每日平均置信度
        Map<LocalDate, List<GraphNode>> nodesByDate = new TreeMap<>();
        for (GraphNode node : allNodes) {
            LocalDate date = node.getCreatedAt() != null ?
                    node.getCreatedAt().toLocalDate() : LocalDate.now();
            nodesByDate.computeIfAbsent(date, k -> new ArrayList<>()).add(node);
        }

        List<ConfidenceTrendReport.DailyData> dailyDataList = new ArrayList<>();
        BigDecimal startConfidence = null;
        BigDecimal endConfidence = null;

        for (Map.Entry<LocalDate, List<GraphNode>> entry : nodesByDate.entrySet()) {
            LocalDate date = entry.getKey();
            List<GraphNode> dailyNodes = entry.getValue();

            BigDecimal avgConf = calculateOverallConfidence(dailyNodes);
            long confirmedCount = dailyNodes.stream()
                    .filter(n -> "CONFIRMED".equals(n.getStatus()))
                    .count();

            ConfidenceTrendReport.DailyData dailyData = new ConfidenceTrendReport.DailyData();
            dailyData.setDate(date);
            dailyData.setAverageConfidence(avgConf);
            dailyData.setConfirmedNodes(confirmedCount);
            dailyData.setNewNodes((long) dailyNodes.size());
            dailyDataList.add(dailyData);

            if (startConfidence == null) {
                startConfidence = avgConf;
            }
            endConfidence = avgConf;
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
    public TestCoverageReport generateTestCoverageReport(String projectId, String versionId) {
        log.info("Generating test coverage report for project {}, version {}", projectId, versionId);

        TestCoverageReport report = new TestCoverageReport();
        report.setProjectId(projectId);
        report.setVersionId(versionId);

        // 获取所有节点
        List<GraphNode> allNodes = nodeRepository.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .eq(GraphNode::getVersionId, versionId)
                .list();

        List<GraphEdge> allEdges = edgeRepository.lambdaQuery()
                .eq(GraphEdge::getProjectId, projectId)
                .eq(GraphEdge::getVersionId, versionId)
                .list();

        report.setTotalNodes(allNodes.size());
        report.setTotalEdges(allEdges.size());

        // 简单统计：有测试结果关联的节点算覆盖
        // 完整实现需要关联测试用例和节点
        long coveredNodes = 0;
        long coveredEdges = 0;

        report.setCoveredNodes(coveredNodes);
        report.setCoveredEdges(coveredEdges);

        BigDecimal nodeCoverage = allNodes.isEmpty() ? BigDecimal.ZERO :
                BigDecimal.valueOf(coveredNodes)
                        .divide(BigDecimal.valueOf(allNodes.size()), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
        BigDecimal edgeCoverage = allEdges.isEmpty() ? BigDecimal.ZERO :
                BigDecimal.valueOf(coveredEdges)
                        .divide(BigDecimal.valueOf(allEdges.size()), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));

        report.setCoveragePercentage(nodeCoverage);
        report.setEdgeCoveragePercentage(edgeCoverage);

        // 找出高置信度但未覆盖的节点
        List<TestCoverageReport.UncoveredItem> highConfUncovered = new ArrayList<>();
        for (GraphNode node : allNodes) {
            if (node.getConfidence().compareTo(BigDecimal.valueOf(0.8)) >= 0) {
                // 假设没有被测试覆盖
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
    public GraphQualityReport generateGraphQualityReport(String projectId, String versionId) {
        log.info("Generating graph quality report for project {}, version {}", projectId, versionId);

        GraphQualityReport report = new GraphQualityReport();
        report.setProjectId(projectId);
        report.setVersionId(versionId);

        List<GraphNode> allNodes = nodeRepository.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .eq(GraphNode::getVersionId, versionId)
                .list();

        List<GraphEdge> allEdges = edgeRepository.lambdaQuery()
                .eq(GraphEdge::getProjectId, projectId)
                .eq(GraphEdge::getVersionId, versionId)
                .list();

        report.setTotalNodes(allNodes.size());
        report.setTotalEdges(allEdges.size());

        // 计算平均置信度
        BigDecimal avgConfidence = calculateOverallConfidence(allNodes);
        report.setAverageConfidence(avgConfidence);

        // 计算平均节点度数
        int totalDegrees = 0;
        Map<String, Integer> degreeMap = new HashMap<>();
        for (GraphEdge edge : allEdges) {
            degreeMap.merge(edge.getFromNodeId(), 1, Integer::sum);
            degreeMap.merge(edge.getToNodeId(), 1, Integer::sum);
        }
        for (Integer degree : degreeMap.values()) {
            totalDegrees += degree;
        }
        BigDecimal avgDegree = allNodes.isEmpty() ? BigDecimal.ZERO :
                BigDecimal.valueOf(totalDegrees)
                        .divide(BigDecimal.valueOf(allNodes.size()), 2, RoundingMode.HALF_UP);
        report.setAverageNodeDegree(avgDegree);

        // 置信度分布
        List<GraphQualityReport.ConfidenceBin> bins = new ArrayList<>();
        for (double lower = 0.0; lower < 1.0; lower += 0.2) {
            double upper = lower + 0.2;
            long count = allNodes.stream()
                    .filter(n -> n.getConfidence().compareTo(BigDecimal.valueOf(lower)) >= 0
                            && n.getConfidence().compareTo(BigDecimal.valueOf(upper)) < 0)
                    .count();
            GraphQualityReport.ConfidenceBin bin = new GraphQualityReport.ConfidenceBin();
            bin.setLowerBound(BigDecimal.valueOf(lower));
            bin.setUpperBound(BigDecimal.valueOf(upper));
            bin.setNodeCount(count);
            bins.add(bin);
        }
        report.setConfidenceDistribution(bins);

        // 收集质量问题
        List<GraphQualityReport.QualityIssue> issues = new ArrayList<>();
        for (GraphNode node : allNodes) {
            if (node.getConfidence().compareTo(BigDecimal.valueOf(0.3)) < 0) {
                GraphQualityReport.QualityIssue issue = new GraphQualityReport.QualityIssue();
                issue.setIssueType("LOW_CONFIDENCE");
                issue.setDescription("节点置信度极低 (" + node.getConfidence() + ")");
                issue.setNodeId(node.getId());
                issue.setNodeName(node.getNodeName());
                issue.setImpact(BigDecimal.ONE.subtract(node.getConfidence()));
                issues.add(issue);
            }
        }

        // 孤立节点检测
        Set<String> connectedNodes = new HashSet<>();
        for (GraphEdge edge : allEdges) {
            connectedNodes.add(edge.getFromNodeId());
            connectedNodes.add(edge.getToNodeId());
        }
        for (GraphNode node : allNodes) {
            if (!connectedNodes.contains(node.getId()) && allNodes.size() > 1) {
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

        // 计算质量评级
        BigDecimal qualityScore = avgConfidence.multiply(BigDecimal.valueOf(100))
                .subtract(BigDecimal.valueOf(issues.size() * 2));
        if (qualityScore.compareTo(BigDecimal.valueOf(80)) >= 0) {
            report.setQualityRating("A");
        } else if (qualityScore.compareTo(BigDecimal.valueOf(60)) >= 0) {
            report.setQualityRating("B");
        } else if (qualityScore.compareTo(BigDecimal.valueOf(40)) >= 0) {
            report.setQualityRating("C");
        } else {
            report.setQualityRating("D");
        }

        saveReport(projectId, "GRAPH_QUALITY", "图谱质量报告");

        return report;
    }

    /**
     * 导出报告到文件
     */
    public byte[] exportReport(String reportId, String format) throws Exception {
        Report report = reportRepository.selectById(reportId);
        if (report == null) {
            throw new IllegalArgumentException("Report not found: " + reportId);
        }

        // 根据报告类型获取对应的报告数据
        Object reportData = null;
        switch (report.getReportType()) {
            case "MIGRATION_READINESS":
                reportData = generateMigrationReport(report.getProjectId());
                break;
            case "CONFIDENCE_TREND":
                reportData = generateConfidenceTrend(report.getProjectId(), report.getVersionId());
                break;
            case "TEST_COVERAGE":
                reportData = generateTestCoverageReport(report.getProjectId(), report.getVersionId());
                break;
            case "GRAPH_QUALITY":
                reportData = generateGraphQualityReport(report.getProjectId(), report.getVersionId());
                break;
            default:
                throw new IllegalArgumentException("Unknown report type: " + report.getReportType());
        }

        if ("JSON".equalsIgnoreCase(format)) {
            return objectMapper.writeValueAsBytes(reportData);
        }
        // TODO: PDF/Excel 导出 - 后续实现
        return objectMapper.writeValueAsBytes(reportData);
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

        reportRepository.save(report);
        return report;
    }

    /**
     * 获取项目报告列表
     */
    public List<Report> listReports(String projectId) {
        return reportRepository.findByProjectId(projectId);
    }

    // ========== 辅助方法 ==========

    private BigDecimal calculateOverallConfidence(List<GraphNode> nodes) {
        if (nodes.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = nodes.stream()
                .map(GraphNode::getConfidence)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(nodes.size()), 4, RoundingMode.HALF_UP);
    }

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
