package io.github.legacygraph.review;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Graphify 审核规则。
 * <p>
 * 规则按顺序匹配，第一个命中的规则决定审核决策。
 * 新规则只能从已审核的候选关系生成，禁止凭空新增自动确认规则。
 * </p>
 *
 * @param ruleId       规则ID
 * @param edgeType     边类型（如 CALLS, READS, WRITES），"*" 表示所有
 * @param sourceType   来源类型（如 GRAPHIFY_AST, EXTRACTED），"*" 表示所有
 * @param minConfidence 最低置信度阈值（0.0-1.0）
 * @param decision     命中时的决策
 * @param reason       规则原因说明
 * @param createdBy    创建人
 * @param createdAt    创建时间
 */
public record GraphifyReviewRule(
    String ruleId,
    String edgeType,
    String sourceType,
    double minConfidence,
    Decision decision,
    String reason,
    String createdBy,
    LocalDateTime createdAt
) {

    /**
     * 审核决策类型。
     */
    public enum Decision {
        /** 自动确认 */
        CONFIRM,
        /** 自动拒绝 */
        REJECT,
        /** 需要人工审核 */
        REQUIRE_MANUAL_REVIEW
    }

    public GraphifyReviewRule {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId 不能为空");
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision 不能为空");
        }
        if (minConfidence < 0.0 || minConfidence > 1.0) {
            throw new IllegalArgumentException("minConfidence 必须在 0.0-1.0 之间");
        }
        if (edgeType == null) edgeType = "*";
        if (sourceType == null) sourceType = "*";
        if (reason == null) reason = "";
        if (createdBy == null) createdBy = "system";
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    /**
     * 判断规则是否匹配给定的边。
     *
     * @param edgeType    边类型
     * @param sourceType  来源类型
     * @param confidence  置信度
     * @return 是否匹配
     */
    public boolean matches(String edgeType, String sourceType, double confidence) {
        boolean edgeMatch = "*".equals(this.edgeType) || this.edgeType.equals(edgeType);
        boolean sourceMatch = "*".equals(this.sourceType) || this.sourceType.equals(sourceType);
        boolean confidenceMatch = confidence >= this.minConfidence;
        return edgeMatch && sourceMatch && confidenceMatch;
    }
}
