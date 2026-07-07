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

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static io.github.legacygraph.dao.Neo4jConversions.*;

/**
 * Neo4j 写入 Repository — 负责节点/边的创建、MERGE、更新、批量写入。
 */
@Slf4j
@Component
public class Neo4jWriteRepository {

    private final Driver neo4jDriver;

    @Autowired(required = false)
    private CacheService cacheService;

    /** 批量 MERGE 批次大小（避免单事务过大） */
    private static final int BATCH_MERGE_SIZE = 500;

    public Neo4jWriteRepository(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    // ==================== records ====================

    /** mergeNode 的返回结果：节点 + 是否本次新建 */
    public record NodeUpsert(GraphNode node, boolean created) {}

    /** mergeEdge 的返回结果：边 + 是否本次新建 */
    public record EdgeUpsert(GraphEdge edge, boolean created) {}

    /** 批量写入节点的描述 record */
    public record BatchNodeUpsert(String nodeType,
                                  String nodeKey,
                                  String nodeName,
                                  Map<String, Object> properties) {}

    /** 批量写入边的描述 record */
    public record BatchEdgeUpsert(String fromNodeId,
                                  String toNodeId,
                                  String edgeType,
                                  String edgeKey,
                                  Map<String, Object> properties) {}

    // ==================== Node CRUD ====================

    /** 创建节点（使用 nodeType 作为 Neo4j 标签） */
    public GraphNode createNode(GraphNode node) {
        try (Session session = neo4jDriver.session()) {
            String label = CypherCatalog.safeIdentifier(node.getNodeType(), "nodeType");
            String cypher = String.format(
                    "CREATE (n:%s {id: $id, projectId: $projectId, versionId: $versionId, " +
                    "nodeType: $nodeType, nodeKey: $nodeKey, nodeName: $nodeName, displayName: $displayName, " +
                    "description: $description, sourceType: $sourceType, sourcePath: $sourcePath, " +
                    "startLine: $startLine, endLine: $endLine, confidence: $confidence, " +
                    "status: $status, properties: $properties, scanType: $scanType, className: $className, " +
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
     *
     * @return 节点（id 取自库内已存在或新建节点）+ 是否本次新建的标志
     */
    public NodeUpsert mergeNode(GraphNode node) {
        String label = CypherCatalog.safeIdentifier(node.getNodeType(), "nodeType");
        try (Session session = neo4jDriver.session()) {
            String cypher = String.format(
                    "MERGE (n:%s {projectId: $projectId, versionId: $versionId, nodeKey: $nodeKey}) " +
                    "ON CREATE SET n.id = $id, n.nodeType = $nodeType, n.nodeName = $nodeName, n.displayName = $displayName, " +
                    "n.description = $description, n.sourceType = $sourceType, n.sourcePath = $sourcePath, " +
                    "n.startLine = $startLine, n.endLine = $endLine, n.confidence = $confidence, " +
                    "n.status = $status, n.properties = $properties, n.scanType = $scanType, n.className = $className, " +
                    "n.verifiedScore = $verifiedScore, n.runtimeVerified = $runtimeVerified, " +
                    "n.lastSeenAt = $lastSeenAt, n.traceCount = $traceCount, " +
                    "n.createdAt = $createdAt, n.updatedAt = $updatedAt " +
                    "ON MATCH SET n.updatedAt = $updatedAt, n.nodeType = $nodeType, n.scanType = $scanType, n.className = $className " +
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
            String safeProperty = CypherCatalog.safeIdentifier(property, "nodeProperty");
            String cypher = "MATCH (n {id: $id}) SET n." + safeProperty + " = $value";
            session.run(cypher, Map.of("id", nodeId, "value", value));
        }
    }

    /**
     * 设置 Neo4j 关系的单个属性（用于补偿标记）。
     */
    public void setEdgeProperty(String edgeId, String property, Object value) {
        try (Session session = neo4jDriver.session()) {
            String safeProperty = CypherCatalog.safeIdentifier(property, "edgeProperty");
            String cypher = "MATCH ()-[r {id: $id}]->() SET r." + safeProperty + " = $value";
            session.run(cypher, Map.of("id", edgeId, "value", value));
        }
    }

    /**
     * 批量 MERGE 节点（单次 UNWIND 替代逐条 MERGE，大幅减少网络往返）。
     */
    public void mergeNodesBatch(String projectId, String versionId, List<BatchNodeUpsert> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        String cypher = """
                UNWIND $nodes AS n
                MERGE (node:GraphNode {projectId: $projectId, versionId: $versionId, nodeType: n.nodeType, nodeKey: n.nodeKey})
                SET node.nodeName = n.nodeName,
                    node += n.properties,
                    node.updatedAt = datetime()
                """;
        try (Session session = neo4jDriver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, Map.of(
                        "projectId", projectId,
                        "versionId", normalizeId(versionId),
                        "nodes", nodes.stream().map(this::batchNodeToMap).toList()));
                return null;
            });
        }
    }

    /**
     * 批量 MERGE 边（单次 UNWIND 替代逐条遍历）。
     */
    public void mergeEdgesBatch(String projectId, String versionId, List<BatchEdgeUpsert> edges) {
        if (edges == null || edges.isEmpty()) {
            return;
        }
        String cypher = """
                UNWIND $edges AS e
                MATCH (from {id: e.fromNodeId}), (to {id: e.toNodeId})
                MERGE (from)-[r:RELATES {projectId: $projectId, versionId: $versionId, edgeType: e.edgeType, edgeKey: e.edgeKey}]->(to)
                SET r += e.properties,
                    r.updatedAt = datetime()
                """;
        try (Session session = neo4jDriver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, Map.of(
                        "projectId", projectId,
                        "versionId", normalizeId(versionId),
                        "edges", edges.stream().map(this::batchEdgeToMap).toList()));
                return null;
            });
        }
    }

    // ==================== Edge CRUD ====================

    /** 创建边（关系） */
    public GraphEdge createEdge(GraphEdge edge) {
        try (Session session = neo4jDriver.session()) {
            String safeEdgeType = CypherCatalog.safeIdentifier(edge.getEdgeType(), "edgeType");
            String cypher = String.format(
                    "MATCH (from {id: $fromId}) " +
                    "MATCH (to {id: $toId}) " +
                    "CREATE (from)-[r:%s {id: $id, projectId: $projectId, versionId: $versionId, " +
                    "edgeKey: $edgeKey, edgeType: $edgeType, sourceType: $sourceType, " +
                    "confidence: $confidence, status: $status, properties: $properties, " +
                    "evidenceIds: $evidenceIds, relationStatus: $relationStatus, " +
                    "createdAt: $createdAt, updatedAt: $updatedAt}]->(to) RETURN r",
                    safeEdgeType);
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
     *
     * @return 边（两端节点 id 取自库内）+ 是否本次新建的标志；from/to 节点不存在时返回 null
     */
    public EdgeUpsert mergeEdge(GraphEdge edge) {
        String edgeType = CypherCatalog.safeIdentifier(edge.getEdgeType(), "edgeType");
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
        evictNodeCache(cacheService, node.getId());
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

    // ==================== 批量 MERGE ====================

    /**
     * 批量合并节点（UNWIND 单次 Cypher）。
     *
     * @return 成功写入的节点数
     */
    public int mergeNodesBatch(List<GraphNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return 0;

        Map<String, List<GraphNode>> byType = nodes.stream()
                .filter(n -> n.getNodeType() != null)
                .collect(Collectors.groupingBy(GraphNode::getNodeType));

        int total = 0;
        for (var entry : byType.entrySet()) {
            String label = CypherCatalog.safeIdentifier(entry.getKey(), "nodeType");
            List<GraphNode> group = entry.getValue();
            total += mergeNodesBatchForLabel(label, group);
        }
        return total;
    }

    /**
     * 批量合并边（UNWIND 单次 Cypher）。
     *
     * @return 成功写入的边数
     */
    public int mergeEdgesBatch(List<GraphEdge> edges) {
        if (edges == null || edges.isEmpty()) return 0;

        Map<String, List<GraphEdge>> byType = edges.stream()
                .filter(e -> e.getEdgeType() != null)
                .collect(Collectors.groupingBy(GraphEdge::getEdgeType));

        int total = 0;
        for (var entry : byType.entrySet()) {
            total += mergeEdgesBatchForType(
                    CypherCatalog.safeIdentifier(entry.getKey(), "edgeType"),
                    entry.getValue());
        }
        return total;
    }

    /**
     * 将所有从旧节点出发的边重新连接到新节点。
     */
    public void updateEdgeFromNode(String oldNodeId, String newNodeId, String projectId) {
        try (Session session = neo4jDriver.session()) {
            List<org.neo4j.driver.Record> records = session.run(
                    "MATCH (old {id: $oldNodeId})-[r]->(to) WHERE r.projectId = $projectId " +
                    "RETURN r, to, type(r) AS relType, properties(r) AS props",
                    Map.of("oldNodeId", oldNodeId, "projectId", projectId)
            ).list();

            for (org.neo4j.driver.Record rec : records) {
                String relType = CypherCatalog.safeIdentifier(rec.get("relType").asString(), "edgeType");
                @SuppressWarnings("unchecked")
                Map<String, Object> props = rec.get("props").asMap();
                String toId = rec.get("to").get("id").asString();

                Map<String, Object> params = new HashMap<>(props);
                params.put("newNodeId", newNodeId);
                params.put("toId", toId);
                params.put("updatedAt", LocalDateTime.now().toString());

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
     */
    public void updateEdgeToNode(String oldNodeId, String newNodeId, String projectId) {
        try (Session session = neo4jDriver.session()) {
            List<org.neo4j.driver.Record> records = session.run(
                    "MATCH (from)-[r]->(old {id: $oldNodeId}) WHERE r.projectId = $projectId " +
                    "RETURN r, from, type(r) AS relType, properties(r) AS props",
                    Map.of("oldNodeId", oldNodeId, "projectId", projectId)
            ).list();

            for (org.neo4j.driver.Record rec : records) {
                String relType = CypherCatalog.safeIdentifier(rec.get("relType").asString(), "edgeType");
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

    // ==================== private helpers ====================

    private Map<String, Object> batchNodeToMap(BatchNodeUpsert n) {
        Map<String, Object> map = new HashMap<>();
        map.put("nodeType", n.nodeType());
        map.put("nodeKey", n.nodeKey());
        map.put("nodeName", n.nodeName() != null ? n.nodeName() : "");
        if (n.properties() != null) {
            map.putAll(n.properties());
        }
        return map;
    }

    private Map<String, Object> batchEdgeToMap(BatchEdgeUpsert e) {
        Map<String, Object> map = new HashMap<>();
        map.put("fromNodeId", e.fromNodeId());
        map.put("toNodeId", e.toNodeId());
        map.put("edgeType", e.edgeType());
        map.put("edgeKey", e.edgeKey() != null ? e.edgeKey() : "");
        if (e.properties() != null) {
            map.putAll(e.properties());
        }
        return map;
    }

    private int mergeNodesBatchForLabel(String label, List<GraphNode> nodes) {
        int count = 0;
        for (int i = 0; i < nodes.size(); i += BATCH_MERGE_SIZE) {
            int end = Math.min(i + BATCH_MERGE_SIZE, nodes.size());
            List<GraphNode> batch = nodes.subList(i, end);
            count += mergeNodesSubBatch(label, batch);
        }
        return count;
    }

    private int mergeNodesSubBatch(String label, List<GraphNode> batch) {
        try (Session session = neo4jDriver.session()) {
            List<Map<String, Object>> rows = new ArrayList<>(batch.size());
            for (GraphNode node : batch) {
                Map<String, Object> row = new HashMap<>();
                row.put("projectId", node.getProjectId());
                row.put("versionId", normalizeId(node.getVersionId()));
                row.put("nodeKey", node.getNodeKey());
                row.put("id", node.getId());
                row.put("nodeType", node.getNodeType() != null ? node.getNodeType() : "");
                row.put("nodeName", node.getNodeName() != null ? node.getNodeName() : "");
                row.put("displayName", node.getDisplayName() != null ? node.getDisplayName() : "");
                row.put("description", node.getDescription() != null ? node.getDescription() : "");
                row.put("sourceType", node.getSourceType() != null ? node.getSourceType() : "");
                row.put("sourcePath", node.getSourcePath() != null ? node.getSourcePath() : "");
                row.put("startLine", node.getStartLine() != null ? (long) node.getStartLine() : null);
                row.put("endLine", node.getEndLine() != null ? (long) node.getEndLine() : null);
                row.put("confidence", node.getConfidence() != null ? node.getConfidence().doubleValue() : 1.0);
                row.put("status", node.getStatus() != null ? node.getStatus() : "");
                row.put("properties", node.getProperties() != null ? node.getProperties() : "{}");
                row.put("scanType", node.getScanType() != null ? node.getScanType() : "");
                row.put("className", node.getClassName() != null ? node.getClassName() : "");
                row.put("verifiedScore", node.getVerifiedScore() != null ? node.getVerifiedScore().doubleValue() : 0.0);
                row.put("runtimeVerified", node.getRuntimeVerified() != null ? node.getRuntimeVerified() : false);
                row.put("lastSeenAt", node.getLastSeenAt() != null ? node.getLastSeenAt().toString() : null);
                row.put("traceCount", node.getTraceCount() != null ? (long) node.getTraceCount() : 0L);
                row.put("createdAt", node.getCreatedAt() != null ? node.getCreatedAt().toString() : LocalDateTime.now().toString());
                row.put("updatedAt", node.getUpdatedAt() != null ? node.getUpdatedAt().toString() : LocalDateTime.now().toString());
                rows.add(row);
            }

            String cypher = String.format(
                    "UNWIND $rows AS row " +
                    "MERGE (n:%s {projectId: row.projectId, versionId: row.versionId, nodeKey: row.nodeKey}) " +
                    "ON CREATE SET n = row " +
                    "ON MATCH SET n.nodeType = row.nodeType, " +
                    "n.nodeName = row.nodeName, " +
                    "n.displayName = row.displayName, " +
                    "n.description = row.description, " +
                    "n.sourceType = row.sourceType, " +
                    "n.sourcePath = row.sourcePath, " +
                    "n.startLine = row.startLine, " +
                    "n.endLine = row.endLine, " +
                    "n.confidence = row.confidence, " +
                    "n.status = row.status, " +
                    "n.properties = row.properties, " +
                    "n.scanType = row.scanType, " +
                    "n.className = row.className, " +
                    "n.verifiedScore = row.verifiedScore, " +
                    "n.runtimeVerified = row.runtimeVerified, " +
                    "n.lastSeenAt = row.lastSeenAt, " +
                    "n.traceCount = row.traceCount, " +
                    "n.updatedAt = row.updatedAt " +
                    "RETURN count(n) AS cnt", label);

            Result result = session.run(cypher, Map.of("rows", rows));
            if (result.hasNext()) {
                return (int) result.next().get("cnt").asLong();
            }
        }
        return 0;
    }

    private int mergeEdgesBatchForType(String edgeType, List<GraphEdge> edges) {
        int count = 0;
        for (int i = 0; i < edges.size(); i += BATCH_MERGE_SIZE) {
            int end = Math.min(i + BATCH_MERGE_SIZE, edges.size());
            List<GraphEdge> batch = edges.subList(i, end);
            count += mergeEdgesSubBatch(edgeType, batch);
        }
        return count;
    }

    private int mergeEdgesSubBatch(String edgeType, List<GraphEdge> batch) {
        try (Session session = neo4jDriver.session()) {
            List<Map<String, Object>> rows = new ArrayList<>(batch.size());
            for (GraphEdge edge : batch) {
                Map<String, Object> row = new HashMap<>();
                row.put("id", edge.getId());
                row.put("fromId", edge.getFromNodeId());
                row.put("toId", edge.getToNodeId());
                row.put("projectId", edge.getProjectId());
                row.put("versionId", normalizeId(edge.getVersionId()));
                row.put("edgeKey", edge.getEdgeKey() != null ? edge.getEdgeKey() : "");
                // P5 修复：补 edgeType 属性。原 row 漏放该字段，ON CREATE SET r = row 只设了动态关系标签，
                // r.edgeType 属性恒为 null，导致按 r.edgeType 过滤的查询（系统总览边类型统计）漏边。
                row.put("edgeType", edgeType);
                row.put("sourceType", edge.getSourceType() != null ? edge.getSourceType() : "");
                row.put("confidence", edge.getConfidence() != null ? edge.getConfidence().doubleValue() : 1.0);
                row.put("status", edge.getStatus() != null ? edge.getStatus() : "");
                row.put("properties", edge.getProperties() != null ? edge.getProperties() : "{}");
                row.put("evidenceIds", edge.getEvidenceIds() != null ? edge.getEvidenceIds() : "");
                row.put("relationStatus", edge.getRelationStatus() != null ? edge.getRelationStatus() : "");
                row.put("createdAt", edge.getCreatedAt() != null ? edge.getCreatedAt().toString() : LocalDateTime.now().toString());
                row.put("updatedAt", edge.getUpdatedAt() != null ? edge.getUpdatedAt().toString() : LocalDateTime.now().toString());
                rows.add(row);
            }

            String cypher = String.format(
                    "UNWIND $rows AS row " +
                    "MATCH (from {id: row.fromId}) " +
                    "MATCH (to {id: row.toId}) " +
                    "MERGE (from)-[r:%s {projectId: row.projectId, versionId: row.versionId, edgeKey: row.edgeKey}]->(to) " +
                    "ON CREATE SET r = row " +
                    "ON MATCH SET r.edgeType = row.edgeType, " +
                    "r.sourceType = row.sourceType, " +
                    "r.confidence = row.confidence, " +
                    "r.status = row.status, " +
                    "r.properties = row.properties, " +
                    "r.evidenceIds = row.evidenceIds, " +
                    "r.relationStatus = row.relationStatus, " +
                    "r.updatedAt = row.updatedAt " +
                    "RETURN count(r) AS cnt", edgeType);

            Result result = session.run(cypher, Map.of("rows", rows));
            if (result.hasNext()) {
                return (int) result.next().get("cnt").asLong();
            }
        }
        return 0;
    }
}
