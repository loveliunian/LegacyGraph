package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * QA 声明反馈实体 — 记录对图谱声明（claim）的评估反馈与期望证据。
 *
 * <p>与 G-08 的 {@link QaFeedback}（QA 对话消息级反馈）不同，
 * 本实体关注图谱发布版本维度的声明级反馈，用于质量评估与回流。</p>
 */
@Data
@TableName("lg_qa_claim_feedback")
public class QaFeedbackEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 项目 ID */
    private String projectId;

    /** 图谱发布版本 ID */
    private String graphReleaseId;

    /** 问题哈希（关联具体问答请求） */
    private String questionHash;

    /** 被反馈的声明文本 */
    private String claimText;

    /** 反馈类型（如 MISSING_EVIDENCE / WRONG_CLAIM / HALLUCINATION 等） */
    private String feedbackType;

    /** 期望证据列表 JSON */
    private String expectedEvidence;

    /** 触发反馈的主体（如 user:alice） */
    private String principal;

    private LocalDateTime createdAt;
}
