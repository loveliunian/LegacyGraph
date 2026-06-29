package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 图谱关系表实体
 */
@Data
@TableName("lg_graph_edge")
public class GraphEdge {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionId;
    private String fromNodeId;
    private String toNodeId;
    private String edgeType;
    private String edgeKey;
    private String sourceType;
    private BigDecimal confidence;
    private String status;
    private String properties; // JSONB

    // LLM integration fields — 数据库尚未迁移，标记为 exist=false 避免查询报错
    @TableField(exist = false)
    private String evidenceIds; // JSONB
    @TableField(exist = false)
    private String relationStatus; // candidate / verified / review / rejected
    @TableField(exist = false)
    private BigDecimal verifiedScore;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
