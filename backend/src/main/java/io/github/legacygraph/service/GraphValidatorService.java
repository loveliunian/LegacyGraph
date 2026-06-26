package io.github.legacygraph.service;

import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 图谱验证器服务
 * 根据测试执行结果更新图谱节点和关系的置信度
 *
 * 根据文档规则:
 * - 测试通过: confidence + 0.05 (最高 1.0)
 * - 测试失败但接口存在: ApiEndpoint 保持，业务映射关系 - 0.1
 * - 接口 404: ApiEndpoint 标记为 INVALID_CANDIDATE
 * - DB 断言失败: SQL -> Table 关系 - 0.15
 * - 权限断言失败: Permission 关系 - 0.2
 * - 人工确认正确: status = CONFIRMED, confidence = 1.0
 * - 人工否定: status = REJECTED
 */
@Slf4j
@Service
public class GraphValidatorService {

    private final GraphNodeRepository graphNodeRepository;
    private final GraphEdgeRepository graphEdgeRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;

    public GraphValidatorService(GraphNodeRepository graphNodeRepository,
                               GraphEdgeRepository graphEdgeRepository,
                               TestCaseRepository testCaseRepository,
                               TestResultRepository testResultRepository) {
        this.graphNodeRepository = graphNodeRepository;
        this.graphEdgeRepository = graphEdgeRepository;
        this.testCaseRepository = testCaseRepository;
        this.testResultRepository = testResultRepository;
    }

    /**
     * 根据测试结果更新整个版本的置信度
     */
    @Transactional
    public void updateConfidenceByTestResults(String versionId) {
        List<TestResult> results = testResultRepository.lambdaQuery()
                .eq(TestResult::getVersionId, versionId)
                .list();

        int updated = 0;
        for (TestResult result : results) {
            if ("PASSED".equals(result.getResultStatus())) {
                updated += updateByPassedResult(result);
            } else if ("FAILED".equals(result.getResultStatus())) {
                updated += updateByFailedResult(result);
            }
        }

        log.info("Updated confidence for {} relations based on test results for version {}", updated, versionId);
    }

    /**
     * 测试通过，相关关系置信度提高
     */
    private int updateByPassedResult(TestResult result) {
        int updated = 0;
        TestCase testCase = testCaseRepository.getById(result.getTestCaseId());
        if (testCase == null) return 0;

        String targetNodeId = testCase.getTargetNodeId();
        if (targetNodeId == null) return 0;

        // 找到指向目标节点的关系，这些关系应该被验证
        List<GraphEdge> edges = graphEdgeRepository.lambdaQuery()
                .eq(GraphEdge::getVersionId, result.getVersionId())
                .and(w -> w.eq(GraphEdge::getToNodeId, targetNodeId).or().eq(GraphEdge::getFromNodeId, targetNodeId))
                .list();

        for (GraphEdge edge : edges) {
            BigDecimal newConfidence = edge.getConfidence().add(BigDecimal.valueOf(0.05));
            if (newConfidence.compareTo(BigDecimal.ONE) > 0) {
                newConfidence = BigDecimal.ONE;
            }
            edge.setConfidence(newConfidence.setScale(4, RoundingMode.HALF_UP));
            // 置信度超过0.8自动确认
            if (newConfidence.compareTo(BigDecimal.valueOf(0.8)) >= 0) {
                edge.setStatus("CONFIRMED");
            }
            graphEdgeRepository.updateById(edge);
            updated++;
        }

        return updated;
    }

    /**
     * 测试失败，根据失败类型降低置信度
     */
    private int updateByFailedResult(TestResult result) {
        int updated = 0;
        TestCase testCase = testCaseRepository.getById(result.getTestCaseId());
        if (testCase == null) return 0;

        String targetNodeId = testCase.getTargetNodeId();

        // 根据测试用例类型处理
        String caseType = testCase.getCaseType();
        switch (caseType) {
            case "API":
                // 如果是404，标记接口无效
                if (result.getResponseData() != null && result.getResponseData().contains("404")) {
                    GraphNode node = graphNodeRepository.getById(targetNodeId);
                    if (node != null && "ApiEndpoint".equals(node.getNodeType())) {
                        node.setStatus("INVALID_CANDIDATE");
                        graphNodeRepository.updateById(node);
                        updated++;
                    }
                } else {
                    // 接口存在但测试失败，降低映射关系置信度
                    List<GraphEdge> edges = graphEdgeRepository.lambdaQuery()
                            .eq(GraphEdge::getVersionId, result.getVersionId())
                            .eq(GraphEdge::getToNodeId, targetNodeId)
                            .in(GraphEdge::getEdgeType, "IMPLEMENTED_BY", "EXPOSED_BY")
                            .list();
                    for (GraphEdge edge : edges) {
                        BigDecimal newConfidence = edge.getConfidence().subtract(BigDecimal.valueOf(0.1));
                        if (newConfidence.compareTo(BigDecimal.ZERO) < 0) {
                            newConfidence = BigDecimal.ZERO;
                        }
                        edge.setConfidence(newConfidence.setScale(4, RoundingMode.HALF_UP));
                        if (newConfidence.compareTo(BigDecimal.valueOf(0.5)) < 0) {
                            edge.setStatus("PENDING_CONFIRM");
                        }
                        graphEdgeRepository.updateById(edge);
                        updated++;
                    }
                }
                break;

            case "DB_ASSERTION":
                // DB断言失败，降低读写关系置信度
                List<GraphEdge> edges = graphEdgeRepository.lambdaQuery()
                        .eq(GraphEdge::getVersionId, result.getVersionId())
                        .in(GraphEdge::getEdgeType, "READS", "WRITES")
                        .list();
                for (GraphEdge edge : edges) {
                    BigDecimal newConfidence = edge.getConfidence().subtract(BigDecimal.valueOf(0.15));
                    if (newConfidence.compareTo(BigDecimal.ZERO) < 0) {
                        newConfidence = BigDecimal.ZERO;
                    }
                    edge.setConfidence(newConfidence.setScale(4, RoundingMode.HALF_UP));
                    graphEdgeRepository.updateById(edge);
                    updated++;
                }
                break;

            case "PERMISSION":
                // 权限断言失败，降低权限关系置信度
                List<GraphEdge> permEdges = graphEdgeRepository.lambdaQuery()
                        .eq(GraphEdge::getVersionId, result.getVersionId())
                        .eq(GraphEdge::getEdgeType, "REQUIRES_PERMISSION")
                        .list();
                for (GraphEdge edge : permEdges) {
                    BigDecimal newConfidence = edge.getConfidence().subtract(BigDecimal.valueOf(0.2));
                    if (newConfidence.compareTo(BigDecimal.ZERO) < 0) {
                        newConfidence = BigDecimal.ZERO;
                    }
                    edge.setConfidence(newConfidence.setScale(4, RoundingMode.HALF_UP));
                    graphEdgeRepository.updateById(edge);
                    updated++;
                }
                break;
        }

        return updated;
    }

    /**
     * 人工确认节点
     */
    @Transactional
    public void confirmNode(String nodeId, String reviewer) {
        GraphNode node = graphNodeRepository.getById(nodeId);
        if (node != null) {
            node.setStatus("CONFIRMED");
            node.setConfidence(BigDecimal.ONE);
            graphNodeRepository.updateById(node);
            log.info("Node confirmed: {} by {}", nodeId, reviewer);
        }
    }

    /**
     * 人工驳回节点
     */
    @Transactional
    public void rejectNode(String nodeId, String reviewer) {
        GraphNode node = graphNodeRepository.getById(nodeId);
        if (node != null) {
            node.setStatus("REJECTED");
            graphNodeRepository.updateById(node);
            log.info("Node rejected: {} by {}", nodeId, reviewer);
        }
    }

    /**
     * 人工确认关系
     */
    @Transactional
    public void confirmEdge(String edgeId, String reviewer) {
        GraphEdge edge = graphEdgeRepository.getById(edgeId);
        if (edge != null) {
            edge.setStatus("CONFIRMED");
            edge.setConfidence(BigDecimal.ONE);
            graphEdgeRepository.updateById(edge);
            log.info("Edge confirmed: {} by {}", edgeId, reviewer);
        }
    }

    /**
     * 人工驳回关系
     */
    @Transactional
    public void rejectEdge(String edgeId, String reviewer) {
        GraphEdge edge = graphEdgeRepository.getById(edgeId);
        if (edge != null) {
            edge.setStatus("REJECTED");
            graphEdgeRepository.updateById(edge);
            log.info("Edge rejected: {} by {}", edgeId, reviewer);
        }
    }

    /**
     * 获取验证报告统计
     */
    public ValidationReport getValidationReport(String versionId) {
        long totalNodes = graphNodeRepository.lambdaQuery()
                .eq(GraphNode::getVersionId, versionId)
                .count();
        long confirmedNodes = graphNodeRepository.lambdaQuery()
                .eq(GraphNode::getVersionId, versionId)
                .eq(GraphNode::getStatus, "CONFIRMED")
                .count();
        long pendingNodes = graphNodeRepository.lambdaQuery()
                .eq(GraphNode::getVersionId, versionId)
                .eq(GraphNode::getStatus, "PENDING_CONFIRM")
                .count();

        long totalEdges = graphEdgeRepository.lambdaQuery()
                .eq(GraphEdge::getVersionId, versionId)
                .count();
        long confirmedEdges = graphEdgeRepository.lambdaQuery()
                .eq(GraphEdge::getVersionId, versionId)
                .eq(GraphEdge::getStatus, "CONFIRMED")
                .count();
        long pendingEdges = graphEdgeRepository.lambdaQuery()
                .eq(GraphEdge::getVersionId, versionId)
                .eq(GraphEdge::getStatus, "PENDING_CONFIRM")
                .count();

        long passedTests = testResultRepository.lambdaQuery()
                .eq(TestResult::getVersionId, versionId)
                .eq(TestResult::getResultStatus, "PASSED")
                .count();
        long failedTests = testResultRepository.lambdaQuery()
                .eq(TestResult::getVersionId, versionId)
                .eq(TestResult::getResultStatus, "FAILED")
                .count();

        ValidationReport report = new ValidationReport();
        report.setVersionId(versionId);
        report.setTotalNodes(totalNodes);
        report.setConfirmedNodes(confirmedNodes);
        report.setPendingNodes(pendingNodes);
        report.setTotalEdges(totalEdges);
        report.setConfirmedEdges(confirmedEdges);
        report.setPendingEdges(pendingEdges);
        report.setPassedTests(passedTests);
        report.setFailedTests(failedTests);
        report.setOverallConfidence(calculateOverallConfidence(versionId));

        return report;
    }

    private BigDecimal calculateOverallConfidence(String versionId) {
        List<GraphNode> nodes = graphNodeRepository.lambdaQuery()
                .eq(GraphNode::getVersionId, versionId)
                .list();
        if (nodes.isEmpty()) return BigDecimal.ZERO;

        BigDecimal total = BigDecimal.ZERO;
        for (GraphNode node : nodes) {
            total = total.add(node.getConfidence());
        }
        return total.divide(BigDecimal.valueOf(nodes.size()), 4, RoundingMode.HALF_UP);
    }

    @lombok.Data
    public static class ValidationReport {
        private String versionId;
        private long totalNodes;
        private long confirmedNodes;
        private long pendingNodes;
        private long totalEdges;
        private long confirmedEdges;
        private long pendingEdges;
        private long passedTests;
        private long failedTests;
        private BigDecimal overallConfidence;
    }
}
