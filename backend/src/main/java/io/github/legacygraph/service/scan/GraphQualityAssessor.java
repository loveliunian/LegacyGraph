package io.github.legacygraph.service.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.EdgeEvidence;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.EdgeEvidenceRepository;
import io.github.legacygraph.repository.NodeEvidenceRepository;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图谱质量评估服务 — 扫描完成后自动评估图谱完整性、连通性、一致性、准确性，并生成 Markdown 报告。
 *
 * <p>评估维度：
 * <ul>
 *   <li><b>完整性</b> — 节点/边类型分布、关键类型覆盖率、边/节点比</li>
 *   <li><b>连通性</b> — 孤立节点比例、平均连通度</li>
 *   <li><b>一致性</b> — 本体约束校验（Method/Column/ApiEndpoint/SqlStatement 的必需边）</li>
 *   <li><b>准确性</b>（拆为 4 个独立指标）：
 *     <ul>
 *       <li>结构完整性 — 悬空边、重复节点、约束违反</li>
 *       <li>三元组准确率 — 抽样边有证据支撑的比例</li>
 *       <li>证据覆盖率 — 节点/边有证据关联的比例</li>
 *       <li>置信度校准 — 按来源分桶统计 Claim 确认/待确认/驳回分布</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>报告输出到 {@code docs/legacygraph/graph-quality-report.md}，
 * 文件目录解析逻辑与 {@link ScanArtifactPublisher} 一致。
 */
@Slf4j
@Service
public class GraphQualityAssessor {

    /** 关键节点类型 — 用于覆盖率检查，数量为 0 视为缺失 */
    private static final List<String> KEY_NODE_TYPES = List.of(
            "Table", "Column", "Service", "Controller", "ApiEndpoint", "Method");

    /** 本体约束规则定义：节点标签 → 必需的边类型列表 */
    private static final List<ConstraintRule> CONSTRAINT_RULES = List.of(
            new ConstraintRule("Method", "Method 应有 BELONGS_TO 或 CONTAINS 边归属 Class/Service",
                    List.of("BELONGS_TO", "CONTAINS")),
            new ConstraintRule("Column", "Column 应有 HAS_COLUMN 边关联 Table",
                    List.of("HAS_COLUMN")),
            new ConstraintRule("ApiEndpoint", "ApiEndpoint 应有 HANDLED_BY 边指向 Controller",
                    List.of("HANDLED_BY")),
            new ConstraintRule("SqlStatement", "SqlStatement 应有 READS 或 WRITES 边",
                    List.of("READS", "WRITES"))
    );

    /** 置信度校准分桶的来源类型 */
    private static final List<String> CALIBRATION_SOURCE_TYPES = List.of(
            "CODE", "DOC_AI", "AI_INFERENCE", "RUNTIME");

    private static final String REPORT_FILENAME = "graph-quality-report.md";
    private static final String DOCS_SUBDIR = "docs/legacygraph";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Neo4jGraphDao graphDao;
    private final CodeRepoRepository codeRepoRepository;
    private final NodeEvidenceRepository nodeEvidenceRepository;
    private final EdgeEvidenceRepository edgeEvidenceRepository;
    private final KnowledgeClaimService knowledgeClaimService;

    /** 当项目根目录无法解析时的回退目录 */
    @Value("${legacygraph.reports.local-dir:${user.home}/.legacygraph/reports}")
    private String fallbackReportRoot;

    @Autowired
    public GraphQualityAssessor(Neo4jGraphDao graphDao, CodeRepoRepository codeRepoRepository,
                                NodeEvidenceRepository nodeEvidenceRepository,
                                EdgeEvidenceRepository edgeEvidenceRepository,
                                KnowledgeClaimService knowledgeClaimService) {
        this.graphDao = graphDao;
        this.codeRepoRepository = codeRepoRepository;
        this.nodeEvidenceRepository = nodeEvidenceRepository;
        this.edgeEvidenceRepository = edgeEvidenceRepository;
        this.knowledgeClaimService = knowledgeClaimService;
    }

    /**
     * 执行图谱质量评估并生成报告文件。
     *
     * <p>评估流程：完整性 → 连通性 → 一致性 → 准确性 → 生成 Markdown → 写入 docs/legacygraph/。</p>
     *
     * @param projectId 项目 ID
     * @param versionId 扫描版本 ID
     */
    public void assessAndReport(String projectId, String versionId) {
        Path docsDir = resolveDocsDir(projectId);
        if (docsDir == null) {
            log.warn("GraphQualityAssessor: cannot resolve docs dir for projectId={}, skip assessment", projectId);
            return;
        }
        try {
            Files.createDirectories(docsDir);
        } catch (IOException e) {
            log.warn("GraphQualityAssessor: failed to create docs dir {}: {}", docsDir, e.getMessage());
            return;
        }

        log.info("GraphQualityAssessor: assessing graph quality for projectId={}, versionId={}", projectId, versionId);

        // 1. 完整性评估
        Map<String, Object> versionStats = graphDao.versionGraphStats(projectId, versionId);
        long totalNodes = toLong(versionStats.get("totalNodes"));
        long totalEdges = toLong(versionStats.get("totalEdges"));
        List<Map<String, Object>> nodeDist = graphDao.nodeTypeDistribution(projectId, versionId);
        List<Map<String, Object>> edgeDist = graphDao.edgeTypeDistribution(projectId, versionId);
        double edgeNodeRatio = totalNodes > 0 ? (double) totalEdges / totalNodes : 0.0;

        // 2. 连通性评估
        long isolatedCount = graphDao.countIsolatedNodes(projectId, versionId);
        double isolatedRate = totalNodes > 0 ? (double) isolatedCount / totalNodes : 0.0;
        double avgDegree = graphDao.averageNodeDegree(projectId, versionId);
        String connectivityGrade = gradeConnectivity(isolatedRate, avgDegree);

        // 3. 一致性评估（约束违反列表，同时供结构完整性指标汇总）
        List<ConstraintViolation> violations = assessConstraints(projectId, versionId);

        // 4. 准确性评估（拆为 4 个独立指标）
        StructuralIntegrityMetric structuralMetric = assessStructuralIntegrity(projectId, versionId, violations);
        TripleAccuracyMetric tripleMetric = assessTripleAccuracy(projectId, versionId);
        EvidenceCoverageMetric coverageMetric = assessEvidenceCoverage(projectId, versionId, totalNodes, totalEdges);
        CalibrationMetric calibrationMetric = assessCalibration(projectId, versionId);

        // 5. 生成报告
        String markdown = buildReport(projectId, versionId, totalNodes, totalEdges, edgeNodeRatio,
                nodeDist, edgeDist, isolatedCount, isolatedRate, avgDegree, connectivityGrade,
                violations, structuralMetric, tripleMetric, coverageMetric, calibrationMetric);

        // 6. 写入文件
        Path reportFile = docsDir.resolve(REPORT_FILENAME);
        try {
            Files.writeString(reportFile, markdown, StandardCharsets.UTF_8);
            log.info("GraphQualityAssessor: quality report written to {}", reportFile);
        } catch (IOException e) {
            log.warn("GraphQualityAssessor: failed to write report to {}: {}", reportFile, e.getMessage());
        }
    }

    // ==================== 一致性评估 ====================

    /**
     * 逐条检查本体约束规则，统计违反数。
     */
    private List<ConstraintViolation> assessConstraints(String projectId, String versionId) {
        List<ConstraintViolation> results = new ArrayList<>();
        for (ConstraintRule rule : CONSTRAINT_RULES) {
            long violationCount = graphDao.countNodesWithoutEdgeTypes(
                    projectId, versionId, rule.nodeLabel, rule.edgeTypes);
            results.add(new ConstraintViolation(rule.description, violationCount));
        }
        return results;
    }

    // ==================== 准确性评估（4 个独立指标） ====================

    /**
     * 结构完整性 — 统计悬空边、重复节点、约束违反总数。
     */
    private StructuralIntegrityMetric assessStructuralIntegrity(String projectId, String versionId,
                                                                List<ConstraintViolation> violations) {
        long danglingEdges = graphDao.countDanglingEdges(projectId, versionId);
        long duplicateNodes = graphDao.countDuplicateNodes(projectId, versionId);
        long constraintViolations = violations.stream().mapToLong(v -> v.violationCount).sum();
        return new StructuralIntegrityMetric(danglingEdges, duplicateNodes, constraintViolations);
    }

    /**
     * 抽样边证据支撑率 — 统计有 EdgeEvidence 关联（证据支撑）的边比例；不代表事实准确率。
     */
    private TripleAccuracyMetric assessTripleAccuracy(String projectId, String versionId) {
        List<Map<String, Object>> sampleEdges = graphDao.sampleEdgesWithEvidence(projectId, versionId, 100);
        int sampleSize = sampleEdges.size();
        if (sampleSize == 0) {
            return new TripleAccuracyMetric(0, 0, 0.0);
        }
        // 批量查询抽样边对应的 EdgeEvidence 记录
        List<String> edgeIds = sampleEdges.stream()
                .map(e -> e.get("edgeId"))
                .filter(v -> v != null && !String.valueOf(v).isEmpty() && !"null".equals(String.valueOf(v)))
                .map(String::valueOf)
                .distinct()
                .toList();
        Set<String> edgesWithEvidence = new HashSet<>();
        if (!edgeIds.isEmpty()) {
            List<EdgeEvidence> evidences = edgeEvidenceRepository.selectList(
                    new LambdaQueryWrapper<EdgeEvidence>().in(EdgeEvidence::getEdgeId, edgeIds));
            edgesWithEvidence = evidences.stream()
                    .map(EdgeEvidence::getEdgeId)
                    .collect(Collectors.toSet());
        }
        int supportedCount = 0;
        for (Map<String, Object> edge : sampleEdges) {
            Object edgeId = edge.get("edgeId");
            if (edgeId != null && edgesWithEvidence.contains(String.valueOf(edgeId))) {
                supportedCount++;
            }
        }
        double accuracyRate = (double) supportedCount / sampleSize;
        return new TripleAccuracyMetric(sampleSize, supportedCount, accuracyRate);
    }

    /**
     * 证据覆盖率 — 统计有证据关联的节点/边占总数的比例。
     */
    private EvidenceCoverageMetric assessEvidenceCoverage(String projectId, String versionId,
                                                          long totalNodes, long totalEdges) {
        long nodesWithEvidence = nodeEvidenceRepository.countDistinctNodeIds(projectId, versionId);
        long edgesWithEvidence = edgeEvidenceRepository.countDistinctEdgeIds(projectId, versionId);
        double nodeCoverageRate = totalNodes > 0 ? (double) nodesWithEvidence / totalNodes : 0.0;
        double edgeCoverageRate = totalEdges > 0 ? (double) edgesWithEvidence / totalEdges : 0.0;
        return new EvidenceCoverageMetric(totalNodes, nodesWithEvidence, nodeCoverageRate,
                totalEdges, edgesWithEvidence, edgeCoverageRate);
    }

    /**
     * 置信度校准 — 按来源类型分桶统计 Claim 的确认/待确认/驳回分布。
     */
    private CalibrationMetric assessCalibration(String projectId, String versionId) {
        Map<String, BucketStat> buckets = new LinkedHashMap<>();
        for (String sourceType : CALIBRATION_SOURCE_TYPES) {
            long confirmed = knowledgeClaimService.countByStatus(projectId, versionId, sourceType, "CONFIRMED");
            long pending = knowledgeClaimService.countByStatus(projectId, versionId, sourceType, "PENDING_CONFIRM");
            long rejected = knowledgeClaimService.countByStatus(projectId, versionId, sourceType, "REJECTED");
            buckets.put(sourceType, new BucketStat(confirmed, pending, rejected));
        }
        return new CalibrationMetric(buckets);
    }

    // ==================== 报告生成 ====================

    private String buildReport(String projectId, String versionId,
                                long totalNodes, long totalEdges, double edgeNodeRatio,
                                List<Map<String, Object>> nodeDist, List<Map<String, Object>> edgeDist,
                                long isolatedCount, double isolatedRate,
                                double avgDegree, String connectivityGrade,
                                List<ConstraintViolation> violations,
                                StructuralIntegrityMetric structuralMetric,
                                TripleAccuracyMetric tripleMetric,
                                EvidenceCoverageMetric coverageMetric,
                                CalibrationMetric calibrationMetric) {
        StringBuilder sb = new StringBuilder(2048);
        String now = LocalDateTime.now().format(TIME_FMT);

        // ---- 概览 ----
        sb.append("# 图谱质量评估报告\n\n");
        sb.append("## 1. 概览\n\n");
        sb.append("- 项目ID: ").append(projectId).append("\n");
        sb.append("- 扫描版本: ").append(versionId).append("\n");
        sb.append("- 评估时间: ").append(now).append("\n");
        sb.append("- 总节点数: ").append(totalNodes).append("\n");
        sb.append("- 总边数: ").append(totalEdges).append("\n");
        sb.append("- 边/节点比: ").append(String.format("%.2f", edgeNodeRatio)).append("\n\n");

        // ---- 完整性 ----
        sb.append("## 2. 完整性\n\n");

        // 节点类型分布
        sb.append("### 节点类型分布\n\n");
        sb.append("| 节点类型 | 数量 | 状态 |\n");
        sb.append("|----------|------|------|\n");
        Map<String, Long> nodeTypeMap = new LinkedHashMap<>();
        for (Map<String, Object> row : nodeDist) {
            String type = String.valueOf(row.get("nodeType"));
            long count = toLong(row.get("cnt"));
            nodeTypeMap.put(type, count);
            String status = KEY_NODE_TYPES.contains(type) && count == 0 ? "⚠️ 缺失" : "✓";
            sb.append("| ").append(type).append(" | ").append(count).append(" | ").append(status).append(" |\n");
        }
        // 关键类型缺失补充
        for (String keyType : KEY_NODE_TYPES) {
            if (!nodeTypeMap.containsKey(keyType)) {
                sb.append("| ").append(keyType).append(" | 0 | ⚠️ 缺失 |\n");
            }
        }
        sb.append("\n");

        // 边类型分布
        sb.append("### 边类型分布\n\n");
        sb.append("| 边类型 | 数量 | 状态 |\n");
        sb.append("|--------|------|------|\n");
        for (Map<String, Object> row : edgeDist) {
            String type = String.valueOf(row.get("edgeType"));
            long count = toLong(row.get("cnt"));
            sb.append("| ").append(type).append(" | ").append(count).append(" | ✓ |\n");
        }
        if (edgeDist.isEmpty()) {
            sb.append("| (无边) | 0 | ⚠️ 缺失 |\n");
        }
        sb.append("\n");

        // ---- 连通性 ----
        sb.append("## 3. 连通性\n\n");
        sb.append("- 孤立节点数: ").append(isolatedCount)
          .append(" (").append(String.format("%.1f%%", isolatedRate * 100)).append(")\n");
        sb.append("- 平均连通度: ").append(String.format("%.2f", avgDegree)).append("\n");
        sb.append("- 连通性评级: ").append(connectivityGrade).append("\n\n");

        // ---- 一致性 ----
        sb.append("## 4. 一致性\n\n");
        sb.append("### 本体约束校验\n\n");
        sb.append("| 约束规则 | 违反数 | 状态 |\n");
        sb.append("|----------|--------|------|\n");
        for (ConstraintViolation v : violations) {
            String status = v.violationCount == 0 ? "✓ 通过" : "⚠️ " + v.violationCount + " 个违反";
            sb.append("| ").append(v.description).append(" | ").append(v.violationCount).append(" | ").append(status).append(" |\n");
        }
        sb.append("\n");

        // ---- 5. 结构完整性 ----
        sb.append("## 5. 结构完整性\n\n");
        String danglingStatus = structuralMetric.danglingEdges() > 0
                ? "⚠️ " + structuralMetric.danglingEdges() + " 条悬空边" : "✓";
        sb.append("- 悬空边数: ").append(structuralMetric.danglingEdges())
          .append(" ").append(danglingStatus).append("\n");
        sb.append("- 重复节点数: ").append(structuralMetric.duplicateNodes()).append("\n");
        sb.append("- 约束违反总数: ").append(structuralMetric.constraintViolations()).append("\n\n");

        // ---- 6. 抽样边证据支撑率 ----
        sb.append("## 6. 抽样边证据支撑率\n\n");
        if (tripleMetric.sampleSize() == 0) {
            sb.append("- 抽样数: 0（无可用边，N/A）\n");
            sb.append("- 有证据支撑边数: 0\n");
            sb.append("- 证据支撑率: N/A\n\n");
        } else {
            sb.append("- 抽样数: ").append(tripleMetric.sampleSize()).append("\n");
            sb.append("- 有证据支撑边数: ").append(tripleMetric.supportedCount()).append("\n");
            sb.append("- 证据支撑率: ").append(String.format("%.1f%%", tripleMetric.accuracyRate() * 100)).append("\n\n");
        }

        // ---- 7. 证据覆盖率 ----
        sb.append("## 7. 证据覆盖率\n\n");
        if (totalNodes == 0) {
            sb.append("- 节点覆盖率: N/A（无节点）\n");
        } else {
            sb.append("- 节点覆盖率: ").append(coverageMetric.nodesWithEvidence())
              .append("/").append(coverageMetric.totalNodes())
              .append(" (").append(String.format("%.1f%%", coverageMetric.nodeCoverageRate() * 100)).append(")\n");
        }
        if (totalEdges == 0) {
            sb.append("- 边覆盖率: N/A（无边）\n");
        } else {
            sb.append("- 边覆盖率: ").append(coverageMetric.edgesWithEvidence())
              .append("/").append(coverageMetric.totalEdges())
              .append(" (").append(String.format("%.1f%%", coverageMetric.edgeCoverageRate() * 100)).append(")\n");
        }
        sb.append("\n");

        // ---- 8. 来源状态分布 ----
        sb.append("## 8. 来源状态分布\n\n");
        sb.append("| 来源类型 | 已确认 | 待确认 | 已驳回 |\n");
        sb.append("|----------|--------|--------|--------|\n");
        for (Map.Entry<String, BucketStat> entry : calibrationMetric.buckets().entrySet()) {
            BucketStat stat = entry.getValue();
            sb.append("| ").append(entry.getKey())
              .append(" | ").append(stat.confirmed())
              .append(" | ").append(stat.pending())
              .append(" | ").append(stat.rejected()).append(" |\n");
        }
        sb.append("\n");

        // ---- 改进建议 ----
        sb.append("## 9. 改进建议\n\n");
        List<String> suggestions = generateSuggestions(totalNodes, totalEdges, edgeNodeRatio,
                nodeTypeMap, isolatedRate, avgDegree, violations,
                structuralMetric, tripleMetric, coverageMetric);
        if (suggestions.isEmpty()) {
            sb.append("- 图谱质量良好，暂无紧急改进项\n");
        } else {
            for (String s : suggestions) {
                sb.append("- ").append(s).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 根据评估结果自动生成改进建议。
     */
    private List<String> generateSuggestions(long totalNodes, long totalEdges, double edgeNodeRatio,
                                              Map<String, Long> nodeTypeMap,
                                              double isolatedRate, double avgDegree,
                                              List<ConstraintViolation> violations,
                                              StructuralIntegrityMetric structuralMetric,
                                              TripleAccuracyMetric tripleMetric,
                                              EvidenceCoverageMetric coverageMetric) {
        List<String> suggestions = new ArrayList<>();

        // 边/节点比偏低
        if (totalNodes > 0 && edgeNodeRatio < 1.0) {
            suggestions.add("边/节点比仅为 " + String.format("%.2f", edgeNodeRatio)
                    + "，低于健康阈值 1.0，建议补全节点间的调用/包含关系");
        }

        // 关键节点类型缺失
        for (String keyType : KEY_NODE_TYPES) {
            long count = nodeTypeMap.getOrDefault(keyType, 0L);
            if (count == 0) {
                suggestions.add("关键节点类型 " + keyType + " 数量为 0，检查对应扫描器是否执行");
            }
        }

        // 孤立节点过多
        if (isolatedRate > 0.15) {
            suggestions.add("孤立节点比例 " + String.format("%.1f%%", isolatedRate * 100)
                    + " 超过 15%，建议检查未连通节点的来源并补全关系边");
        }

        // 平均连通度偏低
        if (avgDegree < 1.0 && totalNodes > 0) {
            suggestions.add("平均连通度 " + String.format("%.2f", avgDegree)
                    + " 偏低，图谱关系密度不足");
        }

        // 约束违反
        for (ConstraintViolation v : violations) {
            if (v.violationCount > 0) {
                suggestions.add("约束「" + v.description + "」存在 " + v.violationCount + " 个违反，需补全对应边");
            }
        }

        // 悬空边
        if (structuralMetric.danglingEdges() > 0) {
            suggestions.add("存在 " + structuralMetric.danglingEdges()
                    + " 条悬空边（端点节点缺失），建议清理或补全端点节点");
        }

        // 重复节点
        if (structuralMetric.duplicateNodes() > 0) {
            suggestions.add("存在 " + structuralMetric.duplicateNodes()
                    + " 个重复节点（同 nodeType+nodeKey），建议执行节点合并去重");
        }

        // 抽样边证据支撑率偏低（有抽样数据时）
        if (tripleMetric.sampleSize() > 0 && tripleMetric.accuracyRate() < 0.9) {
            suggestions.add("抽样边证据支撑率仅为 " + String.format("%.1f%%", tripleMetric.accuracyRate() * 100)
                    + "，低于 90%，建议为缺少证据支撑的边补充 EdgeEvidence");
        }

        // 证据覆盖率偏低
        if (totalNodes > 0 && coverageMetric.nodeCoverageRate() < 0.5) {
            suggestions.add("节点证据覆盖率仅为 " + String.format("%.1f%%", coverageMetric.nodeCoverageRate() * 100)
                    + "，低于 50%，建议补充节点证据关联");
        }
        if (totalEdges > 0 && coverageMetric.edgeCoverageRate() < 0.3) {
            suggestions.add("边证据覆盖率仅为 " + String.format("%.1f%%", coverageMetric.edgeCoverageRate() * 100)
                    + "，低于 30%，建议补充边证据关联");
        }

        return suggestions;
    }

    // ==================== 辅助方法 ====================

    /**
     * 连通性评级：综合孤立节点比例和平均连通度。
     */
    private String gradeConnectivity(double isolatedRate, double avgDegree) {
        if (isolatedRate <= 0.05 && avgDegree >= 2.0) {
            return "良好";
        } else if (isolatedRate <= 0.15 && avgDegree >= 1.0) {
            return "一般";
        } else {
            return "差";
        }
    }

    /** 安全提取 Long 值 */
    private long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
        }
        return 0L;
    }

    // ==================== 项目根目录解析（与 ScanArtifactPublisher 逻辑一致） ====================

    Path resolveDocsDir(String projectId) {
        List<CodeRepo> repos = codeRepoRepository.selectList(new LambdaQueryWrapper<CodeRepo>()
                .eq(CodeRepo::getProjectId, projectId));

        if (repos.isEmpty()) {
            return fallbackDocsDir(projectId);
        }

        List<Path> localPaths = repos.stream()
                .map(CodeRepo::getLocalPath)
                .filter(p -> p != null && !p.isBlank())
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .toList();

        if (localPaths.isEmpty()) {
            return fallbackDocsDir(projectId);
        }

        Path commonParent = longestCommonParent(localPaths);
        if (commonParent == null) {
            return fallbackDocsDir(projectId);
        }

        String home = System.getProperty("user.home");
        String commonPath = commonParent.toString();
        if (commonPath.equals("/") || commonPath.equals(home)
                || commonPath.equals(Path.of(home).getRoot().toString())) {
            return fallbackDocsDir(projectId);
        }

        return commonParent.resolve(DOCS_SUBDIR);
    }

    private Path longestCommonParent(List<Path> paths) {
        if (paths.isEmpty()) return null;
        Path common = paths.get(0);
        for (int i = 1; i < paths.size(); i++) {
            common = commonParent(common, paths.get(i));
            if (common == null) return null;
        }
        return common;
    }

    private Path commonParent(Path a, Path b) {
        int minLen = Math.min(a.getNameCount(), b.getNameCount());
        Path common = a.getRoot();
        for (int i = 0; i < minLen; i++) {
            if (a.getName(i).equals(b.getName(i))) {
                common = common.resolve(a.getName(i));
            } else {
                break;
            }
        }
        if (common == null || common.getNameCount() < 2) {
            return null;
        }
        return common;
    }

    private Path fallbackDocsDir(String projectId) {
        return Path.of(fallbackReportRoot).resolve(projectId).resolve(DOCS_SUBDIR);
    }

    // ==================== 内部数据结构 ====================

    /** 本体约束规则定义 */
    private record ConstraintRule(String nodeLabel, String description, List<String> edgeTypes) {}

    /** 约束违反结果 */
    private record ConstraintViolation(String description, long violationCount) {}

    /** 结构完整性指标 */
    private record StructuralIntegrityMetric(long danglingEdges, long duplicateNodes, long constraintViolations) {}

    /** 三元组准确率指标 */
    private record TripleAccuracyMetric(int sampleSize, int supportedCount, double accuracyRate) {}

    /** 证据覆盖率指标 */
    private record EvidenceCoverageMetric(long totalNodes, long nodesWithEvidence, double nodeCoverageRate,
                                           long totalEdges, long edgesWithEvidence, double edgeCoverageRate) {}

    /** 置信度校准分桶统计 */
    private record BucketStat(long confirmed, long pending, long rejected) {}

    /** 置信度校准指标 */
    private record CalibrationMetric(Map<String, BucketStat> buckets) {}
}
