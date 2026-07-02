package io.github.legacygraph.dto.report;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 图谱质量报告
 * 评估图谱整体质量，包括连通性、重复度、置信度分布
 */
@Data
public class GraphQualityReport {

    private String projectId;
    private String versionId;

    // 基础统计
    private long totalNodes;
    private long totalEdges;
    private long disconnectedComponents;  // 不连通的组件数量

    // 质量指标
    private BigDecimal averageNodeDegree;          // 平均节点度数
    private BigDecimal averageConfidence;         // 平均置信度
    private BigDecimal duplicateCandidateRatio;   // 重复候选占比
    private BigDecimal density;                    // 图密度

    // 分布统计
    private List<ConfidenceBin> confidenceDistribution;

    // 质量问题
    private List<QualityIssue> qualityIssues;

    // 质量评级 A/B/C/D
    private String qualityRating;

    // Claim/Gap 指标（Phase M1-M3）
    private long claimCount;
    private long confirmedClaimCount;
    private long pendingClaimCount;
    private long conflictedClaimCount;
    private long aiOnlyClaimCount;
    private long gapCount;
    private long openGapCount;
    private long highSeverityGapCount;
    private java.util.Map<String, Long> gapCountByType;

    @Data
    public static class ConfidenceBin {
        private BigDecimal lowerBound;
        private BigDecimal upperBound;
        private long nodeCount;
    }

    @Data
    public static class QualityIssue {
        private String issueType;  // LOW_CONFIDENCE / DISCONNECTED / POSSIBLE_DUPLICATE / ISOLATED
        private String description;
        private String nodeId;
        private String nodeName;
        private BigDecimal impact;
    }
}
