package io.github.legacygraph.service.qa;

import io.github.legacygraph.agent.QueryIntent;
import io.github.legacygraph.dto.qa.ConfidenceBreakdown;
import io.github.legacygraph.dto.qa.VerificationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 置信度评分器 — 基于多维度动态计算答案置信度。
 *
 * <p>维度与默认权重：</p>
 * <ul>
 *   <li>证据覆盖率 coverage: 0.30</li>
 *   <li>证据可靠度 reliability: 0.25</li>
 *   <li>检索一致性 consistency: 0.20</li>
 *   <li>路径置信度 pathConfidence: 0.15</li>
 *   <li>时效性 timeliness: 0.10</li>
 * </ul>
 *
 * <p>高风险意图（{@link QueryIntent#CHANGE_IMPACT}）调整权重：
 * pathConfidence 提升至 0.35，coverage 降至 0.20，timeliness 降为 0.00。</p>
 *
 * <p>权重总和始终为 1.0。</p>
 */
@Slf4j
@Service
public class ConfidenceScorer {

    /** 高风险意图置信度阈值：低于此值拒答 */
    public static final double HIGH_RISK_REJECT_THRESHOLD = 0.5;

    /**
     * 计算置信度分解。
     *
     * @param verificationResult 证据验证结果（提供 coverage 和 reliability）
     * @param intent             查询意图（决定权重配置）
     * @param retrievalConsistency 检索一致性分数（0.0~1.0，多路检索结果重合度）
     * @param pathConfidence     路径置信度分数（0.0~1.0，图谱路径完整度）
     * @param evidenceTimestamp  证据时间戳（用于计算时效性，可为 null）
     * @return 置信度分解
     */
    public ConfidenceBreakdown score(VerificationResult verificationResult,
                                     QueryIntent intent,
                                     double retrievalConsistency,
                                     double pathConfidence,
                                     Instant evidenceTimestamp) {
        ConfidenceBreakdown.Weights weights = resolveWeights(intent);

        double coverage = clamp(verificationResult.evidenceCoverage());
        double reliability = clamp(verificationResult.evidenceReliability());
        double consistency = clamp(retrievalConsistency);
        double pathConf = clamp(pathConfidence);
        double timeliness = computeTimeliness(evidenceTimestamp);

        double finalScore = coverage * weights.coverage()
            + reliability * weights.reliability()
            + consistency * weights.consistency()
            + pathConf * weights.pathConfidence()
            + timeliness * weights.timeliness();

        finalScore = clamp(finalScore);

        log.debug("Confidence scored: intent={}, final={:.2f}, coverage={:.2f}, reliability={:.2f}, consistency={:.2f}, path={:.2f}, timeliness={:.2f}",
            intent, finalScore, coverage, reliability, consistency, pathConf, timeliness);

        return new ConfidenceBreakdown(finalScore, coverage, reliability, consistency, pathConf,
            timeliness, weights);
    }

    /**
     * 计算检索一致性 — 多路检索结果的重合度。
     *
     * @param rankings 多路检索结果列表
     * @return 一致性分数（0.0~1.0）：跨路出现同一文档的比例
     */
    public double computeRetrievalConsistency(List<List<String>> rankings) {
        if (rankings == null || rankings.size() <= 1) {
            return 1.0;
        }
        // 统计每个文档出现在几路中
        java.util.Map<String, Integer> docCounts = new java.util.HashMap<>();
        for (List<String> ranking : rankings) {
            if (ranking == null) continue;
            for (String docId : ranking) {
                if (docId != null) {
                    docCounts.merge(docId, 1, Integer::sum);
                }
            }
        }
        if (docCounts.isEmpty()) {
            return 1.0;
        }
        // 跨路出现的文档数 / 总文档数
        long crossRank = docCounts.values().stream().filter(c -> c > 1).count();
        return (double) crossRank / docCounts.size();
    }

    /**
     * 根据意图选择权重配置。
     */
    private ConfidenceBreakdown.Weights resolveWeights(QueryIntent intent) {
        if (intent == QueryIntent.CHANGE_IMPACT) {
            return ConfidenceBreakdown.Weights.HIGH_RISK;
        }
        return ConfidenceBreakdown.Weights.DEFAULT;
    }

    /**
     * 计算时效性分数 — 证据越新分数越高。
     * <p>1 小时内 = 1.0；24 小时内线性衰减到 0.5；超过 24 小时固定 0.5。</p>
     */
    private double computeTimeliness(Instant evidenceTimestamp) {
        if (evidenceTimestamp == null) {
            return 0.5; // 无时间戳：中等
        }
        Duration age = Duration.between(evidenceTimestamp, Instant.now());
        if (age.isNegative()) {
            return 1.0; // 未来时间（时钟偏移）：满分
        }
        long hours = age.toHours();
        if (hours <= 1) {
            return 1.0;
        }
        if (hours <= 24) {
            // 1~24h 线性衰减 1.0 → 0.5
            return 1.0 - (hours - 1) * (0.5 / 23.0);
        }
        return 0.5;
    }

    /**
     * 限制分数在 [0.0, 1.0] 范围内。
     */
    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
