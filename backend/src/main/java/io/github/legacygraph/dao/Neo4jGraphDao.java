package io.github.legacygraph.dao;

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

    public Neo4jGraphDao(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
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

    /** 查找节点（projectId + versionId + nodeType + nodeKey 唯一约束） */
    public Optional<GraphNode> findNode(String projectId, String versionId, String nodeType, String nodeKey) {
        try (Session session = neo4jDriver.session()) {
            String cypher = String.format(
                    "MATCH (n:%s {projectId: $projectId, versionId: $versionId, nodeKey: $nodeKey}) RETURN n LIMIT 1",
                    nodeType);
            Result result = session.run(cypher, Map.of(
                    "projectId", projectId,
                    "versionId", versionId,
                    "nodeKey", nodeKey));
            if (result.hasNext()) {
                return Optional.of(recordToNode(result.next().get("n").asNode()));
            }
        }
        return Optional.empty();
    }

    /** 按 ID 查找节点 */
    public Optional<GraphNode> findNodeById(String nodeId) {
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(
                    "MATCH (n) WHERE n.id = $id RETURN n LIMIT 1",
                    Map.of("id", nodeId));
            if (result.hasNext()) {
                return Optional.of(recordToNode(result.next().get("n").asNode()));
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
                params.put("versionId", versionId);
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
                params.put("versionId", versionId);
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
                params.put("versionId", versionId);
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
                    "confidence: $confidence, status: $status, evidenceIds: $evidenceIds, " +
                    "createdAt: $createdAt, updatedAt: $updatedAt}]->(to) RETURN r",
                    edge.getEdgeType());
            Map<String, Object> params = new HashMap<>();
            params.put("id", edge.getId());
            params.put("fromId", edge.getFromNodeId());
            params.put("toId", edge.getToNodeId());
            params.put("projectId", edge.getProjectId());
            params.put("versionId", edge.getVersionId());
            params.put("edgeKey", edge.getEdgeKey() != null ? edge.getEdgeKey() : "");
            params.put("edgeType", edge.getEdgeType());
            params.put("sourceType", edge.getSourceType() != null ? edge.getSourceType() : "");
            params.put("confidence", edge.getConfidence() != null ? edge.getConfidence().doubleValue() : 1.0);
            params.put("status", edge.getStatus() != null ? edge.getStatus() : "");
            params.put("evidenceIds", edge.getEvidenceIds() != null ? edge.getEvidenceIds() : "");
            params.put("createdAt", edge.getCreatedAt() != null ? edge.getCreatedAt().toString() : LocalDateTime.now().toString());
            params.put("updatedAt", edge.getUpdatedAt() != null ? edge.getUpdatedAt().toString() : LocalDateTime.now().toString());
            session.run(cypher, params);
            return edge;
        }
    }

    /** 条件查询边列表 */
    public List<GraphEdge> queryEdges(String projectId, String versionId,
                                       Double minConfidence, String status,
                                       int limit) {
        try (Session session = neo4jDriver.session()) {
            StringBuilder cypher = new StringBuilder(
                    "MATCH ()-[r]->() WHERE r.projectId = $projectId");
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            if (versionId != null) {
                cypher.append(" AND r.versionId = $versionId");
                params.put("versionId", versionId);
            }
            if (minConfidence != null) {
                cypher.append(" AND r.confidence >= $minConfidence");
                params.put("minConfidence", minConfidence);
            }
            if (status != null) {
                cypher.append(" AND r.status = $status");
                params.put("status", status);
            }
            cypher.append(" RETURN r");
            if (limit > 0) {
                cypher.append(" LIMIT ").append(limit);
            }
            Result result = session.run(cypher.toString(), params);
            List<GraphEdge> edges = new ArrayList<>();
            while (result.hasNext()) {
                edges.add(recordToEdge(result.next().get("r").asRelationship()));
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
                params.put("versionId", versionId);
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
                "     count(CASE WHEN n.evidenceIds IS NOT NULL AND n.evidenceIds <> '' THEN 1 END) AS withEvidenceCount " +
                "OPTIONAL MATCH ()-[r]->() WHERE r.projectId = $projectId " +
                "RETURN totalNodes, confirmedNodes, pendingNodes, avgConfidence, withEvidenceCount, " +
                "       count(r) AS totalEdges, " +
                "       count(CASE WHEN r.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmedEdges, " +
                "       count(CASE WHEN r.status IN ['PENDING', 'PENDING_CONFIRM'] THEN 1 END) AS pendingEdges";
            Result result = session.run(cypher, Map.of("projectId", projectId));
            if (result.hasNext()) {
                return result.next().asMap();
            }
        }
        return Map.of(
            "totalNodes", 0L, "confirmedNodes", 0L, "pendingNodes", 0L,
            "avgConfidence", 0.0, "withEvidenceCount", 0L,
            "totalEdges", 0L, "confirmedEdges", 0L, "pendingEdges", 0L
        );
    }

    /** 更新边 */
    public void updateEdge(GraphEdge edge) {
        try (Session session = neo4jDriver.session()) {
            String cypher = "MATCH ()-[r {id: $id}]->() SET " +
                    "r.status = $status, r.confidence = $confidence, r.updatedAt = $updatedAt";
            session.run(cypher, Map.of(
                    "id", edge.getId(),
                    "status", edge.getStatus() != null ? edge.getStatus() : "",
                    "confidence", edge.getConfidence() != null ? edge.getConfidence().doubleValue() : 1.0,
                    "updatedAt", LocalDateTime.now().toString()
            ));
        }
    }

    /** 按 ID 查找边 */
    public Optional<GraphEdge> findEdgeById(String edgeId) {
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(
                    "MATCH ()-[r {id: $id}]->() RETURN r LIMIT 1",
                    Map.of("id", edgeId));
            if (result.hasNext()) {
                return Optional.of(recordToEdge(result.next().get("r").asRelationship()));
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
                params.put("versionId", versionId);
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

    // ==================== 批量操作 ====================

    /** 删除指定版本的整个子图（DETACH DELETE） */
    public void deleteGraph(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            session.run(
                    "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId DETACH DELETE n",
                    Map.of("projectId", projectId, "versionId", versionId));
            log.info("Deleted Neo4j graph: projectId={}, versionId={}", projectId, versionId);
        }
    }

    /** 删除单个节点 */
    public void deleteNode(String projectId, String versionId, String nodeId) {
        try (Session session = neo4jDriver.session()) {
            session.run(
                    "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId AND n.id = $nodeId DETACH DELETE n",
                    Map.of("projectId", projectId, "versionId", versionId, "nodeId", nodeId));
        }
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

    /** 创建约束（服务启动时初始化） */
    public void createConstraints() {
        try (Session session = neo4jDriver.session()) {
            String[] nodeTypes = {
                    "Project", "ApiEndpoint", "Table", "Method", "Controller", "Service", "Mapper",
                    "BusinessDomain", "BusinessProcess", "BusinessObject", "BusinessRule",
                    "Feature", "Page", "Role", "Repository"
            };
            for (String type : nodeTypes) {
                String cypher = String.format(
                        "CREATE CONSTRAINT %s_id_key IF NOT EXISTS FOR (n:%s) REQUIRE n.id IS UNIQUE",
                        type.toLowerCase(), type);
                session.run(cypher);
            }
            log.info("Created Neo4j constraints for {} node types", nodeTypes.length);
        }
    }

    /** 创建索引 */
    public void createIndexes() {
        try (Session session = neo4jDriver.session()) {
            session.run("CREATE INDEX project_version_idx IF NOT EXISTS FOR (n) ON (n.projectId, n.versionId)");
            session.run("CREATE INDEX node_id_idx IF NOT EXISTS FOR (n) ON (n.id)");
            session.run("CREATE INDEX node_key_lookup IF NOT EXISTS FOR (n) ON (n.nodeKey)");
            // 关系属性索引：加速按 projectId 统计边数量
            session.run("CREATE INDEX edge_project_id_idx IF NOT EXISTS FOR ()-[r]-() ON (r.projectId)");
            log.info("Created Neo4j indexes");
        }
    }

    // ==================== 内部转换 ====================

    private Map<String, Object> nodeToParams(GraphNode node) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", node.getId());
        params.put("projectId", node.getProjectId());
        params.put("versionId", node.getVersionId());
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

    private GraphEdge recordToEdge(org.neo4j.driver.types.Relationship rel) {
        GraphEdge edge = new GraphEdge();
        Map<String, Object> props = rel.asMap();
        edge.setId((String) props.get("id"));
        edge.setProjectId((String) props.get("projectId"));
        edge.setVersionId((String) props.get("versionId"));
        edge.setFromNodeId(rel.startNodeElementId());
        edge.setToNodeId(rel.endNodeElementId());
        edge.setEdgeType(rel.type());
        edge.setEdgeKey((String) props.get("edgeKey"));
        edge.setSourceType((String) props.get("sourceType"));
        Object conf = props.get("confidence");
        if (conf instanceof Double) edge.setConfidence(BigDecimal.valueOf((Double) conf));
        else if (conf instanceof String) edge.setConfidence(new BigDecimal((String) conf));
        edge.setStatus((String) props.get("status"));
        edge.setEvidenceIds((String) props.get("evidenceIds"));
        Object ca = props.get("createdAt");
        if (ca instanceof String) edge.setCreatedAt(LocalDateTime.parse((String) ca));
        Object ua = props.get("updatedAt");
        if (ua instanceof String) edge.setUpdatedAt(LocalDateTime.parse((String) ua));
        return edge;
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
        edge.setEvidenceIds((String) props.get("evidenceIds"));
        Object ca = props.get("createdAt");
        if (ca instanceof String) edge.setCreatedAt(LocalDateTime.parse((String) ca));
        Object ua = props.get("updatedAt");
        if (ua instanceof String) edge.setUpdatedAt(LocalDateTime.parse((String) ua));
        return edge;
    }
}
