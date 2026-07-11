package io.github.legacygraph.dto.requirement;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 影响子图中的受影响节点（Task 7）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImpactNode {

    /** 节点 ID */
    private String nodeId;

    /** 节点 key */
    private String nodeKey;

    /** 节点名称 */
    private String nodeName;

    /** 节点类型 */
    private String nodeType;

    /** 距离起点的跳数（起点为 0） */
    private int depth;

    /** 影响类型：DIRECT（直接 AFFECTS）/ 传播关系类型（如 CALLS、READS） */
    private String impactType;
}
