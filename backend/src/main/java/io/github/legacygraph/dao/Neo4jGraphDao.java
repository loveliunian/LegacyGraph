package io.github.legacygraph.dao;

import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Neo4j 图数据访问对象 — 统一替代 PostgreSQL GraphNodeRepository / GraphEdgeRepository。
 * 所有图谱数据直接写入 Neo4j，不再经过 PostgreSQL。
 */
@Slf4j
@Component
public class Neo4jGraphDao {

    private final Driver neo4jDriver;

    /** 节点详情缓存（可选）：findNodeById 高频单点查；写时失效，短 TTL 兜底。Redis 不可用时为 null。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.legacygraph.service.CacheService cacheService;

    /** 节点详情缓存 TTL（短，作为写时失效的兜底） */
    private static final java.time.Duration NODE_CACHE_TTL = java.time.Duration.ofSeconds(60);

    public Neo4jGraphDao(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    /**
     * 归一化 UUID 格式：去除横线。
     * Neo4j 中 versionId 由 MyBatis-Plus ASSIGN_UUID 生成，存储为 32 位 hex（无横线），
     * 但 PostgreSQL uuid 列读取时返回 36 位带横线格式，导致前端传入的 versionId 无法匹配。
     * 统一归一化确保读写一致。
     */
    static String normalizeId(String id) {
        if (id == null) return null;
        return id.replace("-", "");
    }

    private String nodeCacheKey(String nodeId) {
        return "graph:node:" + nodeId;
    }

    private void evictNodeCache(String nodeId) {
        if (cacheService != null && nodeId != null) {
            cacheService.evict(nodeCacheKey(nodeId));
        }
    }

    // ==================== Node CRUD ====================

    /** 创建节点（使用 nodeType 作为 Neo4j 标签） */
    public GraphNode createNode(GraphNode node) {
        try (Session session = neo4jDriver.session()) {
            String label = node.getNodeType();
            String cypher = String.format(
                    "CREATE (n:%s {id: $id, projectId: $projectId, versionId: $versionId, " +
                    "nodeKey: $nodeKey, nodeName: $nodeName, displayName: $displayName, " +
                    "description: $description, sourceType: $sourceType, sourcePath: $sourcePath, " +
                    "startLine: $startLine, endLine: $endLine, confidence: $confidence, " +
                    "status: $status, properties: $properties, " +
                    "verifiedScore: $verifiedScore, runtimeVerified: $runtimeVerified, " +
                    "lastSeenAt: $lastSeenAt, traceCount: $traceCount, " +
                    "createdAt: $createdAt, updatedAt: $updatedAt}) RETURN n",
                    label);
            Map<String, Object> params = nodeToParams(node);
            Result result = session.run(cypher, params);
            if (result.hasNext()) {
                log.debug("Created Neo4j node: type={}, key={}", label, node.getNodeKey());
            }
            return node;
        }
    }

    /**
     * 查找或创建节点（MERGE 原子去重）。
     * <p>
     * 一条 Cypher 完成原 findNode + createNode 两步，把每个节点的远程往返从 2 次降到 1 次。
     * 去重键与 {@link #findNode} 一致：(projectId, versionId, nodeKey)。
     *
     * @return 节点（id 取自库内已存在或新建节点）+ 是否本次新建的标志
     */
    public NodeUpsert mergeNode(GraphNode node) {
        String label = node.getNodeType();
        try (Session session = neo4jDriver.session()) {
            String cypher = String.format(
                    "MERGE (n:%s {projectId: $projectId, versionId: $versionId, nodeKey: $nodeKey}) " +
                    "ON CREATE SET n.id = $id, n.nodeName = $nodeName, n.displayName = $displayName, " +
                    "n.description = $description, n.sourceType = $sourceType, n.sourcePath = $sourcePath, " +
                    "n.startLine = $startLine, n.endLine = $endLine, n.confidence = $confidence, " +
                    "n.status = $status, n.properties = $properties, " +
                    "n.verifiedScore = $verifiedScore, n.runtimeVerified = $runtimeVerified, " +
                    "n.lastSeenAt = $lastSeenAt, n.traceCount = $traceCount, " +
                    "n.createdAt = $createdAt, n.updatedAt = $updatedAt " +
                    "ON MATCH SET n.updatedAt = $updatedAt " +
                    "RETURN n, (n.createdAt = $createdAt) AS created",
                    label);
            Map<String, Object> params = nodeToParams(node);
            org.neo4j.driver.Record record = session.run(cypher, params).single();
            GraphNode merged = recordToNode(record.get("n").asNode());
            boolean created = record.get("created").asBoolean();
            if (created) {
                log.debug("Merged-created Neo4j node: type={}, key={}", label, node.getNodeKey());
            }
            return new NodeUpsert(merged, created);
        }
    }

    /**
     * 设置 Neo4j 节点的单个属性（用于补偿标记如 writeStatus=INCOMPLETE）。
     */
    public void setNodeProperty(String nodeId, String property, Object value) {
        try (Session session = neo4jDriver.session()) {
            String cypher = "MATCH (n {id: $id}) SET n." + property + " = $value";
            session.run(cypher, Map.of("id", nodeId, "value", value));
        }
    }

    /**
     * 设置 Neo4j 关系的单个属性（用于补偿标记）。
     */
    public void setEdgeProperty(String edgeId, String property, Object value) {
        try (Session session = neo4jDriver.session()) {
            String cypher = "MATCH ()-[r {id: $id}]->() SET r." + property + " = $value";
            session.run(cypher, Map.of("id", edgeId, "value", value));
        }
    }

    /** mergeNode 的返回结果：节点 + 是否本次新建 */
    public record NodeUpsert(GraphNode node, boolean created) {}

    /** 查找节点（projectId + versionId + nodeType + nodeKey 唯一约束） */
    public Optional<GraphNode> findNode(String projectId, String versionId, String nodeType, String nodeKey) {
        try (Session session = neo4jDriver.session()) {
            String cypher = String.format(
                    "MATCH (n:%s {projectId: $projectId, versionId: $versionId, nodeKey: $nodeKey}) RETURN n LIMIT 1",
                    nodeType);
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
                // versionId 为空时仍需此参数占位
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

    /** 更新节点属性 */
    public void updateNode(GraphNode node) {
        try (Session session = neo4jDriver.session()) {
            String cypher = "MATCH (n {id: $id}) SET " +
                    "n.verifiedScore = $verifiedScore, " +
                    "n.runtimeVerified = $runtimeVerified, " +
                    "n.lastSeenAt = $lastSeenAt, " +
                    "n.traceCount = $traceCount, " +
                    "n.status = $status, " +
                    "n.confidence = $confidence, " +
                    "n.updatedAt = $updatedAt";
            session.run(cypher, Map.of(
                    "id", node.getId(),
                    "verifiedScore", node.getVerifiedScore() != null ? node.getVerifiedScore().doubleValue() : 0.0,
                    "runtimeVerified", node.getRuntimeVerified() != null ? node.getRuntimeVerified() : false,
                    "lastSeenAt", node.getLastSeenAt() != null ? node.getLastSeenAt().toString() : null,
                    "traceCount", node.getTraceCount() != null ? (long) node.getTraceCount() : 0L,
                    "status", node.getStatus() != null ? node.getStatus() : "",
                    "confidence", node.getConfidence() != null ? node.getConfidence().doubleValue() : 1.0,
                    "updatedAt", LocalDateTime.now().toString()
            ));
        }
        evictNodeCache(node.getId());
    }

    // ==================== Edge CRUD ====================

    /** 创建边（关系） */
    public GraphEdge createEdge(GraphEdge edge) {
        try (Session session = neo4jDriver.session()) {
            String cypher = String.format(
                    "MATCH (from {id: $fromId}) " +
                    "MATCH (to {id: $toId}) " +
                    "CREATE (from)-[r:%s {id: $id, projectId: $projectId, versionId: $versionId, " +
                    "edgeKey: $edgeKey, edgeType: $edgeType, sourceType: $sourceType, " +
                    "confidence: $confidence, status: $status, properties: $properties, " +
                    "evidenceIds: $evidenceIds, relationStatus: $relationStatus, " +
                    "createdAt: $createdAt, updatedAt: $updatedAt}]->(to) RETURN r",
                    edge.getEdgeType());
            Map<String, Object> params = new HashMap<>();
            params.put("id", edge.getId());
            params.put("fromId", edge.getFromNodeId());
            params.put("toId", edge.getToNodeId());
            params.put("projectId", edge.getProjectId());
            params.put("versionId", normalizeId(edge.getVersionId()));
            params.put("edgeKey", edge.getEdgeKey() != null ? edge.getEdgeKey() : "");
            params.put("edgeType", edge.getEdgeType());
            params.put("sourceType", edge.getSourceType() != null ? edge.getSourceType() : "");
            params.put("confidence", edge.getConfidence() != null ? edge.getConfidence().doubleValue() : 1.0);
            params.put("status", edge.getStatus() != null ? edge.getStatus() : "");
            params.put("properties", edge.getProperties() != null ? edge.getProperties() : "{}");
            params.put("evidenceIds", edge.getEvidenceIds() != null ? edge.getEvidenceIds() : "");
            params.put("relationStatus", edge.getRelationStatus() != null ? edge.getRelationStatus() : "");
            params.put("createdAt", edge.getCreatedAt() != null ? edge.getCreatedAt().toString() : LocalDateTime.now().toString());
            params.put("updatedAt", edge.getUpdatedAt() != null ? edge.getUpdatedAt().toString() : LocalDateTime.now().toString());
            session.run(cypher, params);
            return edge;
        }
    }

    /**
     * 查找或创建边（MERGE 原子去重）。
     * <p>
     * 一条 Cypher 完成原 findEdge + createEdge 两步，把每条边的远程往返从 2 次降到 1 次。
     * 去重键与 {@link #findEdge} 一致：(projectId, versionId, edgeKey) + 两端节点 id。
     *
     * @return 边（两端节点 id 取自库内）+ 是否本次新建的标志；from/to 节点不存在时返回 null
     */
    public EdgeUpsert mergeEdge(GraphEdge edge) {
        String edgeType = edge.getEdgeType();
        try (Session session = neo4jDriver.session()) {
            String cypher = String.format(
                    "MATCH (from {id: $fromId}), (to {id: $toId}) " +
                    "MERGE (from)-[r:%s {projectId: $projectId, versionId: $versionId, edgeKey: $edgeKey}]->(to) " +
                    "ON CREATE SET r.id = $id, r.edgeType = $edgeType, r.sourceType = $sourceType, " +
                    "r.confidence = $confidence, r.status = $status, r.properties = $properties, " +
                    "r.evidenceIds = $evidenceIds, r.relationStatus = $relationStatus, " +
                    "r.createdAt = $createdAt, r.updatedAt = $updatedAt " +
                    "ON MATCH SET r.updatedAt = $updatedAt " +
                    "RETURN r, from, to, (r.createdAt = $createdAt) AS created",
                    edgeType);
            Map<String, Object> params = new HashMap<>();
            params.put("id", edge.getId());
            params.put("fromId", edge.getFromNodeId());
            params.put("toId", edge.getToNodeId());
            params.put("projectId", edge.getProjectId());
            params.put("versionId", normalizeId(edge.getVersionId()));
            params.put("edgeKey", edge.getEdgeKey() != null ? edge.getEdgeKey() : "");
            params.put("edgeType", edgeType);
            params.put("sourceType", edge.getSourceType() != null ? edge.getSourceType() : "");
            params.put("confidence", edge.getConfidence() != null ? edge.getConfidence().doubleValue() : 1.0);
            params.put("status", edge.getStatus() != null ? edge.getStatus() : "");
            params.put("properties", edge.getProperties() != null ? edge.getProperties() : "{}");
            params.put("evidenceIds", edge.getEvidenceIds() != null ? edge.getEvidenceIds() : "");
            params.put("relationStatus", edge.getRelationStatus() != null ? edge.getRelationStatus() : "");
            params.put("createdAt", edge.getCreatedAt() != null ? edge.getCreatedAt().toString() : LocalDateTime.now().toString());
            params.put("updatedAt", edge.getUpdatedAt() != null ? edge.getUpdatedAt().toString() : LocalDateTime.now().toString());
            Result result = session.run(cypher, params);
            if (!result.hasNext()) {
                // from/to 节点不存在，无法建边
                return new EdgeUpsert(null, false);
            }
            org.neo4j.driver.Record record = result.next();
            GraphEdge merged = recordToEdge(
                    record.get("r").asRelationship(),
                    record.get("from").asNode(),
                    record.get("to").asNode());
            boolean created = record.get("created").asBoolean();
            return new EdgeUpsert(merged, created);
        }
    }

    /** mergeEdge 的返回结果：边 + 是否本次新建 */
    public record EdgeUpsert(GraphEdge edge, boolean created) {}

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
     * 一次查询获取项目图统计信息（节点数、边数、确认率、置信度等）。
     * 使用单次 Cypher 聚合避免多次往返和全量节点加载，显著提升概览接口性能。
     */
    public Map<String, Object> graphStats(String projectId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId " +
                "WITH count(n) AS totalNodes, " +
                "     count(CASE WHEN n.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmedNodes, " +
                "     count(CASE WHEN n.status IN ['PENDING', 'PENDING_CONFIRM'] THEN 1 END) AS pendingNodes, " +
                "     coalesce(avg(n.confidence), 0.0) AS avgConfidence, " +
                "     count(CASE WHEN n.evidenceIds IS NOT NULL AND n.evidenceIds <> '' THEN 1 END) AS withEvidenceCount, " +
                "     count(CASE WHEN n.evidenceIds IS NULL OR n.evidenceIds = '' THEN 1 END) AS noEvidenceNodes, " +
                "     count(CASE WHEN n.sourceType IN ['AI_INFERENCE', 'DOC_AI'] THEN 1 END) AS aiOnlyNodes " +
                "OPTIONAL MATCH ()-[r]->() WHERE r.projectId = $projectId " +
                "RETURN totalNodes, confirmedNodes, pendingNodes, avgConfidence, withEvidenceCount, noEvidenceNodes, aiOnlyNodes, " +
                "       count(r) AS totalEdges, " +
                "       count(CASE WHEN r.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmedEdges, " +
                "       count(CASE WHEN r.status IN ['PENDING', 'PENDING_CONFIRM'] THEN 1 END) AS pendingEdges, " +
                "       count(CASE WHEN r.evidenceIds IS NULL OR r.evidenceIds = '' THEN 1 END) AS noEvidenceEdges, " +
                "       count(CASE WHEN r.sourceType IN ['AI_INFERENCE', 'AI_FEATURE_MAPPING', 'DOC_AI'] THEN 1 END) AS aiOnlyEdges, " +
                "       count(CASE WHEN r.sourceType = 'RUNTIME_TRACE' THEN 1 END) AS runtimeOnlyEdges";
            Result result = session.run(cypher, Map.of("projectId", projectId));
            if (result.hasNext()) {
                return result.next().asMap();
            }
        }
        Map<String, Object> emptyStats = new LinkedHashMap<>();
        emptyStats.put("totalNodes", 0L);
        emptyStats.put("confirmedNodes", 0L);
        emptyStats.put("pendingNodes", 0L);
        emptyStats.put("avgConfidence", 0.0);
        emptyStats.put("withEvidenceCount", 0L);
        emptyStats.put("noEvidenceNodes", 0L);
        emptyStats.put("aiOnlyNodes", 0L);
        emptyStats.put("totalEdges", 0L);
        emptyStats.put("confirmedEdges", 0L);
        emptyStats.put("pendingEdges", 0L);
        emptyStats.put("noEvidenceEdges", 0L);
        emptyStats.put("aiOnlyEdges", 0L);
        emptyStats.put("runtimeOnlyEdges", 0L);
        return emptyStats;
    }

    /**
     * 按节点类型聚合统计（用于迁移就绪度报告）。
     * 单次 Cypher 聚合替代全量节点加载，避免超时。
     * @return [{nodeType, displayName, total, confirmed, avgConfidence}]
     */
    public List<Map<String, Object>> nodeTypeStats(String projectId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId " +
                "UNWIND labels(n) AS label " +
                "WITH label AS nodeType, " +
                "     count(*) AS total, " +
                "     count(CASE WHEN n.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmed, " +
                "     coalesce(avg(n.confidence), 0.0) AS avgConfidence " +
                "RETURN nodeType, total, confirmed, avgConfidence " +
                "ORDER BY total DESC";
            Result result = session.run(cypher, Map.of("projectId", projectId));
            List<Map<String, Object>> stats = new ArrayList<>();
            while (result.hasNext()) {
                stats.add(result.next().asMap());
            }
            return stats;
        }
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

    /** 更新边 */
    public void updateEdge(GraphEdge edge) {
        try (Session session = neo4jDriver.session()) {
            String cypher = "MATCH ()-[r {id: $id}]->() SET " +
                    "r.status = $status, " +
                    "r.confidence = $confidence, " +
                    "r.relationStatus = $relationStatus, " +
                    "r.properties = $properties, " +
                    "r.updatedAt = $updatedAt";
            session.run(cypher, Map.of(
                    "id", edge.getId(),
                    "status", edge.getStatus() != null ? edge.getStatus() : "",
                    "confidence", edge.getConfidence() != null ? edge.getConfidence().doubleValue() : 1.0,
                    "relationStatus", edge.getRelationStatus() != null ? edge.getRelationStatus() : "",
                    "properties", edge.getProperties() != null ? edge.getProperties() : "{}",
                    "updatedAt", LocalDateTime.now().toString()
            ));
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
            // B-S2: 必须同时取两端节点，用其应用 id 属性（UUID），
            // 而非 rel.startNodeElementId()/endNodeElementId()（Neo4j 内部 elementId 形如 "4:xxx:0"）。
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

    // ==================== 报告聚合查询（避免全量加载节点/边导致超时） ====================

    /**
     * 版本维度的图统计（类似 graphStats，但限定 versionId）。
     */
    public Map<String, Object> versionGraphStats(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "WITH count(n) AS totalNodes, " +
                "     count(CASE WHEN n.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmedNodes, " +
                "     count(CASE WHEN n.status IN ['PENDING', 'PENDING_CONFIRM'] THEN 1 END) AS pendingNodes, " +
                "     coalesce(avg(n.confidence), 0.0) AS avgConfidence, " +
                "     count(CASE WHEN n.evidenceIds IS NOT NULL AND n.evidenceIds <> '' THEN 1 END) AS withEvidenceCount, " +
                "     count(CASE WHEN n.evidenceIds IS NULL OR n.evidenceIds = '' THEN 1 END) AS noEvidenceNodes, " +
                "     count(CASE WHEN n.sourceType IN ['AI_INFERENCE', 'DOC_AI'] THEN 1 END) AS aiOnlyNodes, " +
                "     count(CASE WHEN coalesce(n.verifiedScore, 0.0) > 0 THEN 1 END) AS runtimeVerifiedCount " +
                "OPTIONAL MATCH ()-[r]->() WHERE r.projectId = $projectId AND r.versionId = $versionId " +
                "RETURN totalNodes, confirmedNodes, pendingNodes, avgConfidence, withEvidenceCount, noEvidenceNodes, aiOnlyNodes, runtimeVerifiedCount, " +
                "       count(r) AS totalEdges, " +
                "       count(CASE WHEN r.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmedEdges, " +
                "       count(CASE WHEN r.status IN ['PENDING', 'PENDING_CONFIRM'] THEN 1 END) AS pendingEdges, " +
                "       count(CASE WHEN r.evidenceIds IS NULL OR r.evidenceIds = '' THEN 1 END) AS noEvidenceEdges, " +
                "       count(CASE WHEN r.sourceType IN ['AI_INFERENCE', 'AI_FEATURE_MAPPING', 'DOC_AI'] THEN 1 END) AS aiOnlyEdges, " +
                "       count(CASE WHEN r.sourceType = 'RUNTIME_TRACE' THEN 1 END) AS runtimeOnlyEdges";
            Result result = session.run(cypher, Map.of("projectId", projectId, "versionId", normalizeId(versionId)));
            if (result.hasNext()) return result.next().asMap();
        }
        Map<String, Object> emptyStats = new LinkedHashMap<>();
        emptyStats.put("totalNodes", 0L);
        emptyStats.put("confirmedNodes", 0L);
        emptyStats.put("pendingNodes", 0L);
        emptyStats.put("avgConfidence", 0.0);
        emptyStats.put("withEvidenceCount", 0L);
        emptyStats.put("noEvidenceNodes", 0L);
        emptyStats.put("aiOnlyNodes", 0L);
        emptyStats.put("runtimeVerifiedCount", 0L);
        emptyStats.put("totalEdges", 0L);
        emptyStats.put("confirmedEdges", 0L);
        emptyStats.put("pendingEdges", 0L);
        emptyStats.put("noEvidenceEdges", 0L);
        emptyStats.put("aiOnlyEdges", 0L);
        emptyStats.put("runtimeOnlyEdges", 0L);
        return emptyStats;
    }

    /**
     * 每日置信度趋势聚合（按节点创建日期分组）。
     */
    public List<Map<String, Object>> confidenceTrendDaily(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "RETURN date(n.createdAt) AS date, " +
                "       coalesce(avg(n.confidence), 0.0) AS avgConfidence, " +
                "       count(*) AS newNodes, " +
                "       count(CASE WHEN n.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmedNodes " +
                "ORDER BY date";
            Result result = session.run(cypher, Map.of("projectId", projectId, "versionId", normalizeId(versionId)));
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.hasNext()) rows.add(result.next().asMap());
            return rows;
        }
    }

    /**
     * 置信度分布直方图（5 个区间: 0-0.2, 0.2-0.4, 0.4-0.6, 0.6-0.8, 0.8-1.0）。
     */
    public List<Map<String, Object>> confidenceDistribution(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "WITH coalesce(n.confidence, 0.0) AS c " +
                "RETURN " +
                "  sum(CASE WHEN c >= 0.0 AND c < 0.2 THEN 1 ELSE 0 END) AS bin0, " +
                "  sum(CASE WHEN c >= 0.2 AND c < 0.4 THEN 1 ELSE 0 END) AS bin1, " +
                "  sum(CASE WHEN c >= 0.4 AND c < 0.6 THEN 1 ELSE 0 END) AS bin2, " +
                "  sum(CASE WHEN c >= 0.6 AND c < 0.8 THEN 1 ELSE 0 END) AS bin3, " +
                "  sum(CASE WHEN c >= 0.8 AND c <= 1.0 THEN 1 ELSE 0 END) AS bin4";
            Result result = session.run(cypher, Map.of("projectId", projectId, "versionId", normalizeId(versionId)));
            if (result.hasNext()) {
                List<Map<String, Object>> list = new ArrayList<>();
                list.add(result.next().asMap());
                return list;
            }
            return List.of(Map.of("bin0", 0L, "bin1", 0L, "bin2", 0L, "bin3", 0L, "bin4", 0L));
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
     * 平均节点度数（版本维度）。
     */
    public double averageNodeDegree(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n)-[r]-() WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "AND r.projectId = $projectId AND r.versionId = $versionId " +
                "WITH n, count(r) AS degree " +
                "RETURN coalesce(avg(degree), 0.0) AS avgDegree";
            Result result = session.run(cypher, Map.of("projectId", projectId, "versionId", normalizeId(versionId)));
            if (result.hasNext()) {
                Object v = result.next().get("avgDegree");
                if (v instanceof Number n) return n.doubleValue();
            }
        }
        return 0.0;
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

    // ==================== 批量操作 ====================

    /** 删除指定版本的整个子图（DETACH DELETE） */
    public void deleteGraph(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            session.run(
                    "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId DETACH DELETE n",
                    Map.of("projectId", projectId, "versionId", normalizeId(versionId)));
            log.info("Deleted Neo4j graph: projectId={}, versionId={}", projectId, versionId);
        }
    }

    /** 删除单个节点 */
    public void deleteNode(String projectId, String versionId, String nodeId) {
        try (Session session = neo4jDriver.session()) {
            session.run(
                    "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId AND n.id = $nodeId DETACH DELETE n",
                    Map.of("projectId", projectId, "versionId", normalizeId(versionId), "nodeId", nodeId));
        }
        evictNodeCache(nodeId);
    }

    /**
     * 将所有从旧节点出发的边重新连接到新节点。
     * 用于图谱合并：将 mergeNode 的边迁移到 targetNode。
     */
    public void updateEdgeFromNode(String oldNodeId, String newNodeId, String projectId) {
        try (Session session = neo4jDriver.session()) {
            // 查询所有从 oldNode 出发的边，然后删除旧边、创建新边
            List<org.neo4j.driver.Record> records = session.run(
                    "MATCH (old {id: $oldNodeId})-[r]->(to) WHERE r.projectId = $projectId " +
                    "RETURN r, to, type(r) AS relType, properties(r) AS props",
                    Map.of("oldNodeId", oldNodeId, "projectId", projectId)
            ).list();

            for (org.neo4j.driver.Record rec : records) {
                String relType = rec.get("relType").asString();
                @SuppressWarnings("unchecked")
                Map<String, Object> props = rec.get("props").asMap();
                String toId = rec.get("to").get("id").asString();

                Map<String, Object> params = new HashMap<>(props);
                params.put("newNodeId", newNodeId);
                params.put("toId", toId);
                params.put("updatedAt", LocalDateTime.now().toString());

                // 创建新边并删除旧边
                session.run(
                        "MATCH (newFrom {id: $newNodeId}) " +
                        "MATCH (to {id: $toId}) " +
                        "CREATE (newFrom)-[r2:" + relType + "]->(to) SET r2 = $props, r2.updatedAt = $updatedAt",
                        params
                );
                session.run(
                        "MATCH (old {id: $oldNodeId})-[r]->(to {id: $toId}) WHERE r.projectId = $projectId DELETE r",
                        Map.of("oldNodeId", oldNodeId, "toId", toId, "projectId", projectId)
                );
            }
            log.debug("Rewired {} edges from node {} to {}", records.size(), oldNodeId, newNodeId);
        }
    }

    /**
     * 将所有指向旧节点的边重新指向新节点。
     * 用于图谱合并：将 mergeNode 的入边迁移到 targetNode。
     */
    public void updateEdgeToNode(String oldNodeId, String newNodeId, String projectId) {
        try (Session session = neo4jDriver.session()) {
            List<org.neo4j.driver.Record> records = session.run(
                    "MATCH (from)-[r]->(old {id: $oldNodeId}) WHERE r.projectId = $projectId " +
                    "RETURN r, from, type(r) AS relType, properties(r) AS props",
                    Map.of("oldNodeId", oldNodeId, "projectId", projectId)
            ).list();

            for (org.neo4j.driver.Record rec : records) {
                String relType = rec.get("relType").asString();
                @SuppressWarnings("unchecked")
                Map<String, Object> props = rec.get("props").asMap();
                String fromId = rec.get("from").get("id").asString();

                Map<String, Object> params = new HashMap<>(props);
                params.put("fromId", fromId);
                params.put("newNodeId", newNodeId);
                params.put("updatedAt", LocalDateTime.now().toString());

                session.run(
                        "MATCH (from {id: $fromId}) " +
                        "MATCH (newTo {id: $newNodeId}) " +
                        "CREATE (from)-[r2:" + relType + "]->(newTo) SET r2 = $props, r2.updatedAt = $updatedAt",
                        params
                );
                session.run(
                        "MATCH (from {id: $fromId})-[r]->(old {id: $oldNodeId}) WHERE r.projectId = $projectId DELETE r",
                        Map.of("fromId", fromId, "oldNodeId", oldNodeId, "projectId", projectId)
                );
            }
            log.debug("Rewired {} edges to node {} from {}", records.size(), oldNodeId, newNodeId);
        }
    }

    /** 创建约束（服务启动时初始化）。覆盖 NodeType 全部类型，避免新增类型遗漏索引导致全标签扫描。 */
    public void createConstraints() {
        try (Session session = neo4jDriver.session()) {
            for (NodeType type : NodeType.values()) {
                String label = type.name();
                String cypher = String.format(
                        "CREATE CONSTRAINT %s_id_key IF NOT EXISTS FOR (n:%s) REQUIRE n.id IS UNIQUE",
                        label.toLowerCase(), label);
                session.run(cypher);
            }
            log.info("Created Neo4j constraints for {} node types", NodeType.values().length);
        }
    }

    /** 创建索引（Neo4j 5.x 要求 FOR (n:Label) 语法）。覆盖 NodeType 全部类型。 */
    public void createIndexes() {
        try (Session session = neo4jDriver.session()) {
            for (NodeType type : NodeType.values()) {
                String label = type.name();
                // 复合索引：加速按 projectId + versionId 查询
                session.run(String.format(
                        "CREATE INDEX IF NOT EXISTS FOR (n:%s) ON (n.projectId, n.versionId)", label));
                // nodeKey 索引：加速按 nodeKey 点查（MERGE/去重的关键索引）
                session.run(String.format(
                        "CREATE INDEX IF NOT EXISTS FOR (n:%s) ON (n.nodeKey)", label));
            }
            // id 属性已在 createConstraints 中通过 UNIQUE 约束自动索引，无需重复建
            log.info("Created Neo4j indexes for {} node types", NodeType.values().length);
        }
    }

    // ==================== 内部转换 ====================

    private Map<String, Object> nodeToParams(GraphNode node) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", node.getId());
        params.put("projectId", node.getProjectId());
        params.put("versionId", normalizeId(node.getVersionId()));
        params.put("nodeKey", node.getNodeKey());
        params.put("nodeName", node.getNodeName() != null ? node.getNodeName() : "");
        params.put("displayName", node.getDisplayName() != null ? node.getDisplayName() : "");
        params.put("description", node.getDescription() != null ? node.getDescription() : "");
        params.put("sourceType", node.getSourceType() != null ? node.getSourceType() : "");
        params.put("sourcePath", node.getSourcePath() != null ? node.getSourcePath() : "");
        params.put("startLine", node.getStartLine() != null ? (long) node.getStartLine() : null);
        params.put("endLine", node.getEndLine() != null ? (long) node.getEndLine() : null);
        params.put("confidence", node.getConfidence() != null ? node.getConfidence().doubleValue() : 1.0);
        params.put("status", node.getStatus() != null ? node.getStatus() : "");
        params.put("properties", node.getProperties() != null ? node.getProperties() : "{}");
        params.put("verifiedScore", node.getVerifiedScore() != null ? node.getVerifiedScore().doubleValue() : 0.0);
        params.put("runtimeVerified", node.getRuntimeVerified() != null ? node.getRuntimeVerified() : false);
        params.put("lastSeenAt", node.getLastSeenAt() != null ? node.getLastSeenAt().toString() : null);
        params.put("traceCount", node.getTraceCount() != null ? (long) node.getTraceCount() : 0L);
        params.put("createdAt", node.getCreatedAt() != null ? node.getCreatedAt().toString() : LocalDateTime.now().toString());
        params.put("updatedAt", node.getUpdatedAt() != null ? node.getUpdatedAt().toString() : LocalDateTime.now().toString());
        return params;
    }

    private GraphNode recordToNode(org.neo4j.driver.types.Node neoNode) {
        GraphNode node = new GraphNode();
        Map<String, Object> props = neoNode.asMap();
        node.setId((String) props.get("id"));
        node.setProjectId((String) props.get("projectId"));
        node.setVersionId((String) props.get("versionId"));
        node.setNodeType(neoNode.labels().iterator().next());
        node.setNodeKey((String) props.get("nodeKey"));
        node.setNodeName((String) props.get("nodeName"));
        node.setDisplayName((String) props.get("displayName"));
        node.setDescription((String) props.get("description"));
        node.setSourceType((String) props.get("sourceType"));
        node.setSourcePath((String) props.get("sourcePath"));
        Object sl = props.get("startLine");
        if (sl instanceof Long) node.setStartLine(((Long) sl).intValue());
        Object el = props.get("endLine");
        if (el instanceof Long) node.setEndLine(((Long) el).intValue());
        Object conf = props.get("confidence");
        if (conf instanceof Double) node.setConfidence(BigDecimal.valueOf((Double) conf));
        else if (conf instanceof String) node.setConfidence(new BigDecimal((String) conf));
        node.setStatus((String) props.get("status"));
        node.setProperties((String) props.get("properties"));
        Object vs = props.get("verifiedScore");
        if (vs instanceof Double) node.setVerifiedScore(BigDecimal.valueOf((Double) vs));
        else if (vs instanceof String) node.setVerifiedScore(new BigDecimal((String) vs));
        node.setRuntimeVerified((Boolean) props.get("runtimeVerified"));
        Object lsa = props.get("lastSeenAt");
        if (lsa instanceof String) node.setLastSeenAt(LocalDateTime.parse((String) lsa));
        Object tc = props.get("traceCount");
        if (tc instanceof Long) node.setTraceCount(((Long) tc).intValue());
        Object ca = props.get("createdAt");
        if (ca instanceof String) node.setCreatedAt(LocalDateTime.parse((String) ca));
        Object ua = props.get("updatedAt");
        if (ua instanceof String) node.setUpdatedAt(LocalDateTime.parse((String) ua));
        return node;
    }

    /** 从关系+节点构建 GraphEdge（使用节点 id 属性而不是 elementId） */
    private GraphEdge recordToEdge(org.neo4j.driver.types.Relationship rel,
                                   org.neo4j.driver.types.Node fromNode,
                                   org.neo4j.driver.types.Node toNode) {
        GraphEdge edge = new GraphEdge();
        Map<String, Object> props = rel.asMap();
        edge.setId((String) props.get("id"));
        edge.setProjectId((String) props.get("projectId"));
        edge.setVersionId((String) props.get("versionId"));
        edge.setFromNodeId((String) fromNode.asMap().get("id"));
        edge.setToNodeId((String) toNode.asMap().get("id"));
        edge.setEdgeType(rel.type());
        edge.setEdgeKey((String) props.get("edgeKey"));
        edge.setSourceType((String) props.get("sourceType"));
        Object conf = props.get("confidence");
        if (conf instanceof Double) edge.setConfidence(BigDecimal.valueOf((Double) conf));
        else if (conf instanceof String) edge.setConfidence(new BigDecimal((String) conf));
        edge.setStatus((String) props.get("status"));
        edge.setProperties((String) props.get("properties"));
        edge.setEvidenceIds((String) props.get("evidenceIds"));
        edge.setRelationStatus((String) props.get("relationStatus"));
        Object ca = props.get("createdAt");
        if (ca instanceof String) edge.setCreatedAt(LocalDateTime.parse((String) ca));
        Object ua = props.get("updatedAt");
        if (ua instanceof String) edge.setUpdatedAt(LocalDateTime.parse((String) ua));
        return edge;
    }
}
