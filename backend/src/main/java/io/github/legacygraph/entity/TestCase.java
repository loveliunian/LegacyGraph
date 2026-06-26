package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 测试用例表实体
 */
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
    private String targetNodeId;
    private String priority;
    private String preconditions; // JSONB
    private String steps; // JSONB
    private String expectedResult; // JSONB
    private String generatedBy;
    private BigDecimal confidence;
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
