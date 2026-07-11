package io.github.legacygraph.dto.requirement;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 需求影响分析结果（Task 7）。
 * <p>由 {@code ImpactSubgraphService} 从 LinkedTarget 节点出发 BFS 遍历产生，
 * 包含受影响节点列表和影响路径列表。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImpactResult {

    /** 受影响节点列表（含起点） */
    private List<ImpactNode> impactedNodes = new ArrayList<>();

    /** 影响路径列表 */
    private List<ImpactPath> paths = new ArrayList<>();
}
