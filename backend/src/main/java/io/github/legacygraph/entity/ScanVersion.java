package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 扫描版本表实体
 */
@Data
@TableName(value = "lg_scan_version", autoResultMap = true)
public class ScanVersion {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionNo;
    private String branchName;
    private String commitId;
    private String sourceHash;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private String scanScope; // JSONB
    private String scanStatus;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;

    /** 节点数快照（终态回写，见 V13 迁移） */
    private Long nodeCount;
    /** 边数快照 */
    private Long edgeCount;
    /** 事实数快照 */
    private Long factCount;
    /** 子任务总数快照 */
    private Integer taskTotal;
    /** 成功子任务数快照 */
    private Integer taskSuccess;
    /** 失败子任务数快照 */
    private Integer taskFailed;
    /** 最后阶段快照（COMPLETED 或首个非 SUCCESS 子任务 taskType） */
    private String currentStage;
    /** 统计快照回写时间 */
    private LocalDateTime statsUpdatedAt;

    /** AI 增强状态：PENDING/RUNNING/COMPLETED/FAILED/SKIPPED */
    private String aiEnrichmentStatus;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Integer deleted;
}
