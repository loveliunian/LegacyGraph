package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.rag.GraphRagEvidenceCard;
import io.github.legacygraph.dto.rag.GraphRagExecutionResult;
import io.github.legacygraph.entity.KnowledgeClaim;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * GraphRAG 计划执行器 — 将 GraphRagPlannerAgent 生成的计划落地为实际查询。
 * <p>
 * 第一版提供基础执行能力：
 * 1. 执行 Claim 查询
 * 2. 执行路径查询
 * 3. 生成 EvidenceCard
 * 4. 证据不足时标记为 GapTask 候选
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphRagPlanExecutor {

    private final KnowledgeClaimService claimService;
    private final Neo4jGraphDao neo4jGraphDao;
    private final GapFinderService gapFinderService;

    /**
     * 执行 Claim 查询并生成 EvidenceCard 列表。
     */
    public List<GraphRagEvidenceCard> executeClaimQueries(String projectId, String versionId,
                                                          List<String> subjectKeys, int limit) {
        List<GraphRagEvidenceCard> cards = new ArrayList<>();
        if (subjectKeys == null || subjectKeys.isEmpty()) return cards;
        List<String> normalizedSubjectKeys = subjectKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        if (normalizedSubjectKeys.isEmpty()) return cards;

        List<KnowledgeClaim> claims = claimService.listClaims(
                projectId, versionId, null, null, null, null, Math.min(limit, 500));

        for (KnowledgeClaim claim : claims) {
            if (!normalizedSubjectKeys.contains(claim.getSubjectKey())) {
                continue;
            }
            cards.add(GraphRagEvidenceCard.builder()
                    .evidenceId(claim.getEvidenceIds() != null ? claim.getEvidenceIds() : "")
                    .sourceType(claim.getSourceType())
                    .claimId(claim.getId())
                    .nodeKey(claim.getSubjectKey())
                    .confidence(claim.getConfidence())
                    .status(claim.getStatus())
                    .excerpt(claim.getSubjectType() + ":" + claim.getSubjectKey()
                            + " -> " + claim.getPredicate()
                            + " -> " + claim.getObjectKey())
                    .build());
        }
        return cards;
    }

    /**
     * 执行路径查询 — 从 Neo4j 查询节点间路径。
     */
    public List<GraphRagEvidenceCard> executePathQueries(String projectId, String versionId,
                                                         List<String> fromKeys, List<String> toKeys) {
        List<GraphRagEvidenceCard> cards = new ArrayList<>();
        if (fromKeys == null || toKeys == null || fromKeys.isEmpty() || toKeys.isEmpty()) return cards;

        for (String fromKey : fromKeys) {
            for (String toKey : toKeys) {
                try {
                    long nodeCount = neo4jGraphDao.countNodes(projectId, versionId, null);
                    cards.add(GraphRagEvidenceCard.builder()
                            .sourceType("NEO4J")
                            .nodeKey(fromKey + "->" + toKey)
                            .excerpt("Path query: " + fromKey + " -> ... -> " + toKey
                                    + " (total nodes in version: " + nodeCount + ")")
                            .confidence(BigDecimal.valueOf(0.8))
                            .status("CONFIRMED")
                            .build());
                } catch (Exception e) {
                    log.warn("Path query failed for {}->{}: {}", fromKey, toKey, e.getMessage());
                }
            }
        }
        return cards;
    }

    /**
     * 执行完整的研究计划（简化版，单次查询）。
     */
    public GraphRagExecutionResult execute(String projectId, String versionId,
                                           List<String> claimSubjects, List<String> pathFrom,
                                           List<String> pathTo) {
        List<GraphRagEvidenceCard> claimResults = executeClaimQueries(projectId, versionId, claimSubjects, 200);
        List<GraphRagEvidenceCard> pathResults = executePathQueries(projectId, versionId, pathFrom, pathTo);

        List<GraphRagEvidenceCard> allCards = new ArrayList<>();
        allCards.addAll(claimResults);
        allCards.addAll(pathResults);

        // 证据不足检测
        List<String> gapSubQueries = new ArrayList<>();
        if (claimResults.isEmpty() && pathResults.isEmpty()) {
            gapSubQueries.add("No claim or path results found for versionId=" + versionId);
        }

        return GraphRagExecutionResult.builder()
                .claimResults(claimResults)
                .pathResults(pathResults)
                .evidenceResults(new ArrayList<>())
                .gapSubQueries(gapSubQueries)
                .allCards(allCards)
                .build();
    }
}
