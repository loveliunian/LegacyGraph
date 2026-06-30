package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("lg_test_case")
public class TestCase {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;

    private String versionId;

    private String caseCode;

    private String caseName;

    private String caseType;

    private String scenario;

    private String targetNodeId;

    private String priority;

    private String preconditions;

    private String steps;

    private String expectedResult;

    private String generatedBy;

    private BigDecimal confidence;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    @TableField(exist = false)
    private String caseNo;

    @TableField(exist = false)
    private String featureName;

    @TableField(exist = false)
    private String apiPath;

    @TableField(exist = false)
    private String method;

    @TableField(exist = false)
    private Integer assertionCount;

    @TableField(exist = false)
    private String generateType;

    @TableField(exist = false)
    private String lastRunStatus;

    @TableField(exist = false)
    private LocalDateTime lastRunTime;

    @TableField(exist = false)
    private String relatedNodeIds;

    @TableField(exist = false)
    private String createdBy;
}
