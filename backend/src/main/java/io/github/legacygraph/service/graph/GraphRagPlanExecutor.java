package io.github.legacygraph.service.graph;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.rag.GraphRagEvidenceCard;
import io.github.legacygraph.dto.rag.GraphRagExecutionResult;
import io.github.legacygraph.dto.rag.GraphRagPlan;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.KnowledgeClaim;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * GraphRAG 计划执行器 — 将 GraphRagPlannerAgent 生成的计划落地为实际查询。
 * <p>
 * 把占位实现补成真实查询：
 * 1. Claim 精准查询：{@link KnowledgeClaimService#listClaimsBySubjects} DB 层过滤
 * 2. 路径查询：{@link Neo4jGraphDao#findPaths} 无向有界路径遍历
 * 3. 生成结构化 EvidenceCard（携带 relationTypes / pathNodeKeys / 行号 / 置信度）
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

    /** 单次路径查询返回路径数上限（稠密图兜底） */
    private static final int PATH_LIMIT = 20;
    /** 路径 evidence card 总上限 */
    private static final int PATH_CARD_CAP = 20;
    /** 关系类型白名单正则（与 CypherCatalog.safeIdentifier 一致） */
    private static final Pattern REL_TYPE_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** pathDepth "1..3" 形式的上界解析 */
    private static final Pattern RANGE_DEPTH_PATTERN = Pattern.compile("(\\d+)\\s*\\.\\.\\s*(\\d+)");
    private static final Pattern SINGLE_DEPTH_PATTERN = Pattern.compile("(\\d+)");

    /**
     * 执行 Claim 查询并生成 EvidenceCard 列表。
     * <p>
     * 从 claimQueries 收集 subjectKeys，status / minConfidence 取最宽
     * （status 取首个非空，minConfidence 取 min，默认 0.5），下推到 DB 层过滤。
     * </p>
     */
    public List<GraphRagEvidenceCard> executeClaimQueries(String projectId, String versionId,
                                                          List<GraphRagPlan.ClaimQuery> claimQueries,
                                                          int limit) {
        List<GraphRagEvidenceCard> cards = new ArrayList<>();
        if (claimQueries == null || claimQueries.isEmpty()) return cards;

        // 收集 subjectKeys（distinct，非空）
        List<String> subjectKeys = claimQueries.stream()
                .map(GraphRagPlan.ClaimQuery::getSubjectKey)
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (subjectKeys.isEmpty()) return cards;

        // status 取首个非空；minConfidence 取 min（默认 0.5）→ 最宽召回
        String status = claimQueries.stream()
                .map(GraphRagPlan.ClaimQuery::getStatus)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse(null);
        double minConf = claimQueries.stream()
                .mapToDouble(GraphRagPlan.ClaimQuery::getMinConfidence)
                .min()
                .orElse(0.5);

        List<KnowledgeClaim> claims = claimService.listClaimsBySubjects(
                projectId, versionId, subjectKeys, status, minConf, limit);

        for (KnowledgeClaim claim : claims) {
            cards.add(GraphRagEvidenceCard.builder()
                    .evidenceId(claim.getEvidenceIds() != null ? claim.getEvidenceIds() : "")
                    .sourceType(claim.getSourceType())
                    .claimId(claim.getId())
                    .nodeKey(claim.getSubjectKey())
                    .confidence(claim.getConfidence())
                    .status(claim.getStatus())
                    .excerpt(claim.getSubjectType() + ":" + claim.getSubjectKey()
                            + " -[" + claim.getPredicate() + "]-> "
                            + (claim.getObjectType() != null ? claim.getObjectType() + ":" : "")
                            + (claim.getObjectKey() != null ? claim.getObjectKey() : ""))
                    .build());
        }
        return cards;
    }

    /**
     * 执行路径查询 — 从 Neo4j 查询节点间有界无向路径。
     * <p>
     * 每个 PathQuery：startNodeFilter/endNodeFilter 作 nodeKey；从 relationshipPattern
     * 提取关系类型白名单；maxDepth 从 pathDepth 解析上界。单 query try/catch，
     * 某条失败不影响其他 query。
     * </p>
     */
    public List<GraphRagEvidenceCard> executePathQueries(String projectId, String versionId,
                                                         List<GraphRagPlan.PathQuery> pathQueries) {
        List<GraphRagEvidenceCard> cards = new ArrayList<>();
        if (pathQueries == null || pathQueries.isEmpty()) return cards;

        for (GraphRagPlan.PathQuery pq : pathQueries) {
            String fromKey = pq.getStartNodeFilter() != null ? pq.getStartNodeFilter().trim() : null;
            String toKey = pq.getEndNodeFilter() != null ? pq.getEndNodeFilter().trim() : null;
            if (fromKey == null || fromKey.isBlank() || toKey == null || toKey.isBlank()) {
                continue;
            }
            List<String> relTypes = extractRelationshipTypes(pq.getRelationshipPattern());
            int maxDepth = parseMaxDepth(pq.getPathDepth());
            try {
                List<Neo4jGraphDao.GraphPath> paths = neo4jGraphDao.findPaths(
                        projectId, versionId, fromKey, toKey, relTypes, maxDepth, PATH_LIMIT);
                for (Neo4jGraphDao.GraphPath path : paths) {
                    if (cards.size() >= PATH_CARD_CAP) return cards;
                    cards.add(pathToCard(path));
                }
            } catch (Exception e) {
                log.warn("Path query failed for {}->{}: {}", fromKey, toKey, e.getMessage());
            }
        }
        return cards;
    }

    /**
     * 执行完整的研究计划：Claim 查询 + 路径查询，组装执行结果并做证据不足检测。
     * <p>仅被 {@code EnhancedQaAgent} 内部调用。</p>
     */
    public GraphRagExecutionResult execute(String projectId, String versionId,
                                           List<GraphRagPlan.ClaimQuery> claimQueries,
                                           List<GraphRagPlan.PathQuery> pathQueries) {
        List<GraphRagEvidenceCard> claimResults = executeClaimQueries(projectId, versionId, claimQueries, 200);
        List<GraphRagEvidenceCard> pathResults = executePathQueries(projectId, versionId, pathQueries);

        List<GraphRagEvidenceCard> allCards = new ArrayList<>();
        allCards.addAll(claimResults);
        allCards.addAll(pathResults);

        // 证据不足检测（逻辑不变）
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

    // ──────────── Private helpers ────────────

    /**
     * 从 relationshipPattern 用白名单正则提取关系类型。
     * 形如 {@code [:HANDLED_BY]->} / {@code <-[:READS]-} / {@code HANDLED_BY|CALLS}。
     * 空或非法 → 返回空列表（全类型）。提取后的标识符在 findPaths 内再次经 safeIdentifier 校验。
     */
    private List<String> extractRelationshipTypes(String relationshipPattern) {
        if (relationshipPattern == null || relationshipPattern.isBlank()) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        Matcher m = REL_TYPE_PATTERN.matcher(relationshipPattern);
        while (m.find()) {
            types.add(m.group());
        }
        return types;
    }

    /**
     * 从 pathDepth 解析上界，默认 3，钳制 [1,4]。
     * 形如 "1..3" → 3；"3" → 3。
     */
    private int parseMaxDepth(String pathDepth) {
        if (pathDepth == null || pathDepth.isBlank()) return 3;
        Matcher range = RANGE_DEPTH_PATTERN.matcher(pathDepth);
        if (range.find()) {
            return clampDepth(Integer.parseInt(range.group(2)));
        }
        Matcher single = SINGLE_DEPTH_PATTERN.matcher(pathDepth);
        if (single.find()) {
            return clampDepth(Integer.parseInt(single.group(1)));
        }
        return 3;
    }

    private int clampDepth(int depth) {
        return Math.max(1, Math.min(4, depth));
    }

    /**
     * 把一条 GraphPath 折成 EvidenceCard（携带轻量结构，不塞全量 node/edge 对象）。
     */
    private GraphRagEvidenceCard pathToCard(Neo4jGraphDao.GraphPath path) {
        List<String> pathNodeKeys = path.nodes().stream()
                .map(GraphNode::getNodeKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 可读链：A -[REL]-> B -[REL]-> C
        StringBuilder excerpt = new StringBuilder();
        for (int i = 0; i < path.nodes().size(); i++) {
            GraphNode n = path.nodes().get(i);
            excerpt.append(nodeLabel(n));
            if (i < path.edges().size()) {
                excerpt.append(" -[").append(path.edges().get(i).getEdgeType()).append("]-> ");
            }
        }

        // status：首节点 status 或 "CONFIRMED"
        String firstStatus = path.nodes().isEmpty() ? null : path.nodes().get(0).getStatus();
        String status = (firstStatus != null && !firstStatus.isBlank()) ? firstStatus : "CONFIRMED";

        return GraphRagEvidenceCard.builder()
                .sourceType("NEO4J")
                .nodeKey(path.fromKey() + "->" + path.toKey())
                .relationTypes(path.relationTypes())
                .pathNodeKeys(pathNodeKeys)
                .sourcePath(path.sourcePath())
                .startLine(path.startLine())
                .endLine(path.endLine())
                .confidence(path.confidence())
                .status(status)
                .excerpt(excerpt.toString())
                .build();
    }

    private String nodeLabel(GraphNode n) {
        if (n.getDisplayName() != null && !n.getDisplayName().isBlank()) {
            return n.getDisplayName();
        }
        if (n.getNodeName() != null && !n.getNodeName().isBlank()) {
            return n.getNodeName();
        }
        return n.getNodeKey() != null ? n.getNodeKey() : "";
    }
}
