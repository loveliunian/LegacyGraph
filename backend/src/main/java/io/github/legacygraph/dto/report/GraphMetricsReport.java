package io.github.legacygraph.dto.report;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 图谱质量度量汇总
 * 一次性输出方法论要求的核心度量：覆盖率、证据完备度、待审核比例、测试通过率、运行时验证比例。
 */
@Data
public class GraphMetricsReport {

    private String projectId;
    private String versionId;

    // 规模
    private long totalNodes;
    private long totalEdges;

    /** 覆盖率：已映射到图谱（CONFIRMED）的节点占比 */
    private BigDecimal coverageRatio;

    /** 证据完备度：拥有至少一条证据关联的节点占比 */
    private BigDecimal evidenceCompletenessRatio;

    /** 待审核比例：状态为 PENDING_CONFIRM 的节点占比 */
    private BigDecimal pendingReviewRatio;

    /** 测试通过率：PASSED 测试结果占全部已执行结果的比例 */
    private BigDecimal testPassRatio;

    /** 运行时验证比例：verifiedScore > 0 的节点占比（接入真实 trace 前通常为 0） */
    private BigDecimal runtimeVerifiedRatio;
}
