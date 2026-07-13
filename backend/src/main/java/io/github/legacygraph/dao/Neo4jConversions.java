package io.github.legacygraph.dao;

import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.service.system.CacheService;
import io.github.legacygraph.util.IdUtil;
import org.neo4j.driver.types.Relationship;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Neo4j 数据转换工具类 — 共享的静态 helper，供各 Repository 使用。
 */
final class Neo4jConversions {

    private Neo4jConversions() {}

    /**
     * 归一化 UUID 格式：去除横线。
     * 委托给 {@link IdUtil#normalizeId(String)} 统一管理。
     */
    static String normalizeId(String id) {
        return IdUtil.normalizeId(id);
    }

    static String nodeCacheKey(String nodeId) {
        return "graph:node:" + nodeId;
    }

    static void evictNodeCache(CacheService cacheService, String nodeId) {
        if (cacheService != null && nodeId != null) {
            cacheService.evict(nodeCacheKey(nodeId));
        }
    }

    static Map<String, Object> nodeToParams(GraphNode node) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", node.getId());
        params.put("projectId", node.getProjectId());
        params.put("versionId", normalizeId(node.getVersionId()));
        params.put("nodeType", node.getNodeType() != null ? node.getNodeType() : "");
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
        params.put("scanType", node.getScanType() != null ? node.getScanType() : "");
        params.put("className", node.getClassName() != null ? node.getClassName() : "");
        params.put("aliasNames", node.getAliasNames());
        params.put("verifiedScore", node.getVerifiedScore() != null ? node.getVerifiedScore().doubleValue() : 0.0);
        params.put("runtimeVerified", node.getRuntimeVerified() != null ? node.getRuntimeVerified() : false);
        params.put("lastSeenAt", node.getLastSeenAt() != null ? node.getLastSeenAt().toString() : null);
        params.put("traceCount", node.getTraceCount() != null ? (long) node.getTraceCount() : 0L);
        params.put("createdAt", node.getCreatedAt() != null ? node.getCreatedAt().toString() : LocalDateTime.now().toString());
        params.put("updatedAt", node.getUpdatedAt() != null ? node.getUpdatedAt().toString() : LocalDateTime.now().toString());
        return params;
    }

    static GraphNode recordToNode(org.neo4j.driver.types.Node neoNode) {
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
        node.setScanType((String) props.get("scanType"));
        node.setClassName((String) props.get("className"));
        Object sl = props.get("startLine");
        if (sl instanceof Long) node.setStartLine(((Long) sl).intValue());
        Object el = props.get("endLine");
        if (el instanceof Long) node.setEndLine(((Long) el).intValue());
        Object conf = props.get("confidence");
        if (conf instanceof Double) node.setConfidence(BigDecimal.valueOf((Double) conf));
        else if (conf instanceof String) node.setConfidence(new BigDecimal((String) conf));
        node.setStatus((String) props.get("status"));
        node.setProperties((String) props.get("properties"));
        node.setAliasNames((String) props.get("aliasNames"));
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
    static GraphEdge recordToEdge(Relationship rel,
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
