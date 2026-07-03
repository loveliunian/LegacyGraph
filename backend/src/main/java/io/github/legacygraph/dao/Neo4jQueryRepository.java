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
                "OPTIONAL MATCH (t)-[ref1:REFERENCES]-(:Table) " +
                "WHERE ref1.projectId = $projectId AND ref1.versionId = $versionId " +
                "RETURN t.id AS nodeId, colCnt, count(ref1) AS relCnt";
        Map<String, long[]> result = new HashMap<>();
        try (Session session = neo4jDriver.session()) {
            Result rs = session.run(cypher, Map.of(
                    "projectId", projectId,
                    "versionId", normalizeId(versionId),
                    "nodeIds", nodeIds));
            while (rs.hasNext()) {
                org.neo4j.driver.Record rec = rs.next();
                result.put(rec.get("nodeId").asString(), new long[]{
                        rec.get("colCnt").asLong(),
                        rec.get("relCnt").asLong()
                });
            }
        } catch (Exception e) {
            log.warn("queryTableStats failed: {}", e.getMessage());
        }
        return result;
    }
}
