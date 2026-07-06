package io.github.legacygraph.review;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Graphify 审核审计记录。
 * <p>
 * 每次审核决策（无论是自动规则还是人工审核）都会写入审计日志，
 * 确保可追溯到审核人、规则、证据和时间。
 * </p>
 *
 * @param candidateId    候选关系ID
 * @param previousStatus 审核前状态（如 PENDING, CONFIRMED, REJECTED）
 * @param newStatus      审核后状态
 * @param reviewer       审核人（规则ID 或 用户名）
 * @param ruleId         命中的规则ID（人工审核时为 null）
 * @param evidenceIds    关联的证据ID集合
 * @param reviewedAt     审核时间
 */
public record GraphifyReviewAudit(
    String candidateId,
    String previousStatus,
    String newStatus,
    String reviewer,
    String ruleId,
    Set<String> evidenceIds,
    LocalDateTime reviewedAt
) {
    public GraphifyReviewAudit {
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId 不能为空");
        }
        if (newStatus == null || newStatus.isBlank()) {
            throw new IllegalArgumentException("newStatus 不能为空");
        }
        if (reviewer == null || reviewer.isBlank()) {
            throw new IllegalArgumentException("reviewer 不能为空");
        }
        if (evidenceIds == null) evidenceIds = Set.of();
        if (reviewedAt == null) reviewedAt = LocalDateTime.now();
    }

    /**
     * 判断此审计是否由规则自动生成。
     */
    public boolean isAutomated() {
        return ruleId != null && !ruleId.isBlank();
    }
}
