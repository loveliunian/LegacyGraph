package io.github.legacygraph.service;

import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

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

    @Autowired
    private GraphNodeRepository nodeRepository;

    @Autowired
    private GraphEdgeRepository edgeRepository;

    private static final BigDecimal PASS_EDGE_INCREMENT = new BigDecimal("0.10");
    private static final BigDecimal PASS_NODE_INCREMENT = new BigDecimal("0.05");
    private static final BigDecimal FAIL_EDGE_DECREMENT = new BigDecimal("0.20");
    private static final BigDecimal VERIFIED_THRESHOLD = new BigDecimal("0.85");

    /**
     * 回写测试通过结果
     */
    @Transactional
    public void onTestPass(String nodeId, String edgeId) {
        if (nodeId != null) {
            GraphNode node = nodeRepository.selectById(nodeId);
            if (node != null && node.getVerifiedScore() != null) {
                node.setVerifiedScore(node.getVerifiedScore().add(PASS_NODE_INCREMENT));
                // 不超过 1.0000
                if (node.getVerifiedScore().compareTo(BigDecimal.ONE) > 0) {
                    node.setVerifiedScore(BigDecimal.ONE);
                }
                nodeRepository.updateById(node);
            }
        }

        if (edgeId != null) {
            GraphEdge edge = edgeRepository.selectById(edgeId);
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
                edgeRepository.updateById(edge);
            }
        }

        log.info("Test PASS result written: nodeId={}, edgeId={}", nodeId, edgeId);
    }

    /**
     * 回写测试失败结果
     */
    @Transactional
    public void onTestFail(String nodeId, String edgeId) {
        if (edgeId != null) {
            GraphEdge edge = edgeRepository.selectById(edgeId);
            if (edge != null && edge.getVerifiedScore() != null) {
                edge.setVerifiedScore(edge.getVerifiedScore().subtract(FAIL_EDGE_DECREMENT));
                // 不低于 0
                if (edge.getVerifiedScore().compareTo(BigDecimal.ZERO) < 0) {
                    edge.setVerifiedScore(BigDecimal.ZERO);
                }
                edge.setRelationStatus("review");
                edgeRepository.updateById(edge);
            }
        }

        // TODO: 创建 review task 记录失败上下文

        log.info("Test FAIL result written: nodeId={}, edgeId={}", nodeId, edgeId);
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
}
