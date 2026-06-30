package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lg_review_record")
public class ReviewRecord {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String versionId;

    private String targetType;

    private String targetId;

    private String targetName;

    private String graphType;

    private Double confidence;

    private Integer evidenceCount;

    private String priority;

    private String status;

    private String assignee;

    private String comment;

    private String reviewedBy;

    private LocalDateTime reviewedAt;

    /** JSONB 字段 — 审核前数据快照 */
    private String beforeData;

    /** JSONB 字段 — 审核后数据快照 */
    private String afterData;

    private LocalDateTime createdAt;
}
