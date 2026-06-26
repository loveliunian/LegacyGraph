package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 扫描版本表实体
 */
@Data
@TableName("lg_scan_version")
public class ScanVersion {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionNo;
    private String branchName;
    private String commitId;
    private String sourceHash;
    private String scanScope; // JSONB
    private String scanStatus;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
