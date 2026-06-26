package io.github.legacygraph.service;

import io.github.legacygraph.agent.GraphMergeAgent;
import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.GraphNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 图谱合并服务 - 依照详细设计文档的三阶段算法
 *
 * 算法流程：
 * 1. 候选生成 - 按 node_type + project_id 分桶，blocking + ANN 召回
 * 2. 特征打分 - name_score, semantic_score, struct_score, neighbor_score, evidence_score
 * 3. 决策与审核 - >=0.92 自动合并，0.75-0.92 人工审核，<0.75 拒绝
 */
@Slf4j
@Service
public class GraphMergeService {

    @Autowired
    private GraphNodeRepository nodeRepository;

    @Autowired
    private GraphMergeAgent graphMergeAgent;

    /**
     * 合并阈值常量 - 依照详细设计文档
     */
    private static final double AUTO_MERGE_THRESHOLD = 0.92;
    private static final double REVIEW_THRESHOLD = 0.75;

    /**
     * 权重 - 依照详细设计文档
     */
    private static final double NAME_WEIGHT = 0.30;
    private static final double SEMANTIC_WEIGHT = 0.25;
    private static final double STRUCT_WEIGHT = 0.20;
    private static final double NEIGHBOR_WEIGHT = 0.15;
    private static final double EVIDENCE_WEIGHT = 0.10;

    /**
     * 为项目中所有同类型节点生成合并候选对
     */
    public List<MergeCandidate> findMergeCandidates(String projectId, String nodeType) {
        List<GraphNode> nodes = nodeRepository.findByProjectIdAndNodeType(projectId, nodeType);
        List<MergeCandidate> candidates = new ArrayList<>();

        // 简单的两两比较（实际生产中可以优化为分块 blocking + 向量召回）
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                GraphNode a = nodes.get(i);
                GraphNode b = nodes.get(j);

                // 计算各项分数
                double nameScore = calculateNameScore(a.getNodeName(), b.getNodeName());
                double semanticScore = 0.5; // 占位：实际从向量计算余弦相似度
                double structScore = calculateStructScore(a, b);
                double neighborScore = 0.5; // 占位：实际从图结构计算邻居相似度
                double evidenceScore = calculateEvidenceScore(a, b);

                double totalScore =
                        NAME_WEIGHT * nameScore +
                        SEMANTIC_WEIGHT * semanticScore +
                        STRUCT_WEIGHT * structScore +
                        NEIGHBOR_WEIGHT * neighborScore +
                        EVIDENCE_WEIGHT * evidenceScore;

                if (totalScore >= REVIEW_THRESHOLD) {
                    MergeCandidate candidate = new MergeCandidate();
                    candidate.setNodeAId(a.getId());
                    candidate.setNodeBId(b.getId());
                    candidate.setNameScore(nameScore);
                    candidate.setSemanticScore(semanticScore);
                    candidate.setStructScore(structScore);
                    candidate.setNeighborScore(neighborScore);
                    candidate.setEvidenceScore(evidenceScore);
                    candidate.setTotalScore(totalScore);
                    candidates.add(candidate);
                }
            }
        }

        log.info("Found {} merge candidates for projectId={}, nodeType={}",
                candidates.size(), projectId, nodeType);
        return candidates;
    }

    /**
     * 对候选对进行 LLM 决策
     */
    public GraphMergeDecision decideMerge(String projectId, GraphNode a, GraphNode b,
                                          MergeCandidate candidate) {
        return graphMergeAgent.decideMerge(projectId, a, b,
                candidate.getNameScore(), candidate.getSemanticScore(),
                candidate.getStructScore(), candidate.getNeighborScore(),
                candidate.getEvidenceScore());
    }

    /**
     * 执行自动合并 - 将节点 B 合并到节点 A，保留 A，标记 B 为删除
     */
    @Transactional
    public void executeMerge(String projectId, String targetNodeId, String mergeNodeId) {
        GraphNode target = nodeRepository.selectById(targetNodeId);
        GraphNode merge = nodeRepository.selectById(mergeNodeId);

        // 合并别名
        // TODO: 合并别名、证据、属性...

        // 标记被合并节点删除
        nodeRepository.deleteById(mergeNodeId);

        log.info("Auto merged nodes: {} -> {}", mergeNodeId, targetNodeId);
    }

    /**
     * 计算名称相似度（简单 Jaccard 基于 token）
     */
    private double calculateNameScore(String nameA, String nameB) {
        if (nameA == null || nameB == null) return 0.0;
        if (nameA.equalsIgnoreCase(nameB)) return 1.0;

        // 简单分词比较
        List<String> tokensA = splitIntoTokens(nameA);
        List<String> tokensB = splitIntoTokens(nameB);

        int intersection = 0;
        for (String tA : tokensA) {
            for (String tB : tokensB) {
                if (tA.equalsIgnoreCase(tB)) {
                    intersection++;
                    break;
                }
            }
        }

        int union = tokensA.size() + tokensB.size() - intersection;
        return (double) intersection / union;
    }

    /**
     * 计算结构相似度（基于属性/参数重叠）
     */
    private double calculateStructScore(GraphNode a, GraphNode b) {
        // 如果 properties 为 null，返回基础分
        if (a.getProperties() == null || b.getProperties() == null) {
            return 0.5;
        }
        // 简化实现：实际应该解析 JSON 比较属性重叠
        return 0.5;
    }

    /**
     * 计算证据重叠相似度
     */
    private double calculateEvidenceScore(GraphNode a, GraphNode b) {
        // 如果两者都没有证据，返回基础分
        if (a.getEvidenceIds() == null && b.getEvidenceIds() == null) {
            return 0.5;
        }
        // 简化实现：实际解析 evidenceIds 计算重叠
        return 0.5;
    }

    private List<String> splitIntoTokens(String s) {
        // 驼峰、下划线、空格分词
        List<String> tokens = new ArrayList<>();
        String[] parts = s.split("[_\\s-]+");
        for (String part : parts) {
            // 驼峰拆分
            for (String camelPart : splitCamelCase(part)) {
                if (camelPart.length() >= 2) {
                    tokens.add(camelPart.toLowerCase());
                }
            }
        }
        return tokens;
    }

    private List<String> splitCamelCase(String s) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 1; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) {
                parts.add(s.substring(start, i));
                start = i;
            }
        }
        if (start < s.length()) {
            parts.add(s.substring(start));
        }
        return parts;
    }

    /**
     * 合并候选DTO
     */
    @lombok.Data
    public static class MergeCandidate {
        private String nodeAId;
        private String nodeBId;
        private double nameScore;
        private double semanticScore;
        private double structScore;
        private double neighborScore;
        private double evidenceScore;
        private double totalScore;
    }
}
