package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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

    private String factType;

    private String factName;

    private String sourceType;

    private String sourcePath;

    private Integer sourceLine;

    private String contentSummary;

    private Double confidence;

    private Boolean mappedToGraph;

    private Integer relatedNodeCount;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // LLM integration fields added per detailed design
    private String evidenceIds; // JSONB
    private String extractorName;
    private String extractorVersion;
    private Long promptRunId;
    private Boolean piiMasked;
    private String reviewStatus;
    private Boolean verifiedByTest;
}
