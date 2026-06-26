package io.github.legacygraph.agent;

import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.llm.LlmGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * GraphMergeAgent - 图谱合并决策
 *
 * 职责：
 * - 节点去重
 * - 别名归一
 * - 边去噪
 * - 关系补全
 * - 置信度计算
 */
@Slf4j
@Service
public class GraphMergeAgent {

    @Autowired
    private LlmGateway llmGateway;

    /**
     * 决策两个节点是否应该合并
     */
    public GraphMergeDecision decideMerge(String projectId, GraphNode nodeA, GraphNode nodeB,
                                           double nameScore, double semanticScore,
                                           double structScore, double neighborScore,
                                           double evidenceScore) {

        StringBuilder nodeAInfo = new StringBuilder();
        nodeAInfo.append("Key: ").append(nodeA.getNodeKey()).append("\n");
        nodeAInfo.append("Name: ").append(nodeA.getNodeName()).append("\n");
        nodeAInfo.append("Type: ").append(nodeA.getNodeType()).append("\n");
        nodeAInfo.append("Description: ").append(nodeA.getDescription() != null ? nodeA.getDescription() : "").append("\n");
        nodeAInfo.append("Properties: ").append(nodeA.getProperties() != null ? nodeA.getProperties() : "").append("\n");

        StringBuilder nodeBInfo = new StringBuilder();
        nodeBInfo.append("Key: ").append(nodeB.getNodeKey()).append("\n");
        nodeBInfo.append("Name: ").append(nodeB.getNodeName()).append("\n");
        nodeBInfo.append("Type: ").append(nodeB.getNodeType()).append("\n");
        nodeBInfo.append("Description: ").append(nodeB.getDescription() != null ? nodeB.getDescription() : "").append("\n");
        nodeBInfo.append("Properties: ").append(nodeB.getProperties() != null ? nodeB.getProperties() : "").append("\n");

        Map<String, String> variables = new HashMap<>();
        variables.put("candidateAKey", nodeA.getNodeKey());
        variables.put("candidateAInfo", nodeAInfo.toString());
        variables.put("candidateBKey", nodeB.getNodeKey());
        variables.put("candidateBInfo", nodeBInfo.toString());
        variables.put("nameScore", String.valueOf(nameScore));
        variables.put("semanticScore", String.valueOf(semanticScore));
        variables.put("structScore", String.valueOf(structScore));
        variables.put("neighborScore", String.valueOf(neighborScore));
        variables.put("evidenceScore", String.valueOf(evidenceScore));

        return llmGateway.callWithTemplate(projectId, "graph-merge-decision",
                variables, GraphMergeDecision.class);
    }

    /**
     * 计算最终置信度 - 依照详细设计文档中的公式
     *
     * final_confidence = clamp(0, 1,
     *   0.50 * support +
     *   0.15 * semantic_score +
     *   0.15 * struct_score +
     *   0.10 * neighbor_score +
     *   0.05 * runtime_verified +
     *   0.05 * human_review -
     *   0.35 * conflict
     * )
     */
    public BigDecimal calculateFinalConfidence(double support, double semanticScore,
                                                 double structScore, double neighborScore,
                                                 boolean runtimeVerified, boolean humanReviewed,
                                                 double conflict) {
        double result =
                0.50 * support +
                0.15 * semanticScore +
                0.15 * structScore +
                0.10 * neighborScore +
                0.05 * (runtimeVerified ? 1.0 : 0.0) +
                0.05 * (humanReviewed ? 1.0 : 0.0) -
                0.35 * conflict;

        // clamp to [0, 1]
        result = Math.max(0, Math.min(1, result));

        // 保留 4 位小数匹配 NUMERIC(5,4)
        return BigDecimal.valueOf(result).setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
