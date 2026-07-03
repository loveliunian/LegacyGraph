package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * QA 消息实体
 */
@Data
@TableName("lg_qa_message")
public class QaMessage {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联会话ID */
    private String conversationId;

    /** 角色：USER / ASSISTANT */
    private String role;

    /** 消息内容 */
    private String content;

    /** 证据列表 JSON */
    private String evidences;

    /** 置信度 */
    private BigDecimal confidence;

    /** Token 数量 */
    private Integer tokenCount;

    private LocalDateTime createdAt;
}
