package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关系证据关联表实体
 */
@Data
@TableName("lg_edge_evidence")
public class EdgeEvidence {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String edgeId;
    private String evidenceId;
    private String relationType;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
