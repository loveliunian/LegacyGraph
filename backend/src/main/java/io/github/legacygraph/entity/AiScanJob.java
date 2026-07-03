package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.legacygraph.common.ScanStep;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lg_ai_scan_job")
public class AiScanJob {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionId;

    /** PENDING / RUNNING / SUCCESS / FAILED / CANCELLED */
    private String status;

    /** 序列化的 AiScanConfig JSON */
    private String configJson;

    private String errorMessage;

    /** 当前执行步骤（状态机） */
    @TableField("current_step")
    private String currentStep;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
