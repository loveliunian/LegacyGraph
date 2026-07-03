package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 图谱节点模型
 * @deprecated 数据已迁移到 Neo4j（Neo4jGraphDao），此实体仅保留用于 MyBatis-Plus Bean 定义
 *           以维持 Spring 上下文加载兼容性。所有读写请使用 Neo4jGraphDao。
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
    private String properties;
    private String scanType;
    private String className;

    private BigDecimal verifiedScore;
    private String evidenceIds;
    private String aliasNames;

    private Boolean runtimeVerified;
    private LocalDateTime lastSeenAt;
    private Integer traceCount;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Integer deleted;
}
