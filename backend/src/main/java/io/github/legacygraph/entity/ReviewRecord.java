package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人工确认表实体
 */
@Data
@TableName("lg_review_record")
public class ReviewRecord {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionId;
    private String targetType;
    private String targetId;
    private String reviewStatus;
    private String reviewer;
    private String reviewComment;
    private String beforeData; // JSONB
    private String afterData; // JSONB

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
