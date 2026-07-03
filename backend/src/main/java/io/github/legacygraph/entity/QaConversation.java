package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * QA 对话会话实体
 */
@Data
@TableName("lg_qa_conversation")
public class QaConversation {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 项目ID */
    private String projectId;

    /** 浏览器 session ID */
    private String sessionId;

    /** 会话标题（自动从第一条问题生成） */
    private String title;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 前端会话列表展示用，不映射数据库列 */
    @TableField(exist = false)
    private Integer messageCount;
}
