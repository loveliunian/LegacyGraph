package io.github.legacygraph.service;

import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.dao.Neo4jGraphDao;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 图谱合并服务
 * 负责节点去重、别名归一、边去噪、关系补全
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphMergeService {

    private final Neo4jGraphDao neo4jGraphDao;

    /**
     * 合并候选对
     */
    @Data
    public static class MergeCandidate {
        private String nodeAId;
        private String nodeBId;
        private double similarityScore;
        private double nameScore;
        private double semanticScore;
    }

    /**
     * 查找合并候选对
     * 遍历同一项目、同类型的节点，计算相似度，返回可能重复的候选对
     */
    public List<MergeCandidate> findMergeCandidates(String projectId, String nodeType) {
        List<GraphNode> nodes = neo4jGraphDao.queryNodes(
                projectId, null, nodeType, null, null, null, Integer.MAX_VALUE);
        List<MergeCandidate> candidates = new ArrayList<>();

        // 过滤已删除的节点
        List<GraphNode> activeNodes = nodes.stream()
                .filter(n -> n.getDeleted() == null || n.getDeleted() == 0)
                .toList();

        // 两两比较节点
        for (int i = 0; i < activeNodes.size(); i++) {
            for (int j = i + 1; j < activeNodes.size(); j++) {
                GraphNode nodeA = activeNodes.get(i);
                GraphNode nodeB = activeNodes.get(j);

                MergeCandidate candidate = new MergeCandidate();
                candidate.setNodeAId(nodeA.getId());
                candidate.setNodeBId(nodeB.getId());

                double nameScore = calculateNameScore(nodeA.getNodeName(), nodeB.getNodeName());
                candidate.setNameScore(nameScore);

                // 简化实现：用名称分数作为相似度的主要依据
                double similarityScore = nameScore * 0.7 + 0.3 * calculateSemanticScore(nodeA, nodeB);
                candidate.setSimilarityScore(similarityScore);
                candidate.setSemanticScore(0.0);

                // 只保留有一定相似度的候选对
                if (similarityScore > 0.1) {
                    candidates.add(candidate);
                }
            }
        }

        return candidates;
    }

    /**
     * 计算两个名称之间的相似度
     */
    public double calculateNameScore(String name1, String name2) {
        if (name1 == null || name2 == null || name1.isEmpty() || name2.isEmpty()) {
            return 0.0;
        }

        String lower1 = name1.toLowerCase().trim();
        String lower2 = name2.toLowerCase().trim();

        if (lower1.equals(lower2)) {
            return 1.0;
        }

        // 最长公共子序列 (LCS) 相似度
        int lcsLength = longestCommonSubsequence(lower1, lower2);
        double lcsScore = (2.0 * lcsLength) / (lower1.length() + lower2.length());

        // 前缀匹配加分
        int prefixLen = 0;
        int minLen = Math.min(lower1.length(), lower2.length());
        for (int i = 0; i < minLen; i++) {
            if (lower1.charAt(i) == lower2.charAt(i)) {
                prefixLen++;
            } else {
                break;
            }
        }
        double prefixScore = (double) prefixLen / minLen;

        // 后缀匹配加分
        int suffixLen = 0;
        for (int i = 0; i < minLen; i++) {
            if (lower1.charAt(lower1.length() - 1 - i) == lower2.charAt(lower2.length() - 1 - i)) {
                suffixLen++;
            } else {
                break;
            }
        }
        double suffixScore = (double) suffixLen / minLen;

        // 综合评分
        return lcsScore * 0.6 + prefixScore * 0.25 + suffixScore * 0.15;
    }

    /**
     * 计算两个节点的语义相似度（简化实现）
     */
    private double calculateSemanticScore(GraphNode nodeA, GraphNode nodeB) {
        // 简化实现：基于描述和属性文本的相似度
        String descA = nodeA.getDescription() != null ? nodeA.getDescription() : "";
        String descB = nodeB.getDescription() != null ? nodeB.getDescription() : "";
        if (descA.isEmpty() && descB.isEmpty()) {
            return 0.0;
        }
        return calculateNameScore(descA, descB) * 0.5;
    }

    /**
     * 最长公共子序列长度
     */
    private int longestCommonSubsequence(String a, String b) {
        int m = a.length(), n = b.length();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp[m][n];
    }

    /**
     * 决策两个节点是否应该合并
     * 使用相似度分数做出决策（简化实现，后续可对接 LLM）
     */
    public GraphMergeDecision decideMerge(String projectId, GraphNode nodeA, GraphNode nodeB,
                                           MergeCandidate candidate) {
        double nameScore = calculateNameScore(nodeA.getNodeName(), nodeB.getNodeName());

        GraphMergeDecision decision = new GraphMergeDecision();
        decision.setCandidateA(nodeA.getId());
        decision.setCandidateB(nodeB.getId());
        decision.setScore(java.math.BigDecimal.valueOf(nameScore));

        if (nameScore >= 0.85) {
            decision.setDecision(GraphMergeDecision.Decision.AUTO_MERGE);
        } else if (nameScore >= 0.5) {
            decision.setDecision(GraphMergeDecision.Decision.REVIEW);
        } else {
            decision.setDecision(GraphMergeDecision.Decision.REJECT);
        }

        List<String> reasons = new ArrayList<>();
        reasons.add("Name similarity: " + String.format("%.2f", nameScore));
        if (nameScore >= 0.85) {
            reasons.add("High name similarity, auto-merge recommended");
        } else if (nameScore >= 0.5) {
            reasons.add("Moderate name similarity, manual review needed");
        } else {
            reasons.add("Low name similarity, no merge needed");
        }
        decision.setReasons(reasons);

        return decision;
    }

    /**
     * 执行合并操作
     * 将 mergeNode 合并到 targetNode，然后标记 mergeNode 为删除
     */
    @Transactional
    public void executeMerge(String projectId, String targetNodeId, String mergeNodeId) {
        // 更新所有指向合并节点的边，改为指向目标节点
        neo4jGraphDao.updateEdgeFromNode(mergeNodeId, targetNodeId, projectId);
        neo4jGraphDao.updateEdgeToNode(mergeNodeId, targetNodeId, projectId);

        // 在 Neo4j 中物理删除合并节点（替代 PG 的软删除）
        GraphNode mergeNode = neo4jGraphDao.findNodeById(mergeNodeId).orElse(null);
        if (mergeNode != null) {
            neo4jGraphDao.deleteNode(projectId, mergeNode.getVersionId(), mergeNodeId);
        }

        log.info("Merged node {} into {} in project {}", mergeNodeId, targetNodeId, projectId);
    }
}
