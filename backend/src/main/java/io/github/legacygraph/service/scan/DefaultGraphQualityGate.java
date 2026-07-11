package io.github.legacygraph.service.scan;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.scan.Decision;
import io.github.legacygraph.dto.scan.GraphQualitySnapshot;
import io.github.legacygraph.repository.NodeEvidenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 默认图谱质量门禁实现 — 采集图谱快照并按门禁规则评估是否放行。
 * <p>
 * 数据采集复用 {@link Neo4jGraphDao} 已有的统计方法：
 * <ul>
 *   <li>{@link Neo4jGraphDao#versionGraphStats} — 节点/边总数</li>
 *   <li>{@link Neo4jGraphDao#countIsolatedNodes} — 孤立节点数</li>
 *   <li>{@link Neo4jGraphDao#countNodesWithoutEdgeTypes} — 约束违反计数</li>
 *   <li>{@link NodeEvidenceRepository#countDistinctNodeIds} — 有证据的节点数</li>
 * </ul>
 * <p>
 * 门禁规则与 {@link GraphQualityGate} 定义一致，任一违反即拦截。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultGraphQualityGate implements GraphQualityGate {

    private final Neo4jGraphDao graphDao;
    private final NodeEvidenceRepository nodeEvidenceRepository;

    /** 边/节点比门禁阈值：低于此值拦截 */
    static final double THRESHOLD_EDGE_NODE_RATIO = 1.0;
    /** 孤立节点率门禁阈值：超过此值拦截（10%） */
    static final double THRESHOLD_ISOLATED_RATE = 0.10;
    /** 证据覆盖率门禁阈值：低于此值拦截（95%） */
    static final double THRESHOLD_EVIDENCE_RATE = 0.95;

    /**
     * 本体约束规则定义：节点标签 → 必需的边类型列表（满足任意一个即可）。
     * <p>与 {@link GraphQualityAssessor} 的约束规则保持一致。</p>
     */
    private static final List<ConstraintRule> CONSTRAINT_RULES = List.of(
            new ConstraintRule("Method", List.of("BELONGS_TO", "CONTAINS")),
            new ConstraintRule("Column", List.of("HAS_COLUMN")),
            new ConstraintRule("ApiEndpoint", List.of("HANDLED_BY")),
            new ConstraintRule("SqlStatement", List.of("READS", "WRITES"))
    );

    @Override
    public Decision evaluate(String projectId, String scanVersionId) {
        GraphQualitySnapshot snapshot = collectSnapshot(projectId, scanVersionId);
        log.info("GraphQualityGate snapshot: project={}, version={}, {}", projectId, scanVersionId, snapshot);

        List<String> violations = evaluateRules(snapshot);
        boolean passed = violations.isEmpty();
        return new Decision(passed, violations);
    }

    /**
     * 采集图谱质量快照。
     *
     * @param projectId     项目 ID
     * @param scanVersionId 扫描版本 ID
     * @return 不可变的质量快照
     */
    GraphQualitySnapshot collectSnapshot(String projectId, String scanVersionId) {
        // 1. 节点/边总数
        Map<String, Object> stats = graphDao.versionGraphStats(projectId, scanVersionId);
        long totalNodes = toLong(stats.get("totalNodes"));
        long totalEdges = toLong(stats.get("totalEdges"));
        double edgeNodeRatio = totalNodes > 0 ? (double) totalEdges / totalNodes : 0.0;

        // 2. 孤立节点数
        long isolatedNodeCount = graphDao.countIsolatedNodes(projectId, scanVersionId);
        double isolatedRate = totalNodes > 0 ? (double) isolatedNodeCount / totalNodes : 0.0;

        // 3. 约束违反数
        long constraintViolations = countConstraintViolations(projectId, scanVersionId);

        // 4. 证据覆盖率
        long nodesWithEvidence = nodeEvidenceRepository.countDistinctNodeIds(projectId, scanVersionId);
        double evidenceCoverageRate = totalNodes > 0 ? (double) nodesWithEvidence / totalNodes : 0.0;

        return new GraphQualitySnapshot(
                totalNodes, totalEdges, edgeNodeRatio,
                isolatedNodeCount, isolatedRate,
                constraintViolations,
                nodesWithEvidence, evidenceCoverageRate);
    }

    /**
     * 逐条检查本体约束规则，汇总约束违反总数。
     */
    private long countConstraintViolations(String projectId, String scanVersionId) {
        long total = 0;
        for (ConstraintRule rule : CONSTRAINT_RULES) {
            total += graphDao.countNodesWithoutEdgeTypes(
                    projectId, scanVersionId, rule.nodeLabel(), rule.edgeTypes());
        }
        return total;
    }

    /**
     * 按门禁规则评估快照，返回违反的规则名列表。
     *
     * @param snapshot 图谱质量快照
     * @return 违反的规则名列表；全部通过时为空列表
     */
    private List<String> evaluateRules(GraphQualitySnapshot snapshot) {
        List<String> violations = new ArrayList<>(4);

        if (snapshot.edgeNodeRatio() < THRESHOLD_EDGE_NODE_RATIO) {
            violations.add(RULE_EDGE_NODE_RATIO_BELOW_1);
        }
        if (snapshot.isolatedRate() > THRESHOLD_ISOLATED_RATE) {
            violations.add(RULE_ISOLATED_RATE_ABOVE_10_PERCENT);
        }
        if (snapshot.constraintViolations() > 0) {
            violations.add(RULE_CONSTRAINT_VIOLATIONS);
        }
        if (snapshot.evidenceCoverageRate() < THRESHOLD_EVIDENCE_RATE) {
            violations.add(RULE_EVIDENCE_RATE_BELOW_95_PERCENT);
        }

        return violations;
    }

    /** 安全提取 Long 值 */
    private long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    /** 本体约束规则定义 */
    private record ConstraintRule(String nodeLabel, List<String> edgeTypes) {}
}
