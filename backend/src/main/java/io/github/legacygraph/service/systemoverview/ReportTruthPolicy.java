package io.github.legacygraph.service.systemoverview;

import io.github.legacygraph.entity.KnowledgeClaim;
import java.util.function.Predicate;

/**
 * 报告真值口径策略 — 统一发布口径，区分正文与附录。
 * <p>
 * 落地 P0-4「统一报告的真值口径」：
 * <ul>
 *   <li>正文（已确认结论）仅包含 CONFIRMED 状态的 Claim</li>
 *   <li>附录（待确认/推断）包含 PENDING_CONFIRM / INFERRED 状态的 Claim，需明确标识</li>
 *   <li>REJECTED / STALE / CONFLICTED 不得进入结论</li>
 * </ul>
 */
public class ReportTruthPolicy {

    /** 正文仅 CONFIRMED */
    public static Predicate<KnowledgeClaim> forBody() {
        return c -> c != null && "CONFIRMED".equals(c.getStatus());
    }

    /** 附录含 PENDING_CONFIRM / INFERRED */
    public static Predicate<KnowledgeClaim> forAppendix() {
        return c -> c != null && ("PENDING_CONFIRM".equals(c.getStatus())
                || "INFERRED".equals(c.getStatus()));
    }

    /** REJECTED / STALE / CONFLICTED 不得进入结论 */
    public static Predicate<KnowledgeClaim> excluded() {
        return c -> c != null && ("REJECTED".equals(c.getStatus())
                || "STALE".equals(c.getStatus())
                || "CONFLICTED".equals(c.getStatus()));
    }

    /** 判断 Claim 是否可进入报告（正文或附录） */
    public static boolean isIncludable(KnowledgeClaim claim) {
        if (claim == null) return false;
        return forBody().or(forAppendix()).test(claim);
    }
}
