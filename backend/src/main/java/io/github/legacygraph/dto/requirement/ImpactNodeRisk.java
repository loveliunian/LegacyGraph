package io.github.legacygraph.dto.requirement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 受影响节点的风险评估 DTO（G-06）。
 * <p>在影响子图节点基础上增加影响层级和风险分数，
 * 供前端分层展示与风险排序使用。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImpactNodeRisk {

    /** 节点 ID */
    private String nodeId;

    /** 节点名称 */
    private String nodeName;

    /** 节点类型 */
    private String nodeType;

    /** 影响层级（L0~L4） */
    private String impactLevel;

    /** 风险分数（各风险因子乘积） */
    private double riskScore;

    /** 距离变更起点的跳数 */
    private int depth;
}
