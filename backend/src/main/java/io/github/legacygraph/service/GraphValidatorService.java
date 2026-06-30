package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * 图谱验证器服务 — 根据测试结果更新 Neo4j 中图谱节点和关系的置信度
 */
@Slf4j
@Service
public class GraphValidatorService {

    private final Neo4jGraphDao neo4jGraphDao;
    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final GraphCacheInvalidator graphCacheInvalidator;

    public GraphValidatorService(Neo4jGraphDao neo4jGraphDao,
                               TestCaseRepository testCaseRepository,
                               TestResultRepository testResultRepository,
                               GraphCacheInvalidator graphCacheInvalidator) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.testCaseRepository = testCaseRepository;
        this.testResultRepository = testResultRepository;
        this.graphCacheInvalidator = graphCacheInvalidator;
    }

    /** 根据测试结果更新整个版本的置信度 */
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

    private int updateByPassedResult(TestResult result) {
        int updated = 0;
        TestCase testCase = testCaseRepository.getById(result.getTestCaseId());
        if (testCase == null) return 0;
        String targetNodeId = testCase.getTargetNodeId();
        if (targetNodeId == null) return 0;

        // 找到指向目标节点的关系
        List<GraphEdge> edges = neo4jGraphDao.queryEdges(
                null, result.getVersionId(), null, null, 0);
        for (GraphEdge edge : edges) {
            if (!targetNodeId.equals(edge.getToNodeId())) continue;
            BigDecimal newConfidence = edge.getConfidence().add(BigDecimal.valueOf(0.05));
            if (newConfidence.compareTo(BigDecimal.ONE) > 0) newConfidence = BigDecimal.ONE;
            edge.setConfidence(newConfidence.setScale(4, RoundingMode.HALF_UP));
            if (newConfidence.compareTo(BigDecimal.valueOf(0.8)) >= 0) edge.setStatus("CONFIRMED");
            neo4jGraphDao.updateEdge(edge);
            updated++;
        }
        return updated;
    }

    private int updateByFailedResult(TestResult result) {
        int updated = 0;
        TestCase testCase = testCaseRepository.getById(result.getTestCaseId());
        if (testCase == null) return 0;
        String targetNodeId = testCase.getTargetNodeId();
        String projectId = testCase.getProjectId();

        switch (testCase.getCaseType()) {
            case "API":
                if (result.getResponseData() != null && result.getResponseData().contains("404")) {
                    Optional<GraphNode> nodeOpt = neo4jGraphDao.findNodeById(targetNodeId);
                    if (nodeOpt.isPresent() && "ApiEndpoint".equals(nodeOpt.get().getNodeType())) {
                        GraphNode node = nodeOpt.get();
                        node.setStatus("INVALID_CANDIDATE");
                        neo4jGraphDao.updateNode(node);
                        updated++;
                    }
                } else {
                    List<GraphEdge> edges = neo4jGraphDao.queryEdges(
                            projectId, result.getVersionId(), null, null, 0);
                    for (GraphEdge edge : edges) {
                        if (!targetNodeId.equals(edge.getToNodeId())) continue;
                        if (!"IMPLEMENTED_BY".equals(edge.getEdgeType()) && !"EXPOSED_BY".equals(edge.getEdgeType()))
                            continue;
                        BigDecimal newConfidence = edge.getConfidence().subtract(BigDecimal.valueOf(0.1));
                        if (newConfidence.compareTo(BigDecimal.ZERO) < 0) newConfidence = BigDecimal.ZERO;
                        edge.setConfidence(newConfidence.setScale(4, RoundingMode.HALF_UP));
                        if (newConfidence.compareTo(BigDecimal.valueOf(0.5)) < 0) edge.setStatus("PENDING_CONFIRM");
                        neo4jGraphDao.updateEdge(edge);
                        updated++;
                    }
                }
                break;

            case "DB_ASSERTION":
                List<GraphEdge> dbEdges = neo4jGraphDao.queryEdges(projectId, result.getVersionId(), null, null, 0);
                for (GraphEdge edge : dbEdges) {
                    if (!"READS".equals(edge.getEdgeType()) && !"WRITES".equals(edge.getEdgeType())) continue;
                    BigDecimal newConfidence = edge.getConfidence().subtract(BigDecimal.valueOf(0.15));
                    if (newConfidence.compareTo(BigDecimal.ZERO) < 0) newConfidence = BigDecimal.ZERO;
                    edge.setConfidence(newConfidence.setScale(4, RoundingMode.HALF_UP));
                    neo4jGraphDao.updateEdge(edge);
                    updated++;
                }
                break;

            case "PERMISSION":
                List<GraphEdge> permEdges = neo4jGraphDao.queryEdges(projectId, result.getVersionId(), null, null, 0);
                for (GraphEdge edge : permEdges) {
                    if (!"REQUIRES_PERMISSION".equals(edge.getEdgeType())) continue;
                    BigDecimal newConfidence = edge.getConfidence().subtract(BigDecimal.valueOf(0.2));
                    if (newConfidence.compareTo(BigDecimal.ZERO) < 0) newConfidence = BigDecimal.ZERO;
                    edge.setConfidence(newConfidence.setScale(4, RoundingMode.HALF_UP));
                    neo4jGraphDao.updateEdge(edge);
                    updated++;
                }
                break;
        }
        return updated;
    }

    @Transactional
    public void confirmNode(String nodeId, String reviewer) {
        Optional<GraphNode> nodeOpt = neo4jGraphDao.findNodeById(nodeId);
        if (nodeOpt.isPresent()) {
            GraphNode node = nodeOpt.get();
            node.setStatus("CONFIRMED");
            node.setConfidence(BigDecimal.ONE);
            neo4jGraphDao.updateNode(node);
            log.info("Node confirmed: {} by {}", nodeId, reviewer);
        }
    }

    @Transactional
    public void rejectNode(String nodeId, String reviewer) {
        Optional<GraphNode> nodeOpt = neo4jGraphDao.findNodeById(nodeId);
        if (nodeOpt.isPresent()) {
            GraphNode node = nodeOpt.get();
            node.setStatus("REJECTED");
            neo4jGraphDao.updateNode(node);
            log.info("Node rejected: {} by {}", nodeId, reviewer);
        }
    }

    @Transactional
    public void confirmEdge(String edgeId, String reviewer) {
        List<GraphEdge> allEdges = neo4jGraphDao.queryEdges(null, null, null, null, 0);
        for (GraphEdge edge : allEdges) {
            if (edge.getId().equals(edgeId)) {
                edge.setStatus("CONFIRMED");
                edge.setConfidence(BigDecimal.ONE);
                neo4jGraphDao.updateEdge(edge);
                log.info("Edge confirmed: {} by {}", edgeId, reviewer);
                return;
            }
        }
    }

    @Transactional
    public void rejectEdge(String edgeId, String reviewer) {
        List<GraphEdge> allEdges = neo4jGraphDao.queryEdges(null, null, null, null, 0);
        for (GraphEdge edge : allEdges) {
            if (edge.getId().equals(edgeId)) {
                edge.setStatus("REJECTED");
                neo4jGraphDao.updateEdge(edge);
                log.info("Edge rejected: {} by {}", edgeId, reviewer);
                return;
            }
        }
    }

    public ValidationReport getValidationReport(String versionId) {
        long totalNodes = neo4jGraphDao.countNodes(null, versionId, null);
        long confirmedNodes = neo4jGraphDao.countNodes(null, versionId, "CONFIRMED");
        long pendingNodes = neo4jGraphDao.countNodes(null, versionId, "PENDING_CONFIRM");
        long totalEdges = neo4jGraphDao.countEdges(null, versionId, null);
        long confirmedEdges = neo4jGraphDao.countEdges(null, versionId, "CONFIRMED");
        long pendingEdges = neo4jGraphDao.countEdges(null, versionId, "PENDING_CONFIRM");

        long passedTests = testResultRepository.lambdaQuery()
                .eq(TestResult::getVersionId, versionId)
                .eq(TestResult::getResultStatus, "PASSED").count();
        long failedTests = testResultRepository.lambdaQuery()
                .eq(TestResult::getVersionId, versionId)
                .eq(TestResult::getResultStatus, "FAILED").count();

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
        List<GraphNode> nodes = neo4jGraphDao.queryNodes(null, versionId, null, null, null, null, 0);
        if (nodes.isEmpty()) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (GraphNode node : nodes) {
            if (node.getConfidence() != null) total = total.add(node.getConfidence());
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
