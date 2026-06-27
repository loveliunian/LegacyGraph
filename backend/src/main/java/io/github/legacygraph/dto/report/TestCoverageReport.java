package io.github.legacygraph.dto.report;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 测试覆盖率报告
 * 统计哪些图谱节点已经被测试覆盖
 */
@Data
public class TestCoverageReport {

    private String projectId;
    private String versionId;

    // 总体统计
    private long totalNodes;
    private long coveredNodes;
    private BigDecimal coveragePercentage;

    private long totalEdges;
    private long coveredEdges;
    private BigDecimal edgeCoveragePercentage;

    // 按节点类型统计
    private List<NodeTypeCoverage> typeCoverage;

    // 未覆盖节点列表（高置信度但未测试）
    private List<UncoveredItem> highConfidenceUncovered;

    // 已覆盖但失败的用例
    private List<FailedTestCase> failedTestCases;

    @Data
    public static class NodeTypeCoverage {
        private String nodeType;
        private long total;
        private long covered;
        private BigDecimal coverage;
    }

    @Data
    public static class UncoveredItem {
        private String nodeId;
        private String nodeName;
        private String nodeType;
        private BigDecimal confidence;
    }

    @Data
    public static class FailedTestCase {
        private String testCaseId;
        private String testCaseName;
        private String targetNodeName;
        private String failureReason;
    }
}
