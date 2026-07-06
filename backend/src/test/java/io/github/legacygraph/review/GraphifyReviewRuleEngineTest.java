package io.github.legacygraph.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphifyReviewRuleEngine 单元测试。
 */
class GraphifyReviewRuleEngineTest {

    private GraphifyReviewRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new GraphifyReviewRuleEngine();
    }

    // ===== decide() 测试 =====

    @Test
    @DisplayName("高置信 EXTRACTED CALLS 可以被规则自动确认")
    void highConfidenceExtractedCallCanBeConfirmedByRule() {
        engine = new GraphifyReviewRuleEngine(List.of(
            rule("CALLS", "GRAPHIFY_AST", 0.9, GraphifyReviewRule.Decision.CONFIRM)
        ));

        assertEquals(GraphifyReviewRule.Decision.CONFIRM,
            engine.decide("CALLS", "GRAPHIFY_AST", 0.95));
    }

    @Test
    @DisplayName("置信度低于阈值时不匹配规则")
    void confidenceBelowThresholdDoesNotMatch() {
        engine = new GraphifyReviewRuleEngine(List.of(
            rule("CALLS", "GRAPHIFY_AST", 0.9, GraphifyReviewRule.Decision.CONFIRM)
        ));

        assertEquals(GraphifyReviewRule.Decision.REQUIRE_MANUAL_REVIEW,
            engine.decide("CALLS", "GRAPHIFY_AST", 0.85));
    }

    @Test
    @DisplayName("边类型不匹配时不命中")
    void edgeTypeMismatchDoesNotHit() {
        engine = new GraphifyReviewRuleEngine(List.of(
            rule("CALLS", "GRAPHIFY_AST", 0.9, GraphifyReviewRule.Decision.CONFIRM)
        ));

        assertEquals(GraphifyReviewRule.Decision.REQUIRE_MANUAL_REVIEW,
            engine.decide("READS", "GRAPHIFY_AST", 0.95));
    }

    @Test
    @DisplayName("来源类型不匹配时不命中")
    void sourceTypeMismatchDoesNotHit() {
        engine = new GraphifyReviewRuleEngine(List.of(
            rule("CALLS", "GRAPHIFY_AST", 0.9, GraphifyReviewRule.Decision.CONFIRM)
        ));

        assertEquals(GraphifyReviewRule.Decision.REQUIRE_MANUAL_REVIEW,
            engine.decide("CALLS", "EXTRACTED", 0.95));
    }

    @Test
    @DisplayName("无规则时默认需要人工审核")
    void noRulesDefaultsToManualReview() {
        assertEquals(GraphifyReviewRule.Decision.REQUIRE_MANUAL_REVIEW,
            engine.decide("CALLS", "GRAPHIFY_AST", 0.99));
    }

    @Test
    @DisplayName("通配符规则匹配所有边类型")
    void wildcardRuleMatchesAllEdgeTypes() {
        engine = new GraphifyReviewRuleEngine(List.of(
            rule("*", "GRAPHIFY_AST", 0.95, GraphifyReviewRule.Decision.CONFIRM)
        ));

        assertEquals(GraphifyReviewRule.Decision.CONFIRM, engine.decide("CALLS", "GRAPHIFY_AST", 0.96));
        assertEquals(GraphifyReviewRule.Decision.CONFIRM, engine.decide("READS", "GRAPHIFY_AST", 0.96));
        assertEquals(GraphifyReviewRule.Decision.CONFIRM, engine.decide("WRITES", "GRAPHIFY_AST", 0.96));
    }

    @Test
    @DisplayName("多规则时第一个命中的规则决定结果")
    void firstMatchingRuleWins() {
        engine = new GraphifyReviewRuleEngine(List.of(
            rule("CALLS", "GRAPHIFY_AST", 0.5, GraphifyReviewRule.Decision.REQUIRE_MANUAL_REVIEW),
            rule("CALLS", "GRAPHIFY_AST", 0.9, GraphifyReviewRule.Decision.CONFIRM)
        ));

        // confidence=0.7: 命中第一条规则（REQUIRE_MANUAL_REVIEW），不会落到第二条
        assertEquals(GraphifyReviewRule.Decision.REQUIRE_MANUAL_REVIEW,
            engine.decide("CALLS", "GRAPHIFY_AST", 0.7));

        // confidence=0.95: 命中第一条规则（也匹配，但第一条优先）
        assertEquals(GraphifyReviewRule.Decision.REQUIRE_MANUAL_REVIEW,
            engine.decide("CALLS", "GRAPHIFY_AST", 0.95));
    }

    @Test
    @DisplayName("低置信 AMBIGUOUS 关系可以被规则拒绝")
    void lowConfidenceAmbiguousCanBeRejected() {
        engine = new GraphifyReviewRuleEngine(List.of(
            rule("*", "AMBIGUOUS", 0.0, GraphifyReviewRule.Decision.REQUIRE_MANUAL_REVIEW)
        ));

        assertEquals(GraphifyReviewRule.Decision.REQUIRE_MANUAL_REVIEW,
            engine.decide("CALLS", "AMBIGUOUS", 0.3));
    }

    // ===== addRule() 安全约束测试 =====

    @Test
    @DisplayName("AMBIGUOUS 来源不允许自动 CONFIRM 规则")
    void ambiguousCannotBeAutoConfirmed() {
        GraphifyReviewRule rule = rule("rule-1", "CALLS", "AMBIGUOUS", 0.9, GraphifyReviewRule.Decision.CONFIRM);

        assertThrows(IllegalArgumentException.class, () -> engine.addRule(rule, true));
    }

    @Test
    @DisplayName("CONFIRM 规则不能凭空新增")
    void confirmRuleCannotBeAddedWithoutReviewedCandidate() {
        GraphifyReviewRule rule = rule("rule-1", "CALLS", "GRAPHIFY_AST", 0.9, GraphifyReviewRule.Decision.CONFIRM);

        assertThrows(IllegalArgumentException.class, () -> engine.addRule(rule, false));
    }

    @Test
    @DisplayName("CONFIRM 规则从已审核候选关系可以添加")
    void confirmRuleFromReviewedCandidateIsAllowed() {
        GraphifyReviewRule rule = rule("rule-1", "CALLS", "GRAPHIFY_AST", 0.9, GraphifyReviewRule.Decision.CONFIRM);

        assertDoesNotThrow(() -> engine.addRule(rule, true));
        assertEquals(1, engine.ruleCount());
    }

    @Test
    @DisplayName("REJECT 规则可以凭空新增")
    void rejectRuleCanBeAddedFreely() {
        GraphifyReviewRule rule = rule("rule-1", "*", "*", 0.0, GraphifyReviewRule.Decision.REJECT);

        assertDoesNotThrow(() -> engine.addRule(rule, false));
        assertEquals(1, engine.ruleCount());
    }

    @Test
    @DisplayName("REQUIRE_MANUAL_REVIEW 规则可以凭空新增")
    void manualReviewRuleCanBeAddedFreely() {
        GraphifyReviewRule rule = rule("rule-1", "CALLS", "INFERRED", 0.0, GraphifyReviewRule.Decision.REQUIRE_MANUAL_REVIEW);

        assertDoesNotThrow(() -> engine.addRule(rule, false));
        assertEquals(1, engine.ruleCount());
    }

    // ===== findMatchingRule() 测试 =====

    @Test
    @DisplayName("findMatchingRule 返回匹配的规则")
    void findMatchingRuleReturnsMatch() {
        GraphifyReviewRule r1 = rule("rule-1", "CALLS", "GRAPHIFY_AST", 0.9, GraphifyReviewRule.Decision.CONFIRM);
        GraphifyReviewRule r2 = rule("rule-2", "READS", "GRAPHIFY_AST", 0.8, GraphifyReviewRule.Decision.CONFIRM);
        engine = new GraphifyReviewRuleEngine(List.of(r1, r2));

        GraphifyReviewRule found = engine.findMatchingRule("READS", "GRAPHIFY_AST", 0.85);
        assertNotNull(found);
        assertEquals("rule-2", found.ruleId());
    }

    @Test
    @DisplayName("findMatchingRule 无匹配返回 null")
    void findMatchingRuleReturnsNullWhenNoMatch() {
        engine = new GraphifyReviewRuleEngine(List.of(
            rule("CALLS", "GRAPHIFY_AST", 0.9, GraphifyReviewRule.Decision.CONFIRM)
        ));

        assertNull(engine.findMatchingRule("WRITES", "EXTRACTED", 0.99));
    }

    // ===== recordAudit() 测试 =====

    @Test
    @DisplayName("记录审核审计日志")
    void recordsAuditLog() {
        GraphifyReviewAudit audit = new GraphifyReviewAudit(
            "cand-1", "PENDING", "CONFIRMED",
            "user:admin", "rule-1",
            Set.of("ev-1", "ev-2"),
            null
        );

        engine.recordAudit(audit);

        assertEquals(1, engine.getAuditLog().size());
        assertEquals("cand-1", engine.getAuditLog().get(0).candidateId());
        assertTrue(engine.getAuditLog().get(0).isAutomated());
    }

    @Test
    @DisplayName("人工审核审计记录 ruleId 为 null")
    void manualReviewAuditHasNoRuleId() {
        GraphifyReviewAudit audit = new GraphifyReviewAudit(
            "cand-2", "PENDING", "REJECTED",
            "user:reviewer", null,
            Set.of(), null
        );

        engine.recordAudit(audit);
        assertFalse(engine.getAuditLog().get(0).isAutomated());
    }

    // ===== GraphifyReviewRule.matches() 测试 =====

    @Test
    @DisplayName("通配符边类型匹配任何边")
    void wildcardEdgeTypeMatchesAny() {
        GraphifyReviewRule rule = rule("rule-1", "*", "GRAPHIFY_AST", 0.5, GraphifyReviewRule.Decision.CONFIRM);

        assertTrue(rule.matches("CALLS", "GRAPHIFY_AST", 0.6));
        assertTrue(rule.matches("READS", "GRAPHIFY_AST", 0.6));
        assertFalse(rule.matches("CALLS", "OTHER", 0.6));
    }

    @Test
    @DisplayName("通配符来源类型匹配任何来源")
    void wildcardSourceTypeMatchesAny() {
        GraphifyReviewRule rule = rule("rule-1", "CALLS", "*", 0.5, GraphifyReviewRule.Decision.CONFIRM);

        assertTrue(rule.matches("CALLS", "GRAPHIFY_AST", 0.6));
        assertTrue(rule.matches("CALLS", "EXTRACTED", 0.6));
        assertTrue(rule.matches("CALLS", "INFERRED", 0.6));
    }

    @Test
    @DisplayName("置信度等于阈值时匹配")
    void confidenceEqualToThresholdMatches() {
        GraphifyReviewRule rule = rule("rule-1", "CALLS", "GRAPHIFY_AST", 0.9, GraphifyReviewRule.Decision.CONFIRM);

        assertTrue(rule.matches("CALLS", "GRAPHIFY_AST", 0.9));
    }

    // ===== GraphifyReviewRule 构造验证 =====

    @Test
    @DisplayName("ruleId 为空抛出异常")
    void emptyRuleIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new GraphifyReviewRule("", "CALLS", "GRAPHIFY_AST", 0.9,
                GraphifyReviewRule.Decision.CONFIRM, "reason", "admin", null));
    }

    @Test
    @DisplayName("decision 为 null 抛出异常")
    void nullDecisionThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new GraphifyReviewRule("r1", "CALLS", "GRAPHIFY_AST", 0.9,
                null, "reason", "admin", null));
    }

    @Test
    @DisplayName("minConfidence 超出范围抛出异常")
    void outOfRangeConfidenceThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new GraphifyReviewRule("r1", "CALLS", "GRAPHIFY_AST", 1.5,
                GraphifyReviewRule.Decision.CONFIRM, "reason", "admin", null));
    }

    // ===== 辅助方法 =====

    private GraphifyReviewRule rule(String edgeType, String sourceType, double minConfidence,
            GraphifyReviewRule.Decision decision) {
        return rule("rule-auto", edgeType, sourceType, minConfidence, decision);
    }

    private GraphifyReviewRule rule(String ruleId, String edgeType, String sourceType,
            double minConfidence, GraphifyReviewRule.Decision decision) {
        return new GraphifyReviewRule(ruleId, edgeType, sourceType, minConfidence, decision,
            "test rule", "tester", null);
    }
}
