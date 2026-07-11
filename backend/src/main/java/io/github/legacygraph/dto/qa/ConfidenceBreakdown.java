package io.github.legacygraph.dto.qa;

/**
 * 置信度分解 — 由 {@link io.github.legacygraph.service.qa.ConfidenceScorer} 产出。
 *
 * <p>记录各维度分数和加权后的最终分数，用于：</p>
 * <ul>
 *   <li>前端展示置信度来源（哪个维度拉低/提升了分数）</li>
 *   <li>低置信度场景标记 {@code LOW_CONFIDENCE} 或拒答</li>
 * </ul>
 *
 * @param finalScore        最终置信度（0.0~1.0，各维度加权求和）
 * @param evidenceCoverage  证据覆盖率分数（0.0~1.0）
 * @param evidenceReliability 证据可靠度分数（0.0~1.0）
 * @param retrievalConsistency 检索一致性分数（0.0~1.0）
 * @param pathConfidence    路径置信度分数（0.0~1.0）
 * @param timeliness        时效性分数（0.0~1.0）
 * @param weights           各维度使用的权重（便于审计权重调整）
 */
public record ConfidenceBreakdown(
        double finalScore,
        double evidenceCoverage,
        double evidenceReliability,
        double retrievalConsistency,
        double pathConfidence,
        double timeliness,
        Weights weights
) {

    /**
     * 权重配置 — 记录最终分数计算时各维度的权重。
     */
    public record Weights(
            double coverage,
            double reliability,
            double consistency,
            double pathConfidence,
            double timeliness
    ) {
        /** 默认权重 */
        public static final Weights DEFAULT = new Weights(0.30, 0.25, 0.20, 0.15, 0.10);
        /** 高风险意图（CHANGE_IMPACT）权重 */
        public static final Weights HIGH_RISK = new Weights(0.20, 0.25, 0.20, 0.35, 0.00);

        /**
         * 权重总和是否为 1.0（容差 0.001）。
         */
        public boolean isValid() {
            double sum = coverage + reliability + consistency + pathConfidence + timeliness;
            return Math.abs(sum - 1.0) < 0.001;
        }
    }

    /**
     * 是否为低置信度（finalScore < 0.6）。
     */
    public boolean isLowConfidence() {
        return finalScore < 0.6;
    }
}
