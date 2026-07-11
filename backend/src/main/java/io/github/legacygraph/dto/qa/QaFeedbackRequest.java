package io.github.legacygraph.dto.qa;

import lombok.Data;

import java.util.List;

/**
 * QA 反馈请求 DTO。
 * <p>
 * 用于评测 UI 中用户对单条答案的反馈提交，包含原始问题、答案标识、声明文本、
 * 反馈类型、用户备注以及期望命中但未命中的证据 ID 列表，便于后续评测样本回收与质量分析。
 * </p>
 */
@Data
public class QaFeedbackRequest {

    /** 原始问题文本 */
    private String question;

    /** 关联答案 / 消息 ID */
    private String answerId;

    /** 答案声明文本（claim），即被反馈的具体答案内容 */
    private String claimText;

    /** 反馈类型：POSITIVE（有用）/ NEGATIVE（无用）/ NEUTRAL（中立补充说明） */
    private String feedbackType;

    /** 期望命中但未出现在答案中的证据 ID 列表（用于召回缺失分析） */
    private List<String> expectedEvidenceIds;

    /**
     * 用户反馈备注（G-08 §13.3 批量评审场景新增）。
     * <p>批量评审时所有目标用例会复用同一段备注，落地到 {@link io.github.legacygraph.entity.QaFeedback#getFeedbackText()}。</p>
     */
    private String feedbackText;
}
