package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 测试结果表实体
 */
@Data
@TableName("lg_test_result")
public class TestResult {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionId;
    private String testCaseId;
    private String executionId;
    private String resultStatus;
    private String requestData; // JSONB
    private String responseData; // JSONB
    private String dbSnapshot; // JSONB
    private String assertionResult; // JSONB
    private String errorMessage;
    private Long durationMs;
    private LocalDateTime executedAt;

    @TableLogic
    private Integer deleted;
}
