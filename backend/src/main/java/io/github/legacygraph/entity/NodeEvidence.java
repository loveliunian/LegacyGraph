package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点证据关联表实体
 */
@Data
@TableName("lg_node_evidence")
public class NodeEvidence {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String nodeId;
    private String evidenceId;
    private String relationType;

    private LocalDateTime createdAt;

    private Integer deleted;
}
