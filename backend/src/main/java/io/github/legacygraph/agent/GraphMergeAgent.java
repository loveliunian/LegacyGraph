package io.github.legacygraph.agent;

import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.llm.LlmGateway;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * GraphMergeAgent - 图谱合并决策。
 * Phase 3-1: {@link #decideMergeFromEnvelope(AgentEnvelope)} 合约入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphMergeAgent {

    private final LlmGateway llmGateway;

    @Data
    public static class MergeInput {
        private String nodeAKey, nodeAInfo, nodeBKey, nodeBInfo;
        private double nameScore, semanticScore, structScore, neighborScore, evidenceScore;
    }

    /** Phase 3-1: AgentEnvelope 合约入口 */
    public GraphMergeDecision decideMergeFromEnvelope(AgentEnvelope<MergeInput> env) {
        MergeInput input = env.getInput();
        if (input == null) return null;
        Map<String, String> variables = new HashMap<>();
        variables.put("candidateAKey", input.nodeAKey);
        variables.put("candidateAInfo", input.nodeAInfo);
        variables.put("candidateBKey", input.nodeBKey);
        variables.put("candidateBInfo", input.nodeBInfo);
        variables.put("nameScore", String.valueOf(input.nameScore));
        variables.put("semanticScore", String.valueOf(input.semanticScore));
        variables.put("structScore", String.valueOf(input.structScore));
        variables.put("neighborScore", String.valueOf(input.neighborScore));
        variables.put("evidenceScore", String.valueOf(input.evidenceScore));
        return llmGateway.callWithEnvelope(env, "graph-merge-decision",
                variables, GraphMergeDecision.class);
    }

    public GraphMergeDecision decideMerge(String projectId, GraphNode nodeA, GraphNode nodeB,
                                           double nameScore, double semanticScore,
                                           double structScore, double neighborScore,
                                           double evidenceScore) {
        Map<String, String> variables = new HashMap<>();
        variables.put("candidateAKey", nodeA.getNodeKey());
        variables.put("candidateAInfo", nodeInfo(nodeA));
        variables.put("candidateBKey", nodeB.getNodeKey());
        variables.put("candidateBInfo", nodeInfo(nodeB));
        variables.put("nameScore", String.valueOf(nameScore));
        variables.put("semanticScore", String.valueOf(semanticScore));
        variables.put("structScore", String.valueOf(structScore));
        variables.put("neighborScore", String.valueOf(neighborScore));
        variables.put("evidenceScore", String.valueOf(evidenceScore));
        return llmGateway.callWithTemplate(projectId, "graph-merge-decision",
                variables, GraphMergeDecision.class);
    }

    private String nodeInfo(GraphNode n) {
        return "Key: " + n.getNodeKey() + "\nName: " + n.getNodeName()
                + "\nType: " + n.getNodeType() + "\nDescription: "
                + (n.getDescription() != null ? n.getDescription() : "")
                + "\nProperties: " + (n.getProperties() != null ? n.getProperties() : "") + "\n";
    }

    public BigDecimal calculateFinalConfidence(double support, double semanticScore,
                                                 double structScore, double neighborScore,
                                                 boolean runtimeVerified, boolean humanReviewed,
                                                 double conflict) {
        double result = 0.50 * support + 0.15 * semanticScore + 0.15 * structScore
                + 0.10 * neighborScore + 0.05 * (runtimeVerified ? 1.0 : 0.0)
                + 0.05 * (humanReviewed ? 1.0 : 0.0) - 0.35 * conflict;
        result = Math.max(0, Math.min(1, result));
        return BigDecimal.valueOf(result).setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
