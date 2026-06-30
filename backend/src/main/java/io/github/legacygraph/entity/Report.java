package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生成报告记录表
 */
@Data
@TableName("lg_reports")
public class Report {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionId;
    private String reportType;  // MIGRATION_READINESS / CONFIDENCE_TREND / TEST_COVERAGE / GRAPH_QUALITY
    private String reportName;
    private String status;  // GENERATING / COMPLETED / FAILED
    private String filePath;  // 存储路径（MinIO）
    private LocalDateTime generatedAt;
    private LocalDateTime completedAt;
    private String errorMessage;

    @TableLogic
    private Integer deleted;
}
