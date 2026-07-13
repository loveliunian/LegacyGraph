package io.github.legacygraph.service.graph;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.service.similarity.SemanticSimilarityCalculator;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 跨类型候选桥接服务（graph-merge-optimization-plan.md 改进⑤）。
 * <p>
 * 业务域节点常与 BusinessProcess / Feature / Page 概念重叠
 * （"用户中心" vs "用户管理流程" vs "用户列表页"）。
 * 本服务在跨类型节点间检测疑似同义关系，输出 {@code POSSIBLE_SAME_AS} 边候选，
 * 由前端可视化让人工裁决，避免误并。
 * </p>
 *
 * <h3>检测条件（同时满足）</h3>
 * <ol>
 *   <li>节点类型对在 {(BusinessDomain, BusinessProcess), (BusinessDomain, Feature), (BusinessProcess, Feature)} 内</li>
 *   <li>共享 evidenceIds ≥ 2，或 aliasNames 交集 ≥ 1</li>
 *   <li>语义相似度 > 0.7</li>
 * </ol>
 *
 * <p>不直接 AUTO_MERGE，仅产出候选，由人工/LLM 通过 POSSIBLE_SAME_AS 边裁决。</p>
 */
@Slf4j
@Service
public class CrossTypeBlockingService {

    /** 参与跨类型桥接的节点类型对 */
    private static final List<String[]> CROSS_TYPE_PAIRS = Arrays.asList(
            new String[]{"BusinessDomain", "BusinessProcess"},
            new String[]{"BusinessDomain", "Feature"},
            new String[]{"BusinessProcess", "Feature"},
            new String[]{"BusinessDomain", "Page"},
            new String[]{"BusinessProcess", "Page"}
    );

    /** 共享证据阈值 */
    private static final int MIN_SHARED_EVIDENCE = 2;

    /** 语义相似度阈值 */
    private static final double MIN_SEMANTIC_SIMILARITY = 0.7;

    private final Neo4jGraphDao neo4jGraphDao;
    private final SemanticSimilarityCalculator semanticSimilarityCalculator;
    private final NodeBlockingService nodeBlockingService;

    public CrossTypeBlockingService(Neo4jGraphDao neo4jGraphDao,
                                     SemanticSimilarityCalculator semanticSimilarityCalculator,
                                     NodeBlockingService nodeBlockingService) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.semanticSimilarityCalculator = semanticSimilarityCalculator;
        this.nodeBlockingService = nodeBlockingService;
    }

    /**
     * 跨类型候选对（POSSIBLE_SAME_AS 边建议）。
     */
    @Data
    public static class CrossTypeCandidate {
        private String nodeAId;
        private String nodeBId;
        private String nodeAType;
        private String nodeBType;
        private double semanticScore;
        private int sharedEvidenceCount;
        private int sharedAliasCount;
        /** 建议理由 */
        private String reason;
    }

    /**
     * 查找跨类型疑似同义候选对。
     * <p>仅在指定类型对之间两两比较，满足条件（共享证据/别名 + 语义相似度）的产出候选。</p>
     *
     * @param projectId 项目 ID
     * @return 跨类型候选列表（按语义相似度降序）
     */
    public List<CrossTypeCandidate> findCrossTypeCandidates(String projectId) {
        List<CrossTypeCandidate> candidates = new ArrayList<>();

        // 按类型加载节点（每类限 500，避免 O(n²) 爆炸）
        Map<String, List<GraphNode>> nodesByType = new HashMap<>();
        Set<String> allTypes = new HashSet<>();
        for (String[] pair : CROSS_TYPE_PAIRS) {
            allTypes.addAll(Arrays.asList(pair));
        }
        for (String type : allTypes) {
            List<GraphNode> nodes = neo4jGraphDao.queryNodes(
                    projectId, null, type, null, null, null, 500);
            nodes = nodes.stream()
                    .filter(n -> n.getDeleted() == null || n.getDeleted() == 0)
                    .toList();
            nodesByType.put(type, nodes);
        }

        // 逐类型对比较
        for (String[] pair : CROSS_TYPE_PAIRS) {
            List<GraphNode> listA = nodesByType.getOrDefault(pair[0], List.of());
            List<GraphNode> listB = nodesByType.getOrDefault(pair[1], List.of());
            if (listA.isEmpty() || listB.isEmpty()) continue;

            for (GraphNode nodeA : listA) {
                for (GraphNode nodeB : listB) {
                    if (Objects.equals(nodeA.getId(), nodeB.getId())) continue;

                    CrossTypeCandidate candidate = evaluateCrossTypePair(nodeA, nodeB);
                    if (candidate != null) {
                        candidates.add(candidate);
                    }
                }
            }
        }

        // 按语义相似度降序
        candidates.sort((a, b) -> Double.compare(b.getSemanticScore(), a.getSemanticScore()));
        log.info("Found {} cross-type candidates for project={}", candidates.size(), projectId);
        return candidates;
    }

    /**
     * 评估一对跨类型节点是否构成 POSSIBLE_SAME_AS 候选。
     *
     * @return 候选对象，不满足条件返回 null
     */
    private CrossTypeCandidate evaluateCrossTypePair(GraphNode nodeA, GraphNode nodeB) {
        // 1. 共享证据数
        int sharedEvidence = countSharedEvidence(nodeA, nodeB);

        // 2. 共享别名数
        int sharedAlias = countSharedAliases(nodeA, nodeB);

        // 快速过滤：无共享证据且无共享别名 → 跳过（避免无谓的 embedding 调用）
        if (sharedEvidence < MIN_SHARED_EVIDENCE && sharedAlias < 1) {
            return null;
        }

        // 3. 语义相似度
        double semanticScore = semanticSimilarityCalculator.compute(nodeA, nodeB);
        if (semanticScore < MIN_SEMANTIC_SIMILARITY) {
            return null;
        }

        // 构建候选
        CrossTypeCandidate candidate = new CrossTypeCandidate();
        candidate.setNodeAId(nodeA.getId());
        candidate.setNodeBId(nodeB.getId());
        candidate.setNodeAType(nodeA.getNodeType());
        candidate.setNodeBType(nodeB.getNodeType());
        candidate.setSemanticScore(semanticScore);
        candidate.setSharedEvidenceCount(sharedEvidence);
        candidate.setSharedAliasCount(sharedAlias);
        candidate.setReason(String.format(
                "跨类型疑似同义: %s(%s) ↔ %s(%s), 语义=%.2f, 共享证据=%d, 共享别名=%d",
                nodeA.getNodeName(), nodeA.getNodeType(),
                nodeB.getNodeName(), nodeB.getNodeType(),
                semanticScore, sharedEvidence, sharedAlias));
        return candidate;
    }

    /**
     * 统计两个节点共享的 evidenceIds 数量。
     */
    private int countSharedEvidence(GraphNode nodeA, GraphNode nodeB) {
        String evA = nodeA.getEvidenceIds();
        String evB = nodeB.getEvidenceIds();
        if (evA == null || evA.isBlank() || evB == null || evB.isBlank()) {
            return 0;
        }
        Set<String> idsA = new HashSet<>(Arrays.asList(evA.split(",")));
        Set<String> idsB = new HashSet<>(Arrays.asList(evB.split(",")));
        idsA.retainAll(idsB);
        return idsA.size();
    }

    /**
     * 统计两个节点共享的别名数量。
     */
    private int countSharedAliases(GraphNode nodeA, GraphNode nodeB) {
        List<String> aliasesA = nodeBlockingService.parseAliasNames(nodeA.getAliasNames());
        List<String> aliasesB = nodeBlockingService.parseAliasNames(nodeB.getAliasNames());
        if (aliasesA.isEmpty() || aliasesB.isEmpty()) {
            return 0;
        }
        Set<String> setA = new HashSet<>();
        for (String a : aliasesA) setA.add(a.toLowerCase().trim());
        Set<String> intersection = new HashSet<>(setA);
        Set<String> setB = new HashSet<>();
        for (String b : aliasesB) setB.add(b.toLowerCase().trim());
        intersection.retainAll(setB);
        return intersection.size();
    }
}
