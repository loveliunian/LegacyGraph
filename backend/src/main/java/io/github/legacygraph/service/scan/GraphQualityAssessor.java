package io.github.legacygraph.service.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.repository.CodeRepoRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱质量评估服务 — 扫描完成后自动评估图谱完整性、连通性、一致性、准确性，并生成 Markdown 报告。
 *
 * <p>评估维度：
 * <ul>
 *   <li><b>完整性</b> — 节点/边类型分布、关键类型覆盖率、边/节点比</li>
 *   <li><b>连通性</b> — 孤立节点比例、平均连通度</li>
 *   <li><b>一致性</b> — 本体约束校验（Method/Column/ApiEndpoint/SqlStatement 的必需边）</li>
 *   <li><b>准确性</b> — 抽样校验边的端点节点是否存在、类型是否兼容</li>
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

    private static final String REPORT_FILENAME = "graph-quality-report.md";
    private static final String DOCS_SUBDIR = "docs/legacygraph";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Neo4jGraphDao graphDao;
    private final CodeRepoRepository codeRepoRepository;

    /** 当项目根目录无法解析时的回退目录 */
    @Value("${legacy-graph.reports.local-dir:${user.home}/.legacygraph/reports}")
    private String fallbackReportRoot;

    @Autowired
    public GraphQualityAssessor(Neo4jGraphDao graphDao, CodeRepoRepository codeRepoRepository) {
        this.graphDao = graphDao;
        this.codeRepoRepository = codeRepoRepository;
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

        // 3. 一致性评估
        List<ConstraintViolation> violations = assessConstraints(projectId, versionId);

        // 4. 准确性评估
        AccuracyMetric accuracyMetric = assessAccuracy(projectId, versionId);

        // 5. 生成报告
        String markdown = buildReport(projectId, versionId, totalNodes, totalEdges, edgeNodeRatio,
                nodeDist, edgeDist, isolatedCount, isolatedRate, avgDegree, connectivityGrade,
                violations, accuracyMetric);

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

    // ==================== 准确性评估 ====================

    /**
     * 从图谱中抽样边，校验边的端点节点是否存在（fromNodeId/toNodeId 非空），统计有效边比例。
     *
     * @param projectId 项目 ID
     * @param versionId 扫描版本 ID
     * @return 准确性指标（accuracyRate 0~1）
     */
    private AccuracyMetric assessAccuracy(String projectId, String versionId) {
        List<Map<String, Object>> sampleEdges = graphDao.sampleEdgesForAccuracy(projectId, versionId, 100);
        int sampleSize = sampleEdges.size();
        int validCount = 0;
        for (Map<String, Object> edge : sampleEdges) {
            Object fromNodeId = edge.get("fromNodeId");
            Object toNodeId = edge.get("toNodeId");
            if (fromNodeId != null && !String.valueOf(fromNodeId).isEmpty()
                    && toNodeId != null && !String.valueOf(toNodeId).isEmpty()) {
                validCount++;
            }
        }
        double accuracyRate = sampleSize > 0 ? (double) validCount / sampleSize : 1.0;
        return new AccuracyMetric(sampleSize, validCount, accuracyRate);
    }

    // ==================== 报告生成 ====================

    private String buildReport(String projectId, String versionId,
                                long totalNodes, long totalEdges, double edgeNodeRatio,
                                List<Map<String, Object>> nodeDist, List<Map<String, Object>> edgeDist,
                                long isolatedCount, double isolatedRate,
                                double avgDegree, String connectivityGrade,
                                List<ConstraintViolation> violations, AccuracyMetric accuracyMetric) {
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

        // ---- 准确性 ----
        sb.append("## 5. 准确性\n\n");
        sb.append("- 抽样数: ").append(accuracyMetric.sampleSize()).append("\n");
        sb.append("- 有效边数: ").append(accuracyMetric.validCount()).append("\n");
        sb.append("- 准确率: ").append(String.format("%.1f%%", accuracyMetric.accuracyRate() * 100)).append("\n\n");

        // ---- 改进建议 ----
        sb.append("## 6. 改进建议\n\n");
        List<String> suggestions = generateSuggestions(totalNodes, totalEdges, edgeNodeRatio,
                nodeTypeMap, isolatedRate, avgDegree, violations, accuracyMetric.accuracyRate());
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
                                              List<ConstraintViolation> violations, double accuracyRate) {
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

        // 准确率偏低
        if (accuracyRate < 0.9) {
            suggestions.add("准确率仅为 " + String.format("%.1f%%", accuracyRate * 100)
                    + "，低于 90%，建议人工审核抽样中端点缺失的边");
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

    /** 准确性评估结果 */
    private record AccuracyMetric(int sampleSize, int validCount, double accuracyRate) {}
}
