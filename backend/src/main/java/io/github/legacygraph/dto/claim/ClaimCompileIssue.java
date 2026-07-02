package io.github.legacygraph.dto.claim;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Claim 编译问题 — 冲突、不一致等需要人工判断的情况。
 */
@Data
@Builder
public class ClaimCompileIssue {

    public enum IssueType {
        /** 同一 subject + predicate 指向多个互斥 object */
        CONFLICT,
        /** 同义节点合并候选 */
        DUPLICATE,
        /** AI Claim 试图覆盖 CONFIRMED Claim */
        OVERRIDE_ATTEMPT,
        /** 同一节点 key 出现多个类型 */
        TYPE_MISMATCH,
        /** 置信度不足 */
        LOW_CONFIDENCE
    }

    private IssueType issueType;

    /** 涉及的第一个 Claim ID */
    private String claimIdA;

    /** 涉及的第二个 Claim ID（可选） */
    private String claimIdB;

    /** 问题描述 */
    private String description;

    /** 涉及的主体类型 */
    private String subjectType;

    /** 涉及的主体 key */
    private String subjectKey;

    /** 谓词 */
    private String predicate;

    /** 建议动作 */
    private String suggestedAction;
}
