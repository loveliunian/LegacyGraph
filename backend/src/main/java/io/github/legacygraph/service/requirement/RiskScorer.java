package io.github.legacygraph.service.requirement;

import io.github.legacygraph.dto.requirement.RiskFactor;
import org.springframework.stereotype.Service;

/**
 * 风险评分服务（G-06）。
 * <p>根据关系置信度、路径深度、变更类型、测试覆盖、关键资产与运行时热度
 * 等因子，计算受影响节点的综合风险分数。</p>
 */
@Service
public class RiskScorer {

    /** 变更类型权重常量 */
    public static final double WEIGHT_SCHEMA_CHANGE = 1.5;
    public static final double WEIGHT_API_CONTRACT = 1.4;
    public static final double WEIGHT_INTERNAL_ONLY = 1.0;
    public static final double WEIGHT_READ_ONLY = 0.6;

    /** 缺少测试惩罚系数 */
    public static final double MISSING_TEST_PENALTY = 1.5;
    /** 有测试时的系数（无惩罚） */
    public static final double HAS_TEST_FACTOR = 1.0;

    /** 推断边置信度衰减 */
    public static final double INFERRED_EDGE_CONFIDENCE = 0.7;
    /** 显式边置信度 */
    public static final double EXPLICIT_EDGE_CONFIDENCE = 1.0;

    /** 默认关键资产权重 */
    public static final double DEFAULT_CRITICAL_ASSET_WEIGHT = 1.0;
    /** 默认运行时热度 */
    public static final double DEFAULT_RUNTIME_HOTNESS = 1.0;

    /**
     * 计算风险分数。
     *
     * @param factor 风险因子
     * @return 风险分数（各因子乘积）
     */
    public double score(RiskFactor factor) {
        if (factor == null) {
            return 0.0;
        }
        return factor.calculate();
    }

    /**
     * 构建风险因子。
     *
     * @param relationConfidence 关系置信度标识："EXPLICIT" 显式边取 1.0，"INFERRED" 推断边取 0.7，
     *                            其他数值字符串按 double 解析
     * @param depth               距离变更起点的跳数
     * @param changeType          变更类型：SchemaChange / ApiContract / InternalOnly / ReadOnly
     * @param hasTest             是否有测试覆盖
     * @param criticalAssetWeight 关键资产权重
     * @param runtimeHotness      运行时热度权重
     * @return 构建好的 RiskFactor
     */
    public RiskFactor buildFactor(String relationConfidence, int depth, String changeType,
                                  boolean hasTest, double criticalAssetWeight, double runtimeHotness) {
        return RiskFactor.builder()
                .relationConfidence(resolveConfidence(relationConfidence))
                .pathDecay(pathDecay(depth))
                .changeTypeWeight(resolveChangeTypeWeight(changeType))
                .missingTestPenalty(hasTest ? HAS_TEST_FACTOR : MISSING_TEST_PENALTY)
                .criticalAssetWeight(criticalAssetWeight)
                .runtimeHotness(runtimeHotness)
                .build();
    }

    /**
     * 路径衰减因子。
     * <p>公式：1 / log2(depth + 2)。depth=0 时衰减为 1/log2(2)=1.0，
     * 随 depth 增大单调递减。</p>
     *
     * @param depth 距离变更起点的跳数
     * @return 路径衰减因子
     */
    public double pathDecay(int depth) {
        return 1.0 / log2(depth + 2);
    }

    /**
     * 解析关系置信度。
     */
    private double resolveConfidence(String relationConfidence) {
        if (relationConfidence == null) {
            return EXPLICIT_EDGE_CONFIDENCE;
        }
        return switch (relationConfidence.toUpperCase()) {
            case "INFERRED" -> INFERRED_EDGE_CONFIDENCE;
            case "EXPLICIT" -> EXPLICIT_EDGE_CONFIDENCE;
            default -> {
                try {
                    yield Double.parseDouble(relationConfidence);
                } catch (NumberFormatException e) {
                    yield EXPLICIT_EDGE_CONFIDENCE;
                }
            }
        };
    }

    /**
     * 解析变更类型权重。
     */
    private double resolveChangeTypeWeight(String changeType) {
        if (changeType == null) {
            return WEIGHT_INTERNAL_ONLY;
        }
        return switch (changeType.toUpperCase()) {
            case "SCHEMA_CHANGE", "SCHEMACHANGE" -> WEIGHT_SCHEMA_CHANGE;
            case "API_CONTRACT", "APICONTRACT" -> WEIGHT_API_CONTRACT;
            case "READ_ONLY", "READONLY" -> WEIGHT_READ_ONLY;
            case "INTERNAL_ONLY", "INTERNALONLY" -> WEIGHT_INTERNAL_ONLY;
            default -> WEIGHT_INTERNAL_ONLY;
        };
    }

    /**
     * 计算 log2。
     */
    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }
}
