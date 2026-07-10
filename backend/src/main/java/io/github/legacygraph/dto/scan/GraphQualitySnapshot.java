package io.github.legacygraph.dto.scan;

/**
 * 图谱质量快照 — 门禁评估时的数据采集结果。
 * <p>
 * 由 {@link io.github.legacygraph.service.scan.DefaultGraphQualityGate} 在评估门禁规则前采集，
 * 作为门禁规则判断的数据基础。快照为不可变值对象，便于日志记录与审计。
 * </p>
 *
 * @param totalNodes           节点总数
 * @param totalEdges           边总数
 * @param edgeNodeRatio        边/节点比（totalNodes > 0 时为 totalEdges/totalNodes，否则 0.0）
 * @param isolatedNodeCount    孤立节点数（无任何边的节点）
 * @param isolatedRate         孤立节点率（totalNodes > 0 时为 isolatedNodeCount/totalNodes，否则 0.0）
 * @param constraintViolations 约束违反数（本体约束规则违反总数）
 * @param nodesWithEvidence    有证据关联的节点数
 * @param evidenceCoverageRate 证据覆盖率（totalNodes > 0 时为 nodesWithEvidence/totalNodes，否则 0.0）
 */
public record GraphQualitySnapshot(
        long totalNodes,
        long totalEdges,
        double edgeNodeRatio,
        long isolatedNodeCount,
        double isolatedRate,
        long constraintViolations,
        long nodesWithEvidence,
        double evidenceCoverageRate) {
}
