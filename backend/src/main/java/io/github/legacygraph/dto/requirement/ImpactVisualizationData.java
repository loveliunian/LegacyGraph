package io.github.legacygraph.dto.requirement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 需求影响可视化数据 DTO（G-20）。
 * <p>由 {@code ImpactVisualizationController} 将 {@link ImpactResult} 转换而来，
 * 供前端影响子图可视化组件直接消费：节点按 impactLevel 着色、按 riskScore 控制大小，
 * 边描述节点间传播关系，摘要提供分层统计与高风险节点清单。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImpactVisualizationData {

    /** 可视化节点列表 */
    private List<VizNode> nodes;

    /** 可视化边列表 */
    private List<VizEdge> edges;

    /** 影响摘要统计 */
    private VizSummary summary;

    /**
     * 可视化节点：合并 ImpactNode 基础信息与 ImpactNodeRisk 的层级/风险分数。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VizNode {
        /** 节点 ID（与图谱节点 ID 对应） */
        private String id;
        /** 节点显示名称 */
        private String label;
        /** 节点类型（如 RequirementItem / Service / Table） */
        private String type;
        /** 影响层级（L0~L4），用于前端着色 */
        private String impactLevel;
        /** 风险分数 [0,1]，用于前端控制节点大小 */
        private double riskScore;
        /** 距离变更起点的跳数 */
        private int depth;
    }

    /**
     * 可视化边：由影响路径中相邻节点对派生。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VizEdge {
        /** 起点节点 key */
        private String source;
        /** 终点节点 key */
        private String target;
        /** 关系类型（如 CALLS / READS / DATA_FLOW） */
        private String relationType;
    }

    /**
     * 影响摘要统计：总节点数、各级别数量、最大风险分数、高风险节点清单。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VizSummary {
        /** 受影响节点总数 */
        private int totalNodes;
        /** 按影响层级分组的节点数（key=L0/L1/L2/L3/L4） */
        private Map<String, Integer> byLevel;
        /** 当前子图中的最大风险分数 */
        private double maxRiskScore;
        /** 高风险节点名称列表（riskScore >= 阈值） */
        private List<String> highRiskNodes;
    }
}
