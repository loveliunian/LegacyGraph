package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 图谱关系模型
 * @deprecated 数据已迁移到 Neo4j（Neo4jGraphDao），此实体仅保留用于 MyBatis-Plus Bean 定义
 *           以维持 Spring 上下文加载兼容性。所有读写请使用 Neo4jGraphDao。
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
    private String properties;
    private String evidenceIds;
    private BigDecimal verifiedScore;
    private Integer deleted;
    private String relationStatus;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
