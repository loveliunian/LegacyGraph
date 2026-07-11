package io.github.legacygraph.service.qa;

import io.github.legacygraph.agent.QueryIntent;
import io.github.legacygraph.dto.qa.ConfidenceBreakdown;
import io.github.legacygraph.dto.qa.VerificationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfidenceScorer 单元测试 — 验证多维度动态置信度计算和高风险权重调整。
 */
class ConfidenceScorerTest {

    private final ConfidenceScorer scorer = new ConfidenceScorer();

    private VerificationResult mockResult(double coverage, double reliability) {
        return new VerificationResult(true, coverage, reliability,
            List.of("matched"), List.of(), List.of(), List.of());
    }

    @Test
    void score_defaultWeights_sumToOne() {
        assertTrue(ConfidenceBreakdown.Weights.DEFAULT.isValid());
    }

    @Test
    void score_highRiskWeights_sumToOne() {
        assertTrue(ConfidenceBreakdown.Weights.HIGH_RISK.isValid());
    }

    @Test
    void score_defaultWeights_correctCalculation() {
        // coverage=1.0, reliability=1.0, consistency=1.0, path=1.0, timeliness=1.0
        // finalScore = 1.0*0.30 + 1.0*0.25 + 1.0*0.20 + 1.0*0.15 + 1.0*0.10 = 1.0
        VerificationResult vr = mockResult(1.0, 1.0);
        ConfidenceBreakdown breakdown = scorer.score(vr, QueryIntent.FACT_LOOKUP, 1.0, 1.0, Instant.now());

        assertEquals(1.0, breakdown.finalScore(), 0.001);
        assertEquals(ConfidenceBreakdown.Weights.DEFAULT, breakdown.weights());
    }

    @Test
    void score_highRiskIntent_usesHighRiskWeights() {
        VerificationResult vr = mockResult(1.0, 1.0);
        ConfidenceBreakdown breakdown = scorer.score(vr, QueryIntent.CHANGE_IMPACT, 1.0, 1.0, Instant.now());

        assertEquals(ConfidenceBreakdown.Weights.HIGH_RISK, breakdown.weights());
        // HIGH_RISK: coverage=0.20, reliability=0.25, consistency=0.20, path=0.35, timeliness=0.00
        // finalScore = 1.0*0.20 + 1.0*0.25 + 1.0*0.20 + 1.0*0.35 + 0.0*0.00 = 1.0
        // 但 timeliness 权重为 0，所以 finalScore = 0.20+0.25+0.20+0.35 = 1.0
        assertEquals(1.0, breakdown.finalScore(), 0.001);
    }

    @Test
    void score_lowCoverage_reducesFinalScore() {
        VerificationResult vr = mockResult(0.3, 1.0);
        ConfidenceBreakdown breakdown = scorer.score(vr, QueryIntent.FACT_LOOKUP, 1.0, 1.0, Instant.now());

        // coverage 贡献从 0.30 降到 0.30*0.3 = 0.09
        // finalScore = 0.3*0.30 + 1.0*0.25 + 1.0*0.20 + 1.0*0.15 + 1.0*0.10
        //            = 0.09 + 0.25 + 0.20 + 0.15 + 0.10 = 0.79
        assertEquals(0.79, breakdown.finalScore(), 0.01);
    }

    @Test
    void score_highRiskLowPathConfidence_reducesFinalScore() {
        // CHANGE_IMPACT 权重中 pathConfidence=0.35，低 pathConfidence 显著拉低分数
        VerificationResult vr = mockResult(1.0, 1.0);
        ConfidenceBreakdown breakdown = scorer.score(vr, QueryIntent.CHANGE_IMPACT, 1.0, 0.2, Instant.now());

        // finalScore = 1.0*0.20 + 1.0*0.25 + 1.0*0.20 + 0.2*0.35 + 0.0 = 0.20+0.25+0.20+0.07 = 0.72
        assertEquals(0.72, breakdown.finalScore(), 0.01);
    }

    @Test
    void score_highRiskVeryLowConfidence_belowThreshold() {
        // 所有维度都低 → 高风险意图应低于拒答阈值 0.5
        VerificationResult vr = mockResult(0.2, 0.3);
        ConfidenceBreakdown breakdown = scorer.score(vr, QueryIntent.CHANGE_IMPACT, 0.2, 0.1, null);

        assertTrue(breakdown.finalScore() < ConfidenceScorer.HIGH_RISK_REJECT_THRESHOLD,
            "高风险意图低置信度应低于拒答阈值 0.5，实际: " + breakdown.finalScore());
    }

    @Test
    void score_clampsToRange() {
        VerificationResult vr = mockResult(2.0, 2.0);  // 超出范围
        ConfidenceBreakdown breakdown = scorer.score(vr, QueryIntent.FACT_LOOKUP, 2.0, 2.0, Instant.now());

        assertEquals(1.0, breakdown.finalScore(), 0.001);
        assertEquals(1.0, breakdown.evidenceCoverage());
        assertEquals(1.0, breakdown.evidenceReliability());
    }

    @Test
    void score_negativeValues_clampedToZero() {
        VerificationResult vr = mockResult(-1.0, -0.5);
        ConfidenceBreakdown breakdown = scorer.score(vr, QueryIntent.FACT_LOOKUP, -0.3, -0.2, null);

        // 所有维度 clamp 到 0，但 timeliness=null 时为 0.5，权重 0.10 → 贡献 0.05
        assertEquals(0.05, breakdown.finalScore(), 0.001);
        assertEquals(0.0, breakdown.evidenceCoverage());
        assertEquals(0.0, breakdown.evidenceReliability());
        assertEquals(0.0, breakdown.retrievalConsistency());
        assertEquals(0.0, breakdown.pathConfidence());
    }

    @Test
    void score_timeliness_recentTimestamp_fullScore() {
        VerificationResult vr = mockResult(1.0, 1.0);
        ConfidenceBreakdown breakdown = scorer.score(vr, QueryIntent.FACT_LOOKUP, 1.0, 1.0, Instant.now());

        assertEquals(1.0, breakdown.timeliness(), 0.001);
    }

    @Test
    void score_timeliness_nullTimestamp_mediumScore() {
        VerificationResult vr = mockResult(1.0, 1.0);
        ConfidenceBreakdown breakdown = scorer.score(vr, QueryIntent.FACT_LOOKUP, 1.0, 1.0, null);

        assertEquals(0.5, breakdown.timeliness(), 0.001);
    }

    @Test
    void score_timeliness_oldTimestamp_floorScore() {
        VerificationResult vr = mockResult(1.0, 1.0);
        // 48 小时前
        Instant oldTimestamp = Instant.now().minusSeconds(48 * 3600);
        ConfidenceBreakdown breakdown = scorer.score(vr, QueryIntent.FACT_LOOKUP, 1.0, 1.0, oldTimestamp);

        assertEquals(0.5, breakdown.timeliness(), 0.001);
    }

    @Test
    void score_lowConfidenceFlag_belowThreshold() {
        VerificationResult vr = mockResult(0.1, 0.1);
        ConfidenceBreakdown breakdown = scorer.score(vr, QueryIntent.FACT_LOOKUP, 0.1, 0.1, null);

        assertTrue(breakdown.isLowConfidence());
    }

    @Test
    void computeRetrievalConsistency_singleRanking_returnsOne() {
        assertEquals(1.0, scorer.computeRetrievalConsistency(List.of(List.of("a", "b", "c"))));
    }

    @Test
    void computeRetrievalConsistency_emptyOrNull_returnsOne() {
        assertEquals(1.0, scorer.computeRetrievalConsistency(null));
        assertEquals(1.0, scorer.computeRetrievalConsistency(List.of()));
    }

    @Test
    void computeRetrievalConsistency_allOverlap_returnsOne() {
        List<List<String>> rankings = List.of(
            List.of("a", "b", "c"),
            List.of("a", "b", "c")
        );
        assertEquals(1.0, scorer.computeRetrievalConsistency(rankings));
    }

    @Test
    void computeRetrievalConsistency_noOverlap_returnsZero() {
        List<List<String>> rankings = List.of(
            List.of("a", "b"),
            List.of("c", "d")
        );
        assertEquals(0.0, scorer.computeRetrievalConsistency(rankings));
    }

    @Test
    void computeRetrievalConsistency_partialOverlap() {
        // a, b 出现在两路；c, d 只出现在一路 → 2/4 = 0.5
        List<List<String>> rankings = List.of(
            List.of("a", "b", "c"),
            List.of("a", "b", "d")
        );
        assertEquals(0.5, scorer.computeRetrievalConsistency(rankings));
    }

    @Test
    void score_defaultWeightsPathConfidence_correctContribution() {
        // 验证默认权重下 pathConfidence 贡献正确
        VerificationResult vr = mockResult(0.0, 0.0);
        // finalScore = 0 + 0 + 0 + pathConf*0.15 + 0.5*0.10
        //            = pathConf*0.15 + 0.05
        ConfidenceBreakdown breakdown = scorer.score(vr, QueryIntent.STRUCTURAL, 0.0, 1.0, null);
        assertEquals(0.15 + 0.05, breakdown.finalScore(), 0.001);
    }

    @Test
    void score_highRiskWeightsPathConfidence_correctContribution() {
        // 验证高风险权重下 pathConfidence 贡献正确
        VerificationResult vr = mockResult(0.0, 0.0);
        // finalScore = 0 + 0 + 0 + pathConf*0.35 + 0
        ConfidenceBreakdown breakdown = scorer.score(vr, QueryIntent.CHANGE_IMPACT, 0.0, 1.0, null);
        assertEquals(0.35, breakdown.finalScore(), 0.001);
    }
}
