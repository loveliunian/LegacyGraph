package io.github.legacygraph.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.GraphMergeAgent;
import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.repository.VectorDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private GraphEdgeRepository edgeRepository;

    @Autowired
    private GraphMergeAgent graphMergeAgent;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private VectorDocumentRepository vectorDocumentRepository;

    @Autowired
    private ObjectMapper objectMapper;

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

                // 跳过已删除节点
                if (a.getDeleted() != null && a.getDeleted() == 1) continue;
                if (b.getDeleted() != null && b.getDeleted() == 1) continue;

                // 计算各项分数
                double nameScore = calculateNameScore(a.getNodeName(), b.getNodeName());
                double semanticScore = calculateSemanticScore(a, b);
                double structScore = calculateStructScore(a, b);
                double neighborScore = calculateNeighborScore(a, b);
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
     *
     * 合并策略：
     * - 别名：合并别名列表，去重
     * - 证据：合并证据ID列表，去重
     * - 属性：合并properties JSON，冲突保留目标节点已有属性
     * - 关系：将所有指向B的关系更新为指向A
     * - 置信度：取目标节点和合并节点置信度的最大值
     */
    @Transactional
    public void executeMerge(String projectId, String targetNodeId, String mergeNodeId) {
        GraphNode target = nodeRepository.selectById(targetNodeId);
        GraphNode merge = nodeRepository.selectById(mergeNodeId);

        if (target == null || merge == null) {
            throw new IllegalArgumentException("Node not found: " + targetNodeId + " or " + mergeNodeId);
        }

        // 1. 合并别名
        Set<String> targetAliases = parseJsonSet(target.getAliasNames());
        Set<String> mergeAliases = parseJsonSet(merge.getAliasNames());
        if (target.getNodeName() != null) {
            targetAliases.add(target.getNodeName());
        }
        if (merge.getNodeName() != null) {
            targetAliases.add(merge.getNodeName());
        }
        targetAliases.addAll(mergeAliases);
        try {
            target.setAliasNames(objectMapper.writeValueAsString(targetAliases));
        } catch (Exception e) {
            log.warn("Failed to serialize aliases", e);
        }

        // 2. 合并证据ID
        Set<String> targetEvidences = parseJsonSet(target.getEvidenceIds());
        Set<String> mergeEvidences = parseJsonSet(merge.getEvidenceIds());
        targetEvidences.addAll(mergeEvidences);
        try {
            target.setEvidenceIds(objectMapper.writeValueAsString(targetEvidences));
        } catch (Exception e) {
            log.warn("Failed to serialize evidenceIds", e);
        }

        // 3. 合并置信度 - 取最大值
        BigDecimal maxConfidence = target.getConfidence().max(merge.getConfidence());
        target.setConfidence(maxConfidence);

        // 4. 如果目标状态是 pending，合并后置信度高则升级
        if ("PENDING_CONFIRM".equals(target.getStatus()) && maxConfidence.compareTo(BigDecimal.valueOf(0.8)) >= 0) {
            target.setStatus("CONFIRMED");
        }

        // 5. 合并描述 - 追加描述
        if (target.getDescription() == null && merge.getDescription() != null) {
            target.setDescription(merge.getDescription());
        } else if (merge.getDescription() != null && !merge.getDescription().isBlank()) {
            target.setDescription(target.getDescription() + "\n---\n" + merge.getDescription());
        }

        // 6. 更新所有指向B的关系，改为指向A
        // 更新 from B -> X 为 from A -> X
        edgeRepository.updateFromNodeId(mergeNodeId, targetNodeId, merge.getVersionId());
        // 更新 X -> B 为 X -> A
        edgeRepository.updateToNodeId(mergeNodeId, targetNodeId, merge.getVersionId());

        // 7. 标记被合并节点删除
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
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    /**
     * 计算语义相似度 - 使用向量化余弦相似度
     */
    private double calculateSemanticScore(GraphNode a, GraphNode b) {
        // 如果节点已经有语义向量引用，使用向量
        // 如果没有，对节点名称和描述进行向量化
        String textA = (a.getNodeName() != null ? a.getNodeName() : "") +
                " " + (a.getDescription() != null ? a.getDescription() : "");
        String textB = (b.getNodeName() != null ? b.getNodeName() : "") +
                " " + (b.getDescription() != null ? b.getDescription() : "");

        if (textA.isBlank() || textB.isBlank()) return 0.5;

        // 向量化
        EmbeddingResponse embeddingA = embeddingModel.embed(textA.trim());
        List<Double> embeddingAList = embeddingA.getResults().get(0).getOutput();

        EmbeddingResponse embeddingB = embeddingModel.embed(textB.trim());
        List<Double> embeddingBList = embeddingB.getResults().get(0).getOutput();

        // 计算余弦相似度
        return cosineSimilarity(embeddingAList, embeddingBList);
    }

    /**
     * 计算结构相似度（基于属性/参数重叠）
     */
    private double calculateStructScore(GraphNode a, GraphNode b) {
        // 如果 properties 为 null，返回基础分
        if (a.getProperties() == null || b.getProperties() == null ||
                a.getProperties().isBlank() || b.getProperties().isBlank()) {
            return 0.5;
        }

        try {
            Set<String> propsA = objectMapper.readValue(a.getProperties(), new TypeReference<Set<String>>() {});
            Set<String> propsB = objectMapper.readValue(b.getProperties(), new TypeReference<Set<String>>() {});

            int intersection = 0;
            for (String prop : propsA) {
                if (propsB.contains(prop)) {
                    intersection++;
                }
            }

            int union = propsA.size() + propsB.size() - intersection;
            return union == 0 ? 0.5 : (double) intersection / union;
        } catch (Exception e) {
            // 如果解析失败，返回基础分
            return 0.5;
        }
    }

    /**
     * 计算邻居相似度 - 基于共同邻居占比
     */
    private double calculateNeighborScore(GraphNode a, GraphNode b) {
        // 获取所有相邻节点ID
        Set<String> neighborsA = new HashSet<>();
        List<String> fromNeighbors = edgeRepository.findNeighborNodeIds(a.getId(), a.getVersionId());
        neighborsA.addAll(fromNeighbors);

        Set<String> neighborsB = new HashSet<>();
        List<String> fromNeighborsB = edgeRepository.findNeighborNodeIds(b.getId(), b.getVersionId());
        neighborsB.addAll(fromNeighborsB);

        if (neighborsA.isEmpty() && neighborsB.isEmpty()) {
            return 0.5;
        }

        // 计算 Jaccard 相似度
        int intersection = 0;
        for (String n : neighborsA) {
            if (neighborsB.contains(n)) {
                intersection++;
            }
        }

        int union = neighborsA.size() + neighborsB.size() - intersection;
        return (double) intersection / union;
    }

    /**
     * 计算证据重叠相似度
     */
    private double calculateEvidenceScore(GraphNode a, GraphNode b) {
        Set<String> evidenceA = parseJsonSet(a.getEvidenceIds());
        Set<String> evidenceB = parseJsonSet(b.getEvidenceIds());

        if (evidenceA.isEmpty() && evidenceB.isEmpty()) {
            return 0.5;
        }

        int intersection = 0;
        for (String e : evidenceA) {
            if (evidenceB.contains(e)) {
                intersection++;
            }
        }

        int union = evidenceA.size() + evidenceB.size() - intersection;
        return union == 0 ? 0.5 : (double) intersection / union;
    }

    /**
     * 余弦相似度计算
     */
    private double cosineSimilarity(List<Double> a, List<Double> b) {
        double dot = 0.0;
        double magA = 0.0;
        double magB = 0.0;
        int minLen = Math.min(a.size(), b.size());
        for (int i = 0; i < minLen; i++) {
            double ai = a.get(i);
            double bi = b.get(i);
            dot += ai * bi;
            magA += ai * ai;
            magB += bi * bi;
        }
        if (magA == 0 || magB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(magA) * Math.sqrt(magB));
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
     * 解析 JSON 格式的集合，返回 Set
     */
    private Set<String> parseJsonSet(String json) {
        Set<String> result = new HashSet<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            result.addAll(list);
        } catch (Exception e) {
            // 如果解析失败，尝试作为普通字符串
            if (!json.equals("[]") && !json.equals("{}")) {
                result.add(json);
            }
        }
        return result;
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
