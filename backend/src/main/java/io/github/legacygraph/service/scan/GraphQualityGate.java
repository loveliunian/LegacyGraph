package io.github.legacygraph.service.scan;

import io.github.legacygraph.dto.scan.Decision;

/**
 * 图谱质量门禁 — 在图谱发布前对指定版本的图谱质量进行评估，决定是否放行。
 * <p>
 * 门禁规则（任一不满足即拦截）：
 * <ul>
 *   <li>{@value #RULE_EDGE_NODE_RATIO_BELOW_1} — 边/节点比 &lt; 1.0</li>
 *   <li>{@value #RULE_ISOLATED_RATE_ABOVE_10_PERCENT} — 孤立节点率 &gt; 10%</li>
 *   <li>{@value #RULE_CONSTRAINT_VIOLATIONS} — 约束违反数 &gt; 0</li>
 *   <li>{@value #RULE_EVIDENCE_RATE_BELOW_95_PERCENT} — 证据覆盖率 &lt; 95%</li>
 * </ul>
 * <p>
 * 实现应通过 {@link io.github.legacygraph.dao.Neo4jGraphDao} 采集图谱统计指标，
 * 复用已有统计方法（versionGraphStats / countIsolatedNodes / countNodesWithoutEdgeTypes 等）。
 */
public interface GraphQualityGate {

    /** 门禁规则名：边/节点比低于 1.0 */
    String RULE_EDGE_NODE_RATIO_BELOW_1 = "EDGE_NODE_RATIO_BELOW_1";

    /** 门禁规则名：孤立节点率超过 10% */
    String RULE_ISOLATED_RATE_ABOVE_10_PERCENT = "ISOLATED_RATE_ABOVE_10_PERCENT";

    /** 门禁规则名：存在约束违反 */
    String RULE_CONSTRAINT_VIOLATIONS = "CONSTRAINT_VIOLATIONS";

    /** 门禁规则名：证据覆盖率低于 95% */
    String RULE_EVIDENCE_RATE_BELOW_95_PERCENT = "EVIDENCE_RATE_BELOW_95_PERCENT";

    /**
     * 评估指定项目版本的图谱质量，返回门禁决策。
     *
     * @param projectId     项目 ID
     * @param scanVersionId 扫描版本 ID
     * @return 门禁决策；passed=true 时 reasons 为空列表，passed=false 时 reasons 包含违反的规则名
     */
    Decision evaluate(String projectId, String scanVersionId);
}
