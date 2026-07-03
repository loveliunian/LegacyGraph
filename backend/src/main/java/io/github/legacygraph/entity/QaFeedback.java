package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * QA 反馈实体
 */
@Data
@TableName("lg_qa_feedback")
public class QaFeedback {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联消息ID */
    private String messageId;

    /** 关联会话ID */
    private String conversationId;

    /** 项目ID */
    private String projectId;

    /** 是否有用 */
    private Boolean helpful;

    /** 用户反馈文本 */
    private String feedbackText;

    /** 使用的证据ID列表 JSON */
    private String usedEvidenceIds;

    /** 原始问题 */
    private String question;

    /** 原始答案 */
    private String answer;

    private LocalDateTime createdAt;
}
