package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 扫描任务表实体
 */
@Data
@TableName("lg_scan_task")
public class ScanTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionId;
    private String taskType;
    private String taskName;
    private String taskStatus;
    private String inputParams; // JSONB
    private String outputSummary; // JSONB
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Integer deleted;
}
