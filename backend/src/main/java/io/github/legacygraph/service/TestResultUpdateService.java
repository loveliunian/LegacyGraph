package io.github.legacygraph.service;

import io.github.legacygraph.agent.TestFailureAnalysisAgent;
import io.github.legacygraph.dto.TestFailureAnalysis;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.repository.ReviewRecordRepository;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 测试结果回写服务
 *
 * 依照详细设计文档的回写规则：
 * - 测试 PASS: edge.verified_score += 0.10, node.verified_score += 0.05
 *              relation_status = verified if final_confidence >= 0.85
 * - 测试 FAIL: edge.verified_score -= 0.20, relation_status = review
 *              创建 review_task，附失败上下文
 */
@Slf4j
@Service
public class TestResultUpdateService {

    private final Neo4jGraphDao neo4jGraphDao;
    private final TestResultRepository testResultRepository;
    private final TestCaseRepository testCaseRepository;
    private final ReviewRecordRepository reviewRecordRepository;
    private final TestFailureAnalysisAgent testFailureAnalysisAgent;

    private static final BigDecimal PASS_EDGE_INCREMENT = new BigDecimal("0.10");
    private static final BigDecimal PASS_NODE_INCREMENT = new BigDecimal("0.05");
    private static final BigDecimal FAIL_EDGE_DECREMENT = new BigDecimal("0.20");
    private static final BigDecimal VERIFIED_THRESHOLD = new BigDecimal("0.85");

    public TestResultUpdateService(Neo4jGraphDao neo4jGraphDao,
                                  TestResultRepository testResultRepository,
                                  TestCaseRepository testCaseRepository,
                                  ReviewRecordRepository reviewRecordRepository,
                                  TestFailureAnalysisAgent testFailureAnalysisAgent) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.testResultRepository = testResultRepository;
        this.testCaseRepository = testCaseRepository;
        this.reviewRecordRepository = reviewRecordRepository;
        this.testFailureAnalysisAgent = testFailureAnalysisAgent;
    }

    /**
     * 回写测试通过结果
     */
    @Transactional
    public void onTestPass(String nodeId, String edgeId) {
        if (nodeId != null) {
            GraphNode node = neo4jGraphDao.findNodeById(nodeId).orElse(null);
            if (node != null && node.getVerifiedScore() != null) {
                node.setVerifiedScore(node.getVerifiedScore().add(PASS_NODE_INCREMENT));
                // 不超过 1.0000
                if (node.getVerifiedScore().compareTo(BigDecimal.ONE) > 0) {
                    node.setVerifiedScore(BigDecimal.ONE);
                }
                neo4jGraphDao.updateNode(node);
            }
        }

        if (edgeId != null) {
            GraphEdge edge = neo4jGraphDao.findEdgeById(edgeId).orElse(null);
            if (edge != null && edge.getVerifiedScore() != null) {
                edge.setVerifiedScore(edge.getVerifiedScore().add(PASS_EDGE_INCREMENT));
                // 不超过 1.0000
                if (edge.getVerifiedScore().compareTo(BigDecimal.ONE) > 0) {
                    edge.setVerifiedScore(BigDecimal.ONE);
                }
                // 如果总分 >= 0.85，标记为 verified
                BigDecimal totalConfidence = calculateTotalConfidence(edge);
                if (totalConfidence.compareTo(VERIFIED_THRESHOLD) >= 0) {
                    edge.setRelationStatus("verified");
                }
                neo4jGraphDao.updateEdge(edge);
            }
        }

        log.info("Test PASS result written: nodeId={}, edgeId={}", nodeId, edgeId);
    }

    /**
     * 回写测试失败结果 - 完整实现，自动创建审核任务
     */
    @Transactional
    public void onTestFail(String nodeId, String edgeId, String testResultId, String failureMessage) {
        if (edgeId != null) {
            GraphEdge edge = neo4jGraphDao.findEdgeById(edgeId).orElse(null);
            if (edge != null && edge.getVerifiedScore() != null) {
                edge.setVerifiedScore(edge.getVerifiedScore().subtract(FAIL_EDGE_DECREMENT));
                // 不低于 0
                if (edge.getVerifiedScore().compareTo(BigDecimal.ZERO) < 0) {
                    edge.setVerifiedScore(BigDecimal.ZERO);
                }
                edge.setRelationStatus("review");
                neo4jGraphDao.updateEdge(edge);
            }
        }

        // 创建审核任务记录失败上下文
        if (nodeId != null) {
            GraphNode node = neo4jGraphDao.findNodeById(nodeId).orElse(null);
            if (node != null) {
                ReviewRecord review = new ReviewRecord();
                review.setId(UUID.randomUUID().toString());
                review.setProjectId(node.getProjectId());
                review.setVersionId(node.getVersionId());
                review.setTargetType(node.getNodeType());
                review.setTargetId(node.getId());
                review.setTargetName(node.getDisplayName());
                review.setGraphType("code");
                review.setConfidence(node.getConfidence().doubleValue());
                review.setPriority("medium");
                review.setStatus("PENDING");
                review.setComment(buildFailureReviewComment(node, testResultId, failureMessage));
                review.setCreatedAt(LocalDateTime.now());
                reviewRecordRepository.insert(review);
                log.info("Created review task for failed test: projectId={}, targetId={}", node.getProjectId(), node.getId());
            }
        }

        log.info("Test FAIL result written: nodeId={}, edgeId={}", nodeId, edgeId);
    }

    /**
     * 回写测试失败结果（无上下文版本，保持向后兼容）
     */
    @Transactional
    public void onTestFail(String nodeId, String edgeId) {
        onTestFail(nodeId, edgeId, null, null);
    }

    private String buildFailureReviewComment(GraphNode node, String testResultId, String failureMessage) {
        StringBuilder comment = new StringBuilder("测试失败: ")
                .append(failureMessage != null ? failureMessage : "验证未通过");
        TestFailureAnalysis analysis = analyzeFailure(node, testResultId, failureMessage);
        if (analysis == null) {
            return comment.toString();
        }
        if (analysis.getSummary() != null && !analysis.getSummary().isBlank()) {
            comment.append("\nAI 根因摘要: ").append(analysis.getSummary());
        }
        if (analysis.getRootCauses() != null && !analysis.getRootCauses().isEmpty()) {
            comment.append("\n可能根因: ");
            comment.append(analysis.getRootCauses().stream()
                    .map(TestFailureAnalysis.RootCause::getCause)
                    .filter(cause -> cause != null && !cause.isBlank())
                    .limit(3)
                    .reduce((a, b) -> a + "；" + b)
                    .orElse(""));
        }
        if (analysis.getTroubleshootingSteps() != null && !analysis.getTroubleshootingSteps().isEmpty()) {
            comment.append("\n排查步骤: ")
                    .append(String.join("；", analysis.getTroubleshootingSteps()));
        }
        if (analysis.getRerunScope() != null && !analysis.getRerunScope().isEmpty()) {
            comment.append("\n建议复测范围: ")
                    .append(String.join("；", analysis.getRerunScope()));
        }
        return comment.toString();
    }

    private TestFailureAnalysis analyzeFailure(GraphNode node, String testResultId, String failureMessage) {
        if (testFailureAnalysisAgent == null) {
            return null;
        }
        try {
            TestResult result = testResultId != null ? testResultRepository.selectById(testResultId) : null;
            TestCase testCase = result != null && result.getTestCaseId() != null
                    ? testCaseRepository.selectById(result.getTestCaseId())
                    : null;

            TestFailureAnalysisAgent.FailureContext context = new TestFailureAnalysisAgent.FailureContext();
            context.setProjectId(result != null && result.getProjectId() != null
                    ? result.getProjectId() : node.getProjectId());
            context.setCaseName(testCase != null ? testCase.getCaseName() : "");
            context.setTargetNode(node.getDisplayName() != null && !node.getDisplayName().isBlank()
                    ? node.getDisplayName() : node.getNodeName());
            context.setRequest(result != null ? result.getRequestData() : "");
            context.setResponse(result != null ? result.getResponseData() : "");
            context.setErrorMessage(failureMessage != null ? failureMessage
                    : result != null ? result.getErrorMessage() : "");
            context.setGraphPath("");
            context.setRecentTrace("");
            return testFailureAnalysisAgent.analyze(context);
        } catch (Exception e) {
            log.warn("AI test failure analysis failed for node {}: {}", node.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 计算总分 = base_confidence + verified_score 调整
     */
    private BigDecimal calculateTotalConfidence(GraphEdge edge) {
        BigDecimal base = edge.getConfidence() != null ? edge.getConfidence() : BigDecimal.ZERO;
        BigDecimal verified = edge.getVerifiedScore() != null ? edge.getVerifiedScore() : BigDecimal.ZERO;
        BigDecimal total = base.add(verified.multiply(new BigDecimal("0.2")));
        if (total.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        if (total.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return total;
    }

    /**
     * 根据一次测试执行的所有结果批量更新节点置信度
     * 遍历所有测试结果，根据测试通过/失败回写置信度到对应的图谱节点
     */
    @Transactional
    public void updateConfidenceByTestResults(String executionId) {
        List<TestResult> allResults = testResultRepository.findByExecutionId(executionId);
        log.info("Updating confidence for {} test results in execution {}", allResults.size(), executionId);

        for (TestResult result : allResults) {
            TestCase testCase = testCaseRepository.getById(result.getTestCaseId());
            if (testCase == null) {
                continue;
            }

            String targetNodeId = testCase.getTargetNodeId();
            String status = result.getResultStatus();

            if ("PASSED".equals(status)) {
                onTestPass(targetNodeId, null);
            } else if ("FAILED".equals(status) || "ERROR".equals(status)) {
                onTestFail(targetNodeId, null, result.getId(), result.getErrorMessage());
            }
        }

        log.info("Confidence update completed for execution {}", executionId);
    }
}
