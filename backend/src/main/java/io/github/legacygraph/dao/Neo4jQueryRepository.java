package io.github.legacygraph.dao;

import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.service.system.CacheService;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static io.github.legacygraph.dao.Neo4jConversions.*;

/**
 * Neo4j 查询 Repository — 负责所有只读查询操作。
 */
@Slf4j
@Component
public class Neo4jQueryRepository {

    private final Driver neo4jDriver;

    @Autowired(required = false)
    private CacheService cacheService;

    private static final Duration NODE_CACHE_TTL = Duration.ofSeconds(60);

    public Neo4jQueryRepository(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    /** 查找节点（projectId + versionId + nodeType + nodeKey 唯一约束） */
    public Optional<GraphNode> findNode(String projectId, String versionId, String nodeType, String nodeKey) {
        try (Session session = neo4jDriver.session()) {
            String cypher = String.format(
                    "MATCH (n:%s {projectId: $projectId, versionId: $versionId, nodeKey: $nodeKey}) RETURN n LIMIT 1",
                    CypherCatalog.safeIdentifier(nodeType, "nodeType"));
            Result result = session.run(cypher, Map.of(
                    "projectId", projectId,
                    "versionId", normalizeId(versionId),
                    "nodeKey", nodeKey));
            if (result.hasNext()) {
                return Optional.of(recordToNode(result.next().get("n").asNode()));
            }
        }
        return Optional.empty();
    }

    /** 按 ID 查找节点 */
    public Optional<GraphNode> findNodeById(String nodeId) {
        if (cacheService != null && nodeId != null) {
            GraphNode cached = cacheService.get(nodeCacheKey(nodeId), GraphNode.class);
            if (cached != null) {
                return Optional.of(cached);
            }
        }
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(
                    "MATCH (n) WHERE n.id = $id RETURN n LIMIT 1",
                    Map.of("id", nodeId));
            if (result.hasNext()) {
                GraphNode node = recordToNode(result.next().get("n").asNode());
                if (cacheService != null && nodeId != null) {
                    cacheService.put(nodeCacheKey(nodeId), node, NODE_CACHE_TTL);
                }
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    /** 按 ID 列表批量查找 */
    public List<GraphNode> findNodesByIds(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) return List.of();
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(
                    "MATCH (n) WHERE n.id IN $ids RETURN n",
                    Map.of("ids", nodeIds));
            List<GraphNode> nodes = new ArrayList<>();
            while (result.hasNext()) {
                nodes.add(recordToNode(result.next().get("n").asNode()));
            }
            return nodes;
        }
    }

    /** 条件查询节点列表 */
    public List<GraphNode> queryNodes(String projectId, String versionId,
                                       String nodeType, String sourceType,
                                       Double minConfidence, String status,
                                       int limit) {
        try (Session session = neo4jDriver.session()) {
            StringBuilder cypher = new StringBuilder("MATCH (n) WHERE n.projectId = $projectId");
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            if (versionId != null) {
                cypher.append(" AND n.versionId = $versionId");
                params.put("versionId", normalizeId(versionId));
            }
            if (nodeType != null) {
                cypher.append(" AND $nodeType IN labels(n)");
                params.put("nodeType", nodeType);
            }
            if (sourceType != null) {
                cypher.append(" AND n.sourceType = $sourceType");
                params.put("sourceType", sourceType);
            }
            if (minConfidence != null) {
                cypher.append(" AND n.confidence >= $minConfidence");
                params.put("minConfidence", minConfidence);
            }
            if (status != null) {
                cypher.append(" AND n.status = $status");
                params.put("status", status);
            }
            cypher.append(" RETURN n");
            if (limit > 0) {
                cypher.append(" LIMIT ").append(limit);
            }
            Result result = session.run(cypher.toString(), params);
            List<GraphNode> nodes = new ArrayList<>();
            while (result.hasNext()) {
                nodes.add(recordToNode(result.next().get("n").asNode()));
            }
            return nodes;
        }
    }

    /** 增强条件查询节点列表（增加 nodeKey 过滤） */
    public List<GraphNode> queryNodes(String projectId, String versionId,
                                       String nodeType, String nodeKey,
                                       String sourceType, Double minConfidence,
                                       String status, int limit) {
        try (Session session = neo4jDriver.session()) {
            StringBuilder cypher = new StringBuilder("MATCH (n) WHERE n.projectId = $projectId");
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            if (versionId != null) {
                cypher.append(" AND n.versionId = $versionId");
                params.put("versionId", normalizeId(versionId));
            } else {
                params.put("versionId", null);
            }
            if (nodeType != null) {
                cypher.append(" AND $nodeType IN labels(n)");
                params.put("nodeType", nodeType);
            }
            if (nodeKey != null) {
                cypher.append(" AND n.nodeKey = $nodeKey");
                params.put("nodeKey", nodeKey);
            }
            if (sourceType != null) {
                cypher.append(" AND n.sourceType = $sourceType");
                params.put("sourceType", sourceType);
            }
            if (minConfidence != null) {
                cypher.append(" AND n.confidence >= $minConfidence");
                params.put("minConfidence", minConfidence);
            }
            if (status != null) {
                cypher.append(" AND n.status = $status");
                params.put("status", status);
            }
            cypher.append(" RETURN n");
            if (limit > 0) {
                cypher.append(" LIMIT ").append(limit);
            }
            Result result = session.run(cypher.toString(), params);
            List<GraphNode> nodes = new ArrayList<>();
            while (result.hasNext()) {
                nodes.add(recordToNode(result.next().get("n").asNode()));
            }
            return nodes;
        }
    }

    /** 统计节点数量 */
    public long countNodes(String projectId, String versionId, String status) {
        try (Session session = neo4jDriver.session()) {
            StringBuilder cypher = new StringBuilder("MATCH (n) WHERE n.projectId = $projectId");
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            if (versionId != null) {
                cypher.append(" AND n.versionId = $versionId");
                params.put("versionId", normalizeId(versionId));
            }
            if (status != null) {
                cypher.append(" AND n.status = $status");
                params.put("status", status);
            }
            cypher.append(" RETURN count(n) AS cnt");
            Result result = session.run(cypher.toString(), params);
            if (result.hasNext()) {
                return result.next().get("cnt").asLong();
            }
        }
        return 0;
    }

    /**
     * 查找已存在的边（用于去重）。
     * 按 (projectId, versionId, fromNodeId, toNodeId, edgeType, edgeKey) 唯一标识一条边。
     */
    public Optional<GraphEdge> findEdge(String projectId, String versionId,
            String fromNodeId, String toNodeId, String edgeType, String edgeKey) {
        try (Session session = neo4jDriver.session()) {
            String cypher = "MATCH (from {id: $fromId})-[r]->(to {id: $toId}) " +
                    "WHERE type(r) = $edgeType AND r.edgeKey = $edgeKey " +
                    "AND r.projectId = $projectId " +
                    "AND (r.versionId = $versionId OR $versionId IS NULL) " +
                    "RETURN r, from, to LIMIT 1";
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            params.put("versionId", normalizeId(versionId));
            params.put("fromId", fromNodeId);
            params.put("toId", toNodeId);
            params.put("edgeType", edgeType);
            params.put("edgeKey", edgeKey != null ? edgeKey : "");
            Result result = session.run(cypher, params);
            if (result.hasNext()) {
                org.neo4j.driver.Record record = result.next();
                return Optional.of(recordToEdge(
                        record.get("r").asRelationship(),
                        record.get("from").asNode(),
                        record.get("to").asNode()));
            }
            return Optional.empty();
        }
    }

    /** 条件查询边列表 */
    public List<GraphEdge> queryEdges(String projectId, String versionId,
                                       Double minConfidence, String status,
                                       int limit) {
        try (Session session = neo4jDriver.session()) {
            StringBuilder cypher = new StringBuilder(
                    "MATCH (from)-[r]->(to) WHERE r.projectId = $projectId");
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            if (versionId != null) {
                cypher.append(" AND r.versionId = $versionId");
                params.put("versionId", normalizeId(versionId));
            }
            if (minConfidence != null) {
                cypher.append(" AND r.confidence >= $minConfidence");
                params.put("minConfidence", minConfidence);
            }
            if (status != null) {
                cypher.append(" AND r.status = $status");
                params.put("status", status);
            }
            cypher.append(" RETURN r, from, to");
            if (limit > 0) {
                cypher.append(" LIMIT ").append(limit);
            }
            Result result = session.run(cypher.toString(), params);
            List<GraphEdge> edges = new ArrayList<>();
            while (result.hasNext()) {
                org.neo4j.driver.Record record = result.next();
                edges.add(recordToEdge(record.get("r").asRelationship(),
                        record.get("from").asNode(),
                        record.get("to").asNode()));
            }
            return edges;
        }
    }

    /** 统计边数量 */
    public long countEdges(String projectId, String versionId, String status) {
        try (Session session = neo4jDriver.session()) {
            StringBuilder cypher = new StringBuilder(
                    "MATCH ()-[r]->() WHERE r.projectId = $projectId");
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            if (versionId != null) {
                cypher.append(" AND r.versionId = $versionId");
                params.put("versionId", normalizeId(versionId));
            }
            if (status != null) {
                cypher.append(" AND r.status = $status");
                params.put("status", status);
            }
            cypher.append(" RETURN count(r) AS cnt");
            Result result = session.run(cypher.toString(), params);
            if (result.hasNext()) {
                return result.next().get("cnt").asLong();
            }
        }
        return 0;
    }

    /** 查询指定来源类型的节点 key，用于构建 Graphify 版本快照。 */
    public Set<String> queryNodeKeysBySourceTypes(String projectId, String versionId, List<String> sourceTypes) {
        if (sourceTypes == null || sourceTypes.isEmpty()) {
            return Set.of();
        }
        try (Session session = neo4jDriver.session()) {
            String cypher =
                    "MATCH (n) WHERE n.projectId = $projectId " +
                    "AND ($versionId IS NULL OR n.versionId = $versionId) " +
                    "AND n.sourceType IN $sourceTypes " +
                    "RETURN DISTINCT coalesce(n.nodeKey, n.id) AS key " +
                    "ORDER BY key";
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            params.put("versionId", normalizeId(versionId));
            params.put("sourceTypes", sourceTypes);
            Result result = session.run(cypher, params);
            return collectStringKeys(result);
        }
    }

    /** 查询指定来源类型的边 key，用于构建 Graphify 版本快照。 */
    public Set<String> queryEdgeKeysBySourceTypes(String projectId, String versionId, List<String> sourceTypes) {
        if (sourceTypes == null || sourceTypes.isEmpty()) {
            return Set.of();
        }
        try (Session session = neo4jDriver.session()) {
            String cypher =
                    "MATCH ()-[r]->() WHERE r.projectId = $projectId " +
                    "AND ($versionId IS NULL OR r.versionId = $versionId) " +
                    "AND r.sourceType IN $sourceTypes " +
                    "RETURN DISTINCT coalesce(r.edgeKey, r.id) AS key " +
                    "ORDER BY key";
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            params.put("versionId", normalizeId(versionId));
            params.put("sourceTypes", sourceTypes);
            Result result = session.run(cypher, params);
            return collectStringKeys(result);
        }
    }

    private Set<String> collectStringKeys(Result result) {
        Set<String> keys = new LinkedHashSet<>();
        while (result.hasNext()) {
            org.neo4j.driver.Record record = result.next();
            if (!record.get("key").isNull()) {
                String key = record.get("key").asString(null);
                if (key != null && !key.isBlank()) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /**
     * 查询低置信度节点（置信度 < 0.5），限制返回数量避免超时。
     */
    public List<GraphNode> queryLowConfidenceNodes(String projectId, int limit) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId " +
                "AND coalesce(n.confidence, 0.0) < $threshold " +
                "RETURN n ORDER BY n.confidence ASC LIMIT $limit";
            Result result = session.run(cypher, Map.of(
                    "projectId", projectId,
                    "threshold", 0.5,
                    "limit", (long) limit));
            List<GraphNode> nodes = new ArrayList<>();
            while (result.hasNext()) {
                nodes.add(recordToNode(result.next().get("n").asNode()));
            }
            return nodes;
        }
    }

    /**
     * 查询无连接关系的孤立节点，限制返回数量避免超时。
     */
    public List<GraphNode> queryDisconnectedNodes(String projectId, int limit) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId " +
                "OPTIONAL MATCH (n)-[r]-() WHERE r.projectId = $projectId " +
                "WITH n, count(r) AS edgeCount WHERE edgeCount = 0 " +
                "RETURN n LIMIT $limit";
            Result result = session.run(cypher, Map.of(
                    "projectId", projectId,
                    "limit", (long) limit));
            List<GraphNode> nodes = new ArrayList<>();
            while (result.hasNext()) {
                nodes.add(recordToNode(result.next().get("n").asNode()));
            }
            return nodes;
        }
    }

    /**
     * 查询某节点在指定项目内的全部邻居节点 id（应用 UUID），单次 Cypher 直查，
     * 替代旧实现中"查全项目边前 50 + Java 过滤"的 N+1 全量加载方式（B-S3）。
     */
    public Set<String> findNeighborNodeIds(String projectId, String nodeId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n {id: $nodeId, projectId: $projectId})-[r]-(m) " +
                "WHERE r.projectId = $projectId AND m.id IS NOT NULL " +
                "RETURN DISTINCT m.id AS neighborId";
            Result result = session.run(cypher, Map.of(
                    "projectId", projectId,
                    "nodeId", nodeId));
            Set<String> ids = new HashSet<>();
            while (result.hasNext()) {
                ids.add(result.next().get("neighborId").asString());
            }
            return ids;
        }
    }

    /**
     * 批量查询多个源节点的邻居（一次 Cypher 替代 N 次单节点查询），
     * 返回 sourceNodeId → neighborIds 映射。
     */
    public Map<String, Set<String>> findNeighborNodeIdsBySources(
            String projectId, Collection<String> sourceNodeIds, int perNodeLimit) {
        if (sourceNodeIds == null || sourceNodeIds.isEmpty()) return Map.of();
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n)-[r]-(m) " +
                "WHERE n.id IN $sourceIds AND n.projectId = $projectId " +
                "AND r.projectId = $projectId AND m.id IS NOT NULL " +
                "RETURN n.id AS sourceId, m.id AS neighborId";
            Result result = session.run(cypher, Map.of(
                    "projectId", projectId,
                    "sourceIds", new ArrayList<>(sourceNodeIds)));
            Map<String, Set<String>> map = new HashMap<>();
            while (result.hasNext()) {
                org.neo4j.driver.Record rec = result.next();
                String src = rec.get("sourceId").asString();
                String nbr = rec.get("neighborId").asString();
                map.computeIfAbsent(src, k -> new LinkedHashSet<>()).add(nbr);
            }
            // 应用 perNodeLimit
            if (perNodeLimit > 0) {
                map.replaceAll((k, v) -> v.size() > perNodeLimit
                        ? v.stream().limit(perNodeLimit).collect(Collectors.toCollection(LinkedHashSet::new))
                        : v);
            }
            return map;
        }
    }

    /** 按 ID 查找边（返回两端节点 id 属性，而非 Neo4j 内部 elementId） */
    public Optional<GraphEdge> findEdgeById(String edgeId) {
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(
                    "MATCH (from)-[r {id: $id}]->(to) RETURN r, from, to LIMIT 1",
                    Map.of("id", edgeId));
            if (result.hasNext()) {
                org.neo4j.driver.Record rec = result.next();
                org.neo4j.driver.types.Relationship rel = rec.get("r").asRelationship();
                org.neo4j.driver.types.Node fromNode = rec.get("from").asNode();
                org.neo4j.driver.types.Node toNode = rec.get("to").asNode();
                return Optional.of(recordToEdge(rel, fromNode, toNode));
            }
        }
        return Optional.empty();
    }

    /** 增强条件查询边列表（支持 edgeType / toNodeId / connectedNodeId） */
    public List<GraphEdge> queryEdges(String projectId, String versionId,
                                       String edgeType, String toNodeId, String connectedNodeId,
                                       Double minConfidence, String status,
                                       int limit) {
        try (Session session = neo4jDriver.session()) {
            StringBuilder cypher = new StringBuilder(
                    "MATCH (from)-[r]->(to) WHERE r.projectId = $projectId");
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            if (versionId != null) {
                cypher.append(" AND r.versionId = $versionId");
                params.put("versionId", normalizeId(versionId));
            }
            if (edgeType != null) {
                cypher.append(" AND type(r) = $edgeType");
                params.put("edgeType", edgeType);
            }
            if (minConfidence != null) {
                cypher.append(" AND r.confidence >= $minConfidence");
                params.put("minConfidence", minConfidence);
            }
            if (status != null) {
                cypher.append(" AND r.status = $status");
                params.put("status", status);
            }
            if (toNodeId != null) {
                cypher.append(" AND to.id = $toNodeId");
                params.put("toNodeId", toNodeId);
            }
            if (connectedNodeId != null) {
                cypher.append(" AND (from.id = $connectedNodeId OR to.id = $connectedNodeId)");
                params.put("connectedNodeId", connectedNodeId);
            }
            cypher.append(" RETURN r, from, to");
            if (limit > 0) {
                cypher.append(" LIMIT ").append(limit);
            }
            Result result = session.run(cypher.toString(), params);
            List<GraphEdge> edges = new ArrayList<>();
            while (result.hasNext()) {
                org.neo4j.driver.Record record = result.next();
                edges.add(recordToEdge(record.get("r").asRelationship(),
                        record.get("from").asNode(),
                        record.get("to").asNode()));
            }
            return edges;
        }
    }

    /**
     * 版本限定：低置信度节点（threshold 以下），按置信度升序。
     */
    public List<GraphNode> queryLowConfidenceNodes(String projectId, String versionId,
                                                    double threshold, int limit) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "AND coalesce(n.confidence, 0.0) < $threshold " +
                "RETURN n ORDER BY n.confidence ASC LIMIT $limit";
            Result result = session.run(cypher, Map.of(
                    "projectId", projectId, "versionId", normalizeId(versionId),
                    "threshold", threshold, "limit", (long) limit));
            List<GraphNode> nodes = new ArrayList<>();
            while (result.hasNext()) nodes.add(recordToNode(result.next().get("n").asNode()));
            return nodes;
        }
    }

    /**
     * 版本限定：孤立节点。
     */
    public List<GraphNode> queryDisconnectedNodes(String projectId, String versionId, int limit) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "OPTIONAL MATCH (n)-[r]-() WHERE r.projectId = $projectId " +
                "WITH n, count(r) AS edgeCount WHERE edgeCount = 0 " +
                "RETURN n LIMIT $limit";
            Result result = session.run(cypher, Map.of(
                    "projectId", projectId, "versionId", normalizeId(versionId),
                    "limit", (long) limit));
            List<GraphNode> nodes = new ArrayList<>();
            while (result.hasNext()) nodes.add(recordToNode(result.next().get("n").asNode()));
            return nodes;
        }
    }

    /**
     * 通过测试覆盖的边计数（连接已覆盖节点的边数）。
     * 用于计算边覆盖率，避免全量边加载。
     */
    public long countEdgesConnectedToNodes(String projectId, String versionId, List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) return 0L;
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (from)-[r]->(to) WHERE r.projectId = $projectId AND r.versionId = $versionId " +
                "AND (from.id IN $nodeIds OR to.id IN $nodeIds) " +
                "RETURN count(DISTINCT r) AS cnt";
            Result result = session.run(cypher, Map.of(
                    "projectId", projectId, "versionId", normalizeId(versionId),
                    "nodeIds", nodeIds));
            if (result.hasNext()) return result.next().get("cnt").asLong();
        }
        return 0L;
    }

    /**
     * 批量查询 Table 节点的字段数与关联表关系数。
     */
    public Map<String, long[]> queryTableStats(String projectId, String versionId, List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Map.of();
        }
        String cypher =
                "UNWIND $nodeIds AS nodeId " +
                "MATCH (t:Table {id: nodeId}) " +
                "OPTIONAL MATCH (t)-[hc:HAS_COLUMN]->(:Column) " +
                "WHERE hc.projectId = $projectId AND hc.versionId = $versionId " +
                "WITH t, count(hc) AS colCnt " +
                // 直接关联：REFERENCES 边
                "OPTIONAL MATCH (t)-[ref1:REFERENCES]-(:Table) " +
                "WHERE ref1.projectId = $projectId AND ref1.versionId = $versionId " +
                "WITH t, colCnt, count(DISTINCT ref1) AS refCnt " +
                // 隐式关联：通过 SqlStatement 共享访问（READS/WRITES 同一 SQL 的其他表）
                "OPTIONAL MATCH (t)<-[:READS|WRITES]-(sql:SqlStatement)-[:READS|WRITES]->(otherTable:Table) " +
                "WHERE sql.projectId = $projectId AND sql.versionId = $versionId " +
                "AND otherTable <> t AND otherTable.projectId = $projectId AND otherTable.versionId = $versionId " +
                "RETURN t.id AS nodeId, colCnt, refCnt, count(DISTINCT otherTable) AS implicitCnt";
        Map<String, long[]> result = new HashMap<>();
        try (Session session = neo4jDriver.session()) {
            Result rs = session.run(cypher, Map.of(
                    "projectId", projectId,
                    "versionId", normalizeId(versionId),
                    "nodeIds", nodeIds));
            while (rs.hasNext()) {
                org.neo4j.driver.Record rec = rs.next();
                long refCnt = rec.get("refCnt").asLong();
                long implicitCnt = rec.get("implicitCnt").asLong();
                // 关联数 = 直接 REFERENCES + 隐式 SQL 共享（去重）
                result.put(rec.get("nodeId").asString(), new long[]{
                        rec.get("colCnt").asLong(),
                        refCnt + implicitCnt
                });
            }
        } catch (Exception e) {
            log.warn("queryTableStats failed: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 有界无向路径查询 — 在 from/to 节点之间查找最多 maxDepth 跳的路径。
     * <p>
     * 关系类型经 {@link CypherCatalog#safeIdentifier} 白名单校验后内联拼接
     * （label/type 不可参数化）；maxDepth 钳制到 [1,4]；无向遍历最大化
     * "A 和 B 什么关系" 的召回。失败时 log 并返回空 list（与既有 queryRepo 风格一致）。
     * </p>
     *
     * @param relationshipTypes 关系类型白名单（null 或空 → 全类型）
     * @param maxDepth          最大跳数，钳制到 [1,4]
     * @param limit             返回路径数上限
     */
    public List<Neo4jGraphDao.GraphPath> findPaths(String projectId, String versionId,
                                                    String fromKey, String toKey,
                                                    List<String> relationshipTypes,
                                                    int maxDepth, int limit) {
        if (fromKey == null || fromKey.isBlank() || toKey == null || toKey.isBlank()) {
            return List.of();
        }
        int depth = Math.max(1, Math.min(4, maxDepth));
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 100);

        // 关系类型白名单校验 + 拼接；空列表 → 裸 *1..depth（全类型）
        StringBuilder relSpec = new StringBuilder();
        if (relationshipTypes != null) {
            for (String t : relationshipTypes) {
                if (t == null || t.isBlank()) continue;
                String safe = CypherCatalog.safeIdentifier(t.trim(), "relType");
                if (relSpec.length() > 0) {
                    relSpec.append("|");
                }
                relSpec.append(safe);
            }
        }
        if (relSpec.length() > 0) {
            relSpec.insert(0, ":");
        }
        relSpec.append("*1..").append(depth);

        String cypher =
                "MATCH p = (from {projectId: $projectId, versionId: $versionId, nodeKey: $fromKey})" +
                "-[" + relSpec + "]-" +
                "(to {projectId: $projectId, versionId: $versionId, nodeKey: $toKey}) " +
                "WHERE from.id <> to.id " +
                "RETURN p, nodes(p) AS ns, relationships(p) AS rs " +
                "LIMIT $limit";

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("versionId", normalizeId(versionId));
        params.put("fromKey", fromKey);
        params.put("toKey", toKey);
        params.put("limit", (long) safeLimit);

        List<Neo4jGraphDao.GraphPath> paths = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, params);
            while (result.hasNext()) {
                org.neo4j.driver.Record record = result.next();
                List<org.neo4j.driver.types.Node> neoNodes =
                        record.get("ns").asList(v -> v.asNode());
                List<org.neo4j.driver.types.Relationship> neoRels =
                        record.get("rs").asList(v -> v.asRelationship());
                if (neoNodes.isEmpty()) continue;

                List<GraphNode> graphNodes = new ArrayList<>(neoNodes.size());
                for (org.neo4j.driver.types.Node neoNode : neoNodes) {
                    graphNodes.add(recordToNode(neoNode));
                }

                // relationTypes = distinct rel.type()（保序）
                Set<String> relTypeSet = new LinkedHashSet<>();
                for (org.neo4j.driver.types.Relationship rel : neoRels) {
                    relTypeSet.add(rel.type());
                }

                // 边：按路径顺序，用 relationship 的 start/end elementId 对齐真实方向
                List<GraphEdge> graphEdges = new ArrayList<>(neoRels.size());
                for (int i = 0; i < neoRels.size(); i++) {
                    org.neo4j.driver.types.Relationship rel = neoRels.get(i);
                    org.neo4j.driver.types.Node n0 = neoNodes.get(i);
                    org.neo4j.driver.types.Node n1 = neoNodes.get(i + 1);
                    String relStart = rel.startNodeElementId();
                    org.neo4j.driver.types.Node edgeFrom =
                            relStart.equals(n0.elementId()) ? n0 : n1;
                    org.neo4j.driver.types.Node edgeTo =
                            relStart.equals(n0.elementId()) ? n1 : n0;
                    graphEdges.add(recordToEdge(rel, edgeFrom, edgeTo));
                }

                GraphNode first = graphNodes.get(0);
                GraphNode last = graphNodes.get(graphNodes.size() - 1);

                // confidence = path 上节点 confidence 的 min（保守）
                BigDecimal pathConf = null;
                for (GraphNode n : graphNodes) {
                    if (n.getConfidence() != null) {
                        if (pathConf == null || n.getConfidence().compareTo(pathConf) < 0) {
                            pathConf = n.getConfidence();
                        }
                    }
                }

                paths.add(new Neo4jGraphDao.GraphPath(
                        graphNodes,
                        graphEdges,
                        new ArrayList<>(relTypeSet),
                        first.getSourcePath(),
                        first.getStartLine(),
                        first.getEndLine(),
                        pathConf,
                        first.getNodeKey(),
                        last.getNodeKey()
                ));
            }
        } catch (Exception e) {
            log.warn("findPaths failed for {} -> {}: {}", fromKey, toKey, e.getMessage());
            return List.of();
        }
        return paths;
    }

    /**
     * 有向有界路径查询 — 从单一起点沿指定方向遍历依赖链。
     * <p>
     * 与 {@link #findPaths} 区别：单起点（非 from-to）+ 有向。
     * INBOUND 用 {@code <-[relSpec]-}（反向往上游），OUTBOUND 用 {@code -[relSpec]->}。
     * 用于变更影响多跳反查（Table←SQL←Mapper←Service←Api←Feature）。
     * </p>
     */
    public List<Neo4jGraphDao.GraphPath> findPathsDirected(String projectId, String versionId,
                                                           String startNodeKey,
                                                           List<String> relationshipTypes,
                                                           io.github.legacygraph.common.FlowDirection flow,
                                                           int maxDepth, int limit) {
        if (startNodeKey == null || startNodeKey.isBlank()) {
            return List.of();
        }
        int depth = Math.max(1, Math.min(4, maxDepth));
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 100);

        // relSpec 拼接（复用 findPaths 逻辑）
        StringBuilder relSpec = new StringBuilder();
        if (relationshipTypes != null) {
            for (String t : relationshipTypes) {
                if (t == null || t.isBlank()) continue;
                String safe = CypherCatalog.safeIdentifier(t.trim(), "relType");
                if (relSpec.length() > 0) relSpec.append("|");
                relSpec.append(safe);
            }
        }
        if (relSpec.length() > 0) relSpec.insert(0, ":");
        relSpec.append("*1..").append(depth);

        // INBOUND: <-[relSpec]- (反向，往上游)；OUTBOUND: -[relSpec]-> (正向)
        String arrow = (flow == io.github.legacygraph.common.FlowDirection.INBOUND)
                ? "<-[" + relSpec + "]-" : "-[" + relSpec + "]->";

        String cypher =
                "MATCH p = (start {projectId: $projectId, versionId: $versionId, nodeKey: $startNodeKey})" +
                arrow +
                "() " +
                "RETURN p, nodes(p) AS ns, relationships(p) AS rs " +
                "LIMIT $limit";

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("versionId", normalizeId(versionId));
        params.put("startNodeKey", startNodeKey);
        params.put("limit", (long) safeLimit);

        List<Neo4jGraphDao.GraphPath> paths = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, params);
            while (result.hasNext()) {
                org.neo4j.driver.Record record = result.next();
                List<org.neo4j.driver.types.Node> neoNodes =
                        record.get("ns").asList(v -> v.asNode());
                List<org.neo4j.driver.types.Relationship> neoRels =
                        record.get("rs").asList(v -> v.asRelationship());
                if (neoNodes.isEmpty()) continue;

                List<GraphNode> graphNodes = new ArrayList<>(neoNodes.size());
                for (org.neo4j.driver.types.Node neoNode : neoNodes) {
                    graphNodes.add(recordToNode(neoNode));
                }

                Set<String> relTypeSet = new LinkedHashSet<>();
                for (org.neo4j.driver.types.Relationship rel : neoRels) {
                    relTypeSet.add(rel.type());
                }

                List<GraphEdge> graphEdges = new ArrayList<>(neoRels.size());
                for (int i = 0; i < neoRels.size(); i++) {
                    org.neo4j.driver.types.Relationship rel = neoRels.get(i);
                    org.neo4j.driver.types.Node n0 = neoNodes.get(i);
                    org.neo4j.driver.types.Node n1 = neoNodes.get(i + 1);
                    String relStart = rel.startNodeElementId();
                    org.neo4j.driver.types.Node edgeFrom =
                            relStart.equals(n0.elementId()) ? n0 : n1;
                    org.neo4j.driver.types.Node edgeTo =
                            relStart.equals(n0.elementId()) ? n1 : n0;
                    graphEdges.add(recordToEdge(rel, edgeFrom, edgeTo));
                }

                GraphNode first = graphNodes.get(0);
                GraphNode last = graphNodes.get(graphNodes.size() - 1);

                BigDecimal pathConf = null;
                for (GraphNode n : graphNodes) {
                    if (n.getConfidence() != null) {
                        if (pathConf == null || n.getConfidence().compareTo(pathConf) < 0) {
                            pathConf = n.getConfidence();
                        }
                    }
                }

                paths.add(new Neo4jGraphDao.GraphPath(
                        graphNodes, graphEdges, new ArrayList<>(relTypeSet),
                        first.getSourcePath(), first.getStartLine(), first.getEndLine(),
                        pathConf, first.getNodeKey(), last.getNodeKey()));
            }
        } catch (Exception e) {
            log.warn("findPathsDirected failed for {}: {}", startNodeKey, e.getMessage());
            return List.of();
        }
        return paths;
    }
}
