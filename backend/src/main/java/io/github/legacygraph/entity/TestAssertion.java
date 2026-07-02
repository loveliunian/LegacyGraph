package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 测试断言表实体
 */
@Data
@TableName("lg_test_assertion")
public class TestAssertion {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String testCaseId;
    private String assertionType;
    private String assertionName;
    private String expression;
    private String expectedValue; // JSONB
    private String actualValue; // JSONB
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Integer deleted;
}
