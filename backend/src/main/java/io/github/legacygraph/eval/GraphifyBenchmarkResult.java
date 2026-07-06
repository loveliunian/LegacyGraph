package io.github.legacygraph.eval;

import java.util.Set;

/**
 * Graphify benchmark 评测结果。
 * 
 * @param caseName              用例名称
 * @param nodeRecall            节点召回率（0.0-1.0）
 * @param edgeRecall            边召回率（0.0-1.0）
 * @param forbiddenEdgeRate     禁止边出现率（0.0-1.0）
 * @param missingExpectedNodes  缺失的期望节点
 * @param missingExpectedEdges  缺失的期望边
 * @param forbiddenEdgesFound   发现的禁止边
 */
public record GraphifyBenchmarkResult(
    String caseName,
    double nodeRecall,
    double edgeRecall,
    double forbiddenEdgeRate,
    Set<String> missingExpectedNodes,
    Set<String> missingExpectedEdges,
    Set<String> forbiddenEdgesFound
) {
    private static final double NODE_RECALL_THRESHOLD = 0.9;
    private static final double EDGE_RECALL_THRESHOLD = 0.85;
    private static final double FORBIDDEN_EDGE_RATE_THRESHOLD = 0.02;

    public GraphifyBenchmarkResult {
        if (missingExpectedNodes == null) missingExpectedNodes = Set.of();
        if (missingExpectedEdges == null) missingExpectedEdges = Set.of();
        if (forbiddenEdgesFound == null) forbiddenEdgesFound = Set.of();
    }

    /**
     * 判断是否通过发布门槛。
     * 
     * 门槛规则：
     * - nodeRecall >= 0.9
     * - edgeRecall >= 0.85
     * - forbiddenEdgeRate <= 0.02
     */
    public boolean passesReleaseGate() {
        return nodeRecall >= NODE_RECALL_THRESHOLD
            && edgeRecall >= EDGE_RECALL_THRESHOLD
            && forbiddenEdgeRate <= FORBIDDEN_EDGE_RATE_THRESHOLD;
    }

    /**
     * 获取发布门槛详情。
     */
    public String getReleaseGateDetails() {
        if (passesReleaseGate()) {
            return "通过发布门槛";
        }
        StringBuilder sb = new StringBuilder("未通过发布门槛：");
        if (nodeRecall < NODE_RECALL_THRESHOLD) {
            sb.append(String.format(" 节点召回率 %.2f < %.2f;", nodeRecall, NODE_RECALL_THRESHOLD));
        }
        if (edgeRecall < EDGE_RECALL_THRESHOLD) {
            sb.append(String.format(" 边召回率 %.2f < %.2f;", edgeRecall, EDGE_RECALL_THRESHOLD));
        }
        if (forbiddenEdgeRate > FORBIDDEN_EDGE_RATE_THRESHOLD) {
            sb.append(String.format(" 禁止边出现率 %.2f > %.2f;", forbiddenEdgeRate, FORBIDDEN_EDGE_RATE_THRESHOLD));
        }
        return sb.toString();
    }
}
