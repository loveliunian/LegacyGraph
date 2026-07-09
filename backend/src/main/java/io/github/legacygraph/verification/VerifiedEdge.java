package io.github.legacygraph.verification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 外部工具验证的边结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifiedEdge {

    /** 源节点 key */
    private String fromNodeKey;

    /** 目标节点 key */
    private String toNodeKey;

    /** 边类型（CALLS/EXTENDS/IMPLEMENTS 等） */
    private String edgeType;

    /** 置信度（0.0~1.0） */
    private Double confidence;

    /** 来源工具名 */
    private String sourceTool;
}
