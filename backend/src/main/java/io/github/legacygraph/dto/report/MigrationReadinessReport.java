package io.github.legacygraph.dto.report;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 迁移就绪度报告
 * 评估项目从遗留系统迁移到新系统的准备程度
 */
@Data
public class MigrationReadinessReport {

    private String projectId;
    private String projectName;
    private LocalDateTime generatedAt;

    // 整体就绪度得分 0-100
    private BigDecimal overallScore;

    // 各项得分
    private BigDecimal architectureUnderstandingScore;  // 架构理解度
    private BigDecimal businessKnowledgeScore;       // 业务知识覆盖
    private BigDecimal testCoverageScore;            // 测试覆盖率
    private BigDecimal confidenceLevel;              // 整体置信度

    // 统计数据
    private long totalNodes;
    private long confirmedNodes;
    private long pendingNodes;
    private long totalEdges;
    private long confirmedEdges;
    private long pendingEdges;

    // 按节点类型统计
    private List<NodeTypeStat> nodeTypeStats;

    // 风险项
    private List<RiskItem> riskItems;

    // 建议
    private List<String> recommendations;

    @Data
    public static class NodeTypeStat {
        private String nodeType;
        private String displayName;
        private long total;
        private long confirmed;
        private BigDecimal averageConfidence;
    }

    @Data
    public static class RiskItem {
        private String riskType;      // LOW_CONFIDENCE / MISSING_TEST / DISCONNECTED
        private String description;
        private String affectedNodeId;
        private String affectedNodeName;
        private BigDecimal riskLevel;  // 0-1
    }
}
