package io.github.legacygraph.service.systemoverview;

import io.github.legacygraph.entity.KnowledgeClaim;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReportTruthPolicy} 单元测试 — 验证 P0-4 真值口径分区策略。
 */
class ReportTruthPolicyTest {

    @Test
    void forBody_acceptsOnlyConfirmed() {
        assertTrue(ReportTruthPolicy.forBody().test(claim("CONFIRMED")));
        assertFalse(ReportTruthPolicy.forBody().test(claim("PENDING_CONFIRM")));
        assertFalse(ReportTruthPolicy.forBody().test(claim("INFERRED")));
        assertFalse(ReportTruthPolicy.forBody().test(claim("REJECTED")));
        assertFalse(ReportTruthPolicy.forBody().test(claim("STALE")));
        assertFalse(ReportTruthPolicy.forBody().test(claim("CONFLICTED")));
        assertFalse(ReportTruthPolicy.forBody().test(claim(null)));
        assertFalse(ReportTruthPolicy.forBody().test(null));
    }

    @Test
    void forAppendix_acceptsPendingConfirmAndInferred() {
        assertTrue(ReportTruthPolicy.forAppendix().test(claim("PENDING_CONFIRM")));
        assertTrue(ReportTruthPolicy.forAppendix().test(claim("INFERRED")));
        assertFalse(ReportTruthPolicy.forAppendix().test(claim("CONFIRMED")));
        assertFalse(ReportTruthPolicy.forAppendix().test(claim("REJECTED")));
        assertFalse(ReportTruthPolicy.forAppendix().test(claim("STALE")));
        assertFalse(ReportTruthPolicy.forAppendix().test(claim("CONFLICTED")));
        assertFalse(ReportTruthPolicy.forAppendix().test(claim(null)));
        assertFalse(ReportTruthPolicy.forAppendix().test(null));
    }

    @Test
    void excluded_rejectsRejectedStaleConflicted() {
        assertTrue(ReportTruthPolicy.excluded().test(claim("REJECTED")));
        assertTrue(ReportTruthPolicy.excluded().test(claim("STALE")));
        assertTrue(ReportTruthPolicy.excluded().test(claim("CONFLICTED")));
        assertFalse(ReportTruthPolicy.excluded().test(claim("CONFIRMED")));
        assertFalse(ReportTruthPolicy.excluded().test(claim("PENDING_CONFIRM")));
        assertFalse(ReportTruthPolicy.excluded().test(claim("INFERRED")));
        assertFalse(ReportTruthPolicy.excluded().test(claim(null)));
        assertFalse(ReportTruthPolicy.excluded().test(null));
    }

    @Test
    void isIncludable_acceptsConfirmedAndPendingAndInferred() {
        assertTrue(ReportTruthPolicy.isIncludable(claim("CONFIRMED")));
        assertTrue(ReportTruthPolicy.isIncludable(claim("PENDING_CONFIRM")));
        assertTrue(ReportTruthPolicy.isIncludable(claim("INFERRED")));
        assertFalse(ReportTruthPolicy.isIncludable(claim("REJECTED")));
        assertFalse(ReportTruthPolicy.isIncludable(claim("STALE")));
        assertFalse(ReportTruthPolicy.isIncludable(claim("CONFLICTED")));
        assertFalse(ReportTruthPolicy.isIncludable(claim(null)));
        assertFalse(ReportTruthPolicy.isIncludable(null));
    }

    private static KnowledgeClaim claim(String status) {
        KnowledgeClaim claim = new KnowledgeClaim();
        claim.setStatus(status);
        return claim;
    }
}
