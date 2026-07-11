package io.github.legacygraph.dto.requirement;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 影响子图中的单条影响路径（Task 7）。
 * <p>BFS 遍历产生的一条从起点到终点的多跳节点序列。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImpactPath {

    /** 起点节点 key */
    private String startNodeKey;

    /** 终点节点 key */
    private String endNodeKey;

    /** 路径上的节点 key 序列（含起点和终点） */
    private List<String> pathNodes;

    /** 影响类型：该路径的传播关系类型 */
    private String impactType;

    /** 路径深度（跳数） */
    private int depth;
}
