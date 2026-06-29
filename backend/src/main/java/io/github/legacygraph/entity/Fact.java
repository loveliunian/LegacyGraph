package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

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

    private Integer sourceLine;

    private String contentSummary;
    private String normalizedData;

    private Double confidence;
    private String status;

    private Boolean mappedToGraph;

    private Integer relatedNodeCount;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // LLM integration fields — 数据库尚未迁移，标记为 exist=false 避免查询报错
    @TableField(exist = false)
    private String evidenceIds; // JSONB
    @TableField(exist = false)
    private String extractorName;
    @TableField(exist = false)
    private String extractorVersion;
    @TableField(exist = false)
    private Long promptRunId;
    @TableField(exist = false)
    private Boolean piiMasked;
    @TableField(exist = false)
    private String reviewStatus;
    @TableField(exist = false)
    private Boolean verifiedByTest;
}
