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

    /** 变更文件路径集合 JSON（增量扫描上下文，AI 编排阶段用于缩小处理范围） */
    private String changedFilePathsJson;

    /** 受影响节点 ID 集合 JSON（增量扫描上下文，由 BlastRadius 分析产生） */
    private String affectedNodeIdsJson;

    private String errorMessage;

    /** 当前执行步骤（状态机） */
    @TableField("current_step")
    private String currentStep;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
