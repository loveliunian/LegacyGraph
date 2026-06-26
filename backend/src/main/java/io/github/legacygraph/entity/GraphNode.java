package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 图谱节点表实体
 */
@Data
@TableName("lg_graph_node")
public class GraphNode {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String versionId;
    private String nodeType;
    private String nodeKey;
    private String nodeName;
    private String displayName;
    private String description;
    private String sourceType;
    private String sourcePath;
    private Integer startLine;
    private Integer endLine;
    private BigDecimal confidence;
    private String status;
    private String properties; // JSONB

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
