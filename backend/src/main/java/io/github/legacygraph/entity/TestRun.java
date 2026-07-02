package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 测试运行实体
 * 记录一次批量测试执行的运行信息
 */
@Data
@TableName("lg_test_run")
public class TestRun {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String versionId;

    private String environment;

    private String status; // RUNNING|FINISHED|FAILED|CANCELLED

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Integer totalCases;

    private Integer passedCases;

    private Integer failedCases;

    private Integer deleted;
}
