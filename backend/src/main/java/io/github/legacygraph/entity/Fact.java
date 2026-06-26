package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 原始事实表实体
 */
@Data
@TableName("lg_fact")
public class Fact {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionId;
    private String factType;
    private String factKey;
    private String factName;
    private String sourceType;
    private String sourcePath;
    private Integer startLine;
    private Integer endLine;
    private String rawContent;
    private String normalizedData; // JSONB
    private BigDecimal confidence;
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
