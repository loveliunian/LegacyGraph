package io.github.legacygraph.eval;

import java.util.List;

/**
 * Graphify 质量评估结果，对应前端 {@code GraphifyQualityResult}。
 *
 * @param overallScore       总体评分（0-100）
 * @param nodeCoverage       节点覆盖率/召回率（0.0-1.0）
 * @param edgeCoverage       边覆盖率/召回率（0.0-1.0）
 * @param benchmarkResults   benchmark 用例结果
 * @param releaseGatePassed  是否通过发布门槛
 * @param releaseGateReason  未通过原因（通过时为 null）
 */
public record GraphifyQualityResult(
        double overallScore,
        double nodeCoverage,
        double edgeCoverage,
        List<BenchmarkItem> benchmarkResults,
        boolean releaseGatePassed,
        String releaseGateReason
) {
    public GraphifyQualityResult {
        if (benchmarkResults == null) benchmarkResults = List.of();
    }

    /**
     * 单个 benchmark 用例结果。
     *
     * @param name      用例名称
     * @param passed    是否通过发布门槛
     * @param score     得分（节点召回率，0.0-1.0）
     * @param threshold 阈值（节点召回率门槛 0.9）
     * @param details   详情说明
     */
    public record BenchmarkItem(
            String name,
            boolean passed,
            double score,
            double threshold,
            String details
    ) {
    }
}
