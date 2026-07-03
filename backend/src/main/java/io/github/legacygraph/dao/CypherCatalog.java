package io.github.legacygraph.dao;

import org.springframework.stereotype.Component;

/**
 * Cypher 查询模板目录 — 集中管理所有常用 Cypher 语句常量。
 * <p>
 * 按功能分组：NODE_QUERY / NODE_WRITE / EDGE_QUERY / EDGE_WRITE / PROJECTION / ADMIN / SCHEMA。
 * 所有常量使用 $param 占位符，运行时通过 {@code Map.of(...)} 绑定参数。
 * </p>
 */
@Component
public class CypherCatalog {

    private static final String IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";

    /**
     * Neo4j label / relationship type / property key 不能参数化，只能在拼接前做白名单校验。
     */
    public static String safeIdentifier(String value, String fieldName) {
        if (value == null || !value.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException("Invalid Cypher identifier for " + fieldName);
        }
        return value;
    }

    // ==================== NODE_QUERY — 节点查询 ====================

    /** 按 (projectId, versionId, nodeType, nodeKey) 复合键查找节点 */
    public static final String FIND_NODE_BY_KEY =
            "MATCH (n:%s {projectId: $projectId, versionId: $versionId, nodeKey: $nodeKey}) " +
            "RETURN n LIMIT 1";

    /** 按应用 UUID 查找单个节点（可走 id UNIQUE 约束索引） */
    public static final String FIND_NODE_BY_ID =
            "MATCH (n) WHERE n.id = $id RETURN n LIMIT 1";

    /** 按 ID 列表批量查找节点 */
    public static final String FIND_NODES_BY_IDS =
            "MATCH (n) WHERE n.id IN $ids RETURN n";

    /** 条件过滤查询节点列表（支持 versionId/nodeType/sourceType/minConfidence/status） */
    public static final String QUERY_NODES_FILTERED =
            "MATCH (n) WHERE n.projectId = $projectId " +
            "AND n.versionId = $versionId " +
            "RETURN n";

    /** 带 nodeKey 过滤的增强条件查询 */
    public static final String QUERY_NODES_WITH_KEY =
            "MATCH (n) WHERE n.projectId = $projectId " +
            "AND n.nodeKey = $nodeKey RETURN n";

    /** 按版本统计节点数量 */
    public static final String COUNT_NODES =
            "MATCH (n) WHERE n.projectId = $projectId " +
            "AND n.versionId = $versionId " +
            "RETURN count(n) AS cnt";

    /** 查询低置信度节点（threshold 以下），按置信度升序 */
    public static final String QUERY_LOW_CONFIDENCE_NODES =
            "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
            "AND coalesce(n.confidence, 0.0) < $threshold " +
            "RETURN n ORDER BY n.confidence ASC LIMIT $limit";

    /** 查询无连接关系的孤立节点 */
    public static final String QUERY_DISCONNECTED_NODES =
            "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
            "OPTIONAL MATCH (n)-[r]-() WHERE r.projectId = $projectId " +
            "WITH n, count(r) AS edgeCount WHERE edgeCount = 0 " +
            "RETURN n LIMIT $limit";

    /** 查询某节点在指定项目内的全部邻居节点 id */
    public static final String FIND_NEIGHBOR_NODE_IDS =
            "MATCH (n {id: $nodeId, projectId: $projectId})-[r]-(m) " +
            "WHERE r.projectId = $projectId AND m.id IS NOT NULL " +
            "RETURN DISTINCT m.id AS neighborId";

    // ==================== NODE_WRITE — 节点写入 ====================

    /** 创建节点（使用 %s 动态标签） */
    public static final String CREATE_NODE =
            "CREATE (n:%s {id: $id, projectId: $projectId, versionId: $versionId, " +
            "nodeType: $nodeType, nodeKey: $nodeKey, nodeName: $nodeName, displayName: $displayName, " +
            "description: $description, sourceType: $sourceType, sourcePath: $sourcePath, " +
            "startLine: $startLine, endLine: $endLine, confidence: $confidence, " +
            "status: $status, properties: $properties, scanType: $scanType, className: $className, " +
            "verifiedScore: $verifiedScore, runtimeVerified: $runtimeVerified, " +
            "lastSeenAt: $lastSeenAt, traceCount: $traceCount, " +
            "createdAt: $createdAt, updatedAt: $updatedAt}) RETURN n";

    /** MERGE 单节点（原子去重，ON CREATE 全量赋值，ON MATCH 仅更新时间戳） */
    public static final String MERGE_NODE =
            "MERGE (n:%s {projectId: $projectId, versionId: $versionId, nodeKey: $nodeKey}) " +
            "ON CREATE SET n.id = $id, n.nodeType = $nodeType, n.nodeName = $nodeName, " +
            "n.displayName = $displayName, n.description = $description, " +
            "n.sourceType = $sourceType, n.sourcePath = $sourcePath, " +
            "n.startLine = $startLine, n.endLine = $endLine, n.confidence = $confidence, " +
            "n.status = $status, n.properties = $properties, n.scanType = $scanType, " +
            "n.className = $className, n.verifiedScore = $verifiedScore, " +
            "n.runtimeVerified = $runtimeVerified, n.lastSeenAt = $lastSeenAt, " +
            "n.traceCount = $traceCount, n.createdAt = $createdAt, n.updatedAt = $updatedAt " +
            "ON MATCH SET n.updatedAt = $updatedAt, n.nodeType = $nodeType, " +
            "n.scanType = $scanType, n.className = $className " +
            "RETURN n, (n.createdAt = $createdAt) AS created";

    /** 批量 UNWIND MERGE 节点（按 nodeType 分组后单次 UNWIND） */
    public static final String UNWIND_MERGE_NODES =
            "UNWIND $rows AS row " +
            "MERGE (n:%s {projectId: row.projectId, versionId: row.versionId, nodeKey: row.nodeKey}) " +
            "ON CREATE SET n = row " +
            "ON MATCH SET n.updatedAt = row.updatedAt " +
            "RETURN count(n) AS cnt";

    /** 批量 UNWIND MERGE 节点（简写版，用于 mergeNodesBatch 方法） */
    public static final String UNWIND_MERGE_NODES_SIMPLE =
            "UNWIND $nodes AS n " +
            "MERGE (node:GraphNode {projectId: $projectId, versionId: $versionId, " +
            "nodeType: n.nodeType, nodeKey: n.nodeKey}) " +
            "SET node.nodeName = n.nodeName, node += n.properties, node.updatedAt = datetime()";

    /** 设置节点单个属性 */
    public static final String SET_NODE_PROPERTY =
            "MATCH (n {id: $id}) SET n.%s = $value";

    /** 更新节点属性（置信度、验证分数、状态等） */
    public static final String UPDATE_NODE =
            "MATCH (n {id: $id}) SET " +
            "n.verifiedScore = $verifiedScore, " +
            "n.runtimeVerified = $runtimeVerified, " +
            "n.lastSeenAt = $lastSeenAt, " +
            "n.traceCount = $traceCount, " +
            "n.status = $status, " +
            "n.confidence = $confidence, " +
            "n.updatedAt = $updatedAt";

    // ==================== EDGE_QUERY — 边查询 ====================

    /** 查找已存在的边（按两端节点 id + edgeType + edgeKey 唯一标识） */
    public static final String FIND_EDGE =
            "MATCH (from {id: $fromId})-[r]->(to {id: $toId}) " +
            "WHERE type(r) = $edgeType AND r.edgeKey = $edgeKey " +
            "AND r.projectId = $projectId " +
            "AND (r.versionId = $versionId OR $versionId IS NULL) " +
            "RETURN r, from, to LIMIT 1";

    /** 按 ID 查找边（同时返回两端节点） */
    public static final String FIND_EDGE_BY_ID =
            "MATCH (from)-[r {id: $id}]->(to) RETURN r, from, to LIMIT 1";

    /** 条件过滤查询边列表 */
    public static final String QUERY_EDGES_FILTERED =
            "MATCH (from)-[r]->(to) WHERE r.projectId = $projectId " +
            "AND r.versionId = $versionId " +
            "RETURN r, from, to";

    /** 增强条件查询边（支持 edgeType/toNodeId/connectedNodeId） */
    public static final String QUERY_EDGES_ENHANCED =
            "MATCH (from)-[r]->(to) WHERE r.projectId = $projectId " +
            "AND r.versionId = $versionId " +
            "AND type(r) = $edgeType " +
            "RETURN r, from, to";

    /** 统计边数量 */
    public static final String COUNT_EDGES =
            "MATCH ()-[r]->() WHERE r.projectId = $projectId " +
            "AND r.versionId = $versionId " +
            "RETURN count(r) AS cnt";

    /** 统计连接到指定节点集合的边数（用于覆盖率计算） */
    public static final String COUNT_EDGES_CONNECTED =
            "MATCH (from)-[r]->(to) WHERE r.projectId = $projectId AND r.versionId = $versionId " +
            "AND (from.id IN $nodeIds OR to.id IN $nodeIds) " +
            "RETURN count(DISTINCT r) AS cnt";

    // ==================== EDGE_WRITE — 边写入 ====================

    /** 创建边（使用 %s 动态关系类型） */
    public static final String CREATE_EDGE =
            "MATCH (from {id: $fromId}) " +
            "MATCH (to {id: $toId}) " +
            "CREATE (from)-[r:%s {id: $id, projectId: $projectId, versionId: $versionId, " +
            "edgeKey: $edgeKey, edgeType: $edgeType, sourceType: $sourceType, " +
            "confidence: $confidence, status: $status, properties: $properties, " +
            "evidenceIds: $evidenceIds, relationStatus: $relationStatus, " +
            "createdAt: $createdAt, updatedAt: $updatedAt}]->(to) RETURN r";

    /** MERGE 单边（原子去重） */
    public static final String MERGE_EDGE =
            "MATCH (from {id: $fromId}), (to {id: $toId}) " +
            "MERGE (from)-[r:%s {projectId: $projectId, versionId: $versionId, edgeKey: $edgeKey}]->(to) " +
            "ON CREATE SET r.id = $id, r.edgeType = $edgeType, r.sourceType = $sourceType, " +
            "r.confidence = $confidence, r.status = $status, r.properties = $properties, " +
            "r.evidenceIds = $evidenceIds, r.relationStatus = $relationStatus, " +
            "r.createdAt = $createdAt, r.updatedAt = $updatedAt " +
            "ON MATCH SET r.updatedAt = $updatedAt " +
            "RETURN r, from, to, (r.createdAt = $createdAt) AS created";

    /** 批量 UNWIND MERGE 边 */
    public static final String UNWIND_MERGE_EDGES =
            "UNWIND $rows AS row " +
            "MATCH (from {id: row.fromId}) " +
            "MATCH (to {id: row.toId}) " +
            "MERGE (from)-[r:%s {projectId: row.projectId, versionId: row.versionId, " +
            "edgeKey: row.edgeKey}]->(to) " +
            "ON CREATE SET r = row " +
            "ON MATCH SET r.updatedAt = row.updatedAt " +
            "RETURN count(r) AS cnt";

    /** 批量 UNWIND MERGE 边（简写版） */
    public static final String UNWIND_MERGE_EDGES_SIMPLE =
            "UNWIND $edges AS e " +
            "MATCH (from {id: e.fromNodeId}), (to {id: e.toNodeId}) " +
            "MERGE (from)-[r:RELATES {projectId: $projectId, versionId: $versionId, " +
            "edgeType: e.edgeType, edgeKey: e.edgeKey}]->(to) " +
            "SET r += e.properties, r.updatedAt = datetime()";

    /** 更新边属性 */
    public static final String UPDATE_EDGE =
            "MATCH ()-[r {id: $id}]->() SET " +
            "r.status = $status, " +
            "r.confidence = $confidence, " +
            "r.relationStatus = $relationStatus, " +
            "r.properties = $properties, " +
            "r.updatedAt = $updatedAt";

    /** 设置边单个属性 */
    public static final String SET_EDGE_PROPERTY =
            "MATCH ()-[r {id: $id}]->() SET r.%s = $value";

    // ==================== PROJECTION — 投影查询（轻量返回） ====================

    /** 节点投影查询（仅返回前端需要的字段，减少网络传输） */
    public static final String QUERY_NODES_PROJECTION =
            "MATCH (n) WHERE n.projectId = $projectId " +
            "RETURN n.id AS id, n.nodeKey AS key, n.nodeName AS name, " +
            "n.displayName AS label, labels(n)[0] AS type, " +
            "n.confidence AS confidence, n.status AS status, " +
            "n.description AS description, n.sourcePath AS sourcePath, " +
            "n.sourceType AS sourceType, n.verifiedScore AS verifiedScore, " +
            "n.runtimeVerified AS runtimeVerified, n.lastSeenAt AS lastSeenAt, " +
            "n.traceCount AS traceCount " +
            "ORDER BY n.confidence DESC";

    /** 边投影查询（仅返回 id/source/target/type/confidence/status） */
    public static final String QUERY_EDGES_PROJECTION =
            "MATCH (from)-[r]->(to) WHERE r.projectId = $projectId " +
            "RETURN r.id AS id, from.id AS source, to.id AS target, " +
            "type(r) AS type, r.confidence AS confidence, r.status AS status";

    /** 项目级图统计（单次 Cypher 聚合避免多次往返） */
    public static final String GRAPH_STATS =
            "MATCH (n) WHERE n.projectId = $projectId " +
            "WITH count(n) AS totalNodes, " +
            "     count(CASE WHEN n.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmedNodes, " +
            "     count(CASE WHEN n.status IN ['PENDING', 'PENDING_CONFIRM'] THEN 1 END) AS pendingNodes, " +
            "     coalesce(avg(n.confidence), 0.0) AS avgConfidence " +
            "OPTIONAL MATCH ()-[r]->() WHERE r.projectId = $projectId " +
            "RETURN totalNodes, confirmedNodes, pendingNodes, avgConfidence, " +
            "       count(r) AS totalEdges";

    /** 版本级图统计 */
    public static final String VERSION_GRAPH_STATS =
            "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
            "WITH count(n) AS totalNodes, " +
            "     count(CASE WHEN n.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmedNodes " +
            "OPTIONAL MATCH ()-[r]->() WHERE r.projectId = $projectId AND r.versionId = $versionId " +
            "RETURN totalNodes, confirmedNodes, count(r) AS totalEdges";

    /** 节点类型分布统计 */
    public static final String NODE_TYPE_STATS =
            "MATCH (n) WHERE n.projectId = $projectId " +
            "UNWIND labels(n) AS label " +
            "WITH label AS nodeType, count(*) AS total, " +
            "     count(CASE WHEN n.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmed, " +
            "     coalesce(avg(n.confidence), 0.0) AS avgConfidence " +
            "RETURN nodeType, total, confirmed, avgConfidence " +
            "ORDER BY total DESC";

    /** 平均节点度数（版本维度） */
    public static final String AVERAGE_NODE_DEGREE =
            "MATCH (n)-[r]-() WHERE n.projectId = $projectId AND n.versionId = $versionId " +
            "AND r.projectId = $projectId AND r.versionId = $versionId " +
            "WITH n, count(r) AS degree " +
            "RETURN coalesce(avg(degree), 0.0) AS avgDegree";

    // ==================== ADMIN — 管理/维护操作 ====================

    /** 删除指定版本的整个子图 */
    public static final String DELETE_VERSION_NODES =
            "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
            "DETACH DELETE n";

    /** 删除指定项目的全部图谱数据 */
    public static final String DELETE_PROJECT_GRAPH =
            "MATCH (n) WHERE n.projectId = $projectId DETACH DELETE n";

    /** 删除单个节点及其所有关联边 */
    public static final String DELETE_NODE =
            "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
            "AND n.id = $nodeId DETACH DELETE n";

    /** 将从旧节点出发的边重新连接到新节点（图谱合并/去重时使用） */
    public static final String REWIRE_EDGE_FROM =
            "MATCH (old {id: $oldNodeId})-[r]->(to) WHERE r.projectId = $projectId " +
            "RETURN r, to, type(r) AS relType, properties(r) AS props";

    /** 将指向旧节点的边重新指向新节点 */
    public static final String REWIRE_EDGE_TO =
            "MATCH (from)-[r]->(old {id: $oldNodeId}) WHERE r.projectId = $projectId " +
            "RETURN r, from, type(r) AS relType, properties(r) AS props";

    /** 删除旧边（rewire 辅助） */
    public static final String DELETE_OLD_EDGE_FROM =
            "MATCH (old {id: $oldNodeId})-[r]->(to {id: $toId}) " +
            "WHERE r.projectId = $projectId DELETE r";

    /** 删除旧边（rewire 辅助，入边方向） */
    public static final String DELETE_OLD_EDGE_TO =
            "MATCH (from {id: $fromId})-[r]->(old {id: $oldNodeId}) " +
            "WHERE r.projectId = $projectId DELETE r";

    // ==================== SCHEMA — 约束与索引 ====================

    /** 为指定标签创建 id 唯一性约束 */
    public static final String CREATE_CONSTRAINT =
            "CREATE CONSTRAINT %s_id_key IF NOT EXISTS FOR (n:%s) REQUIRE n.id IS UNIQUE";

    /** 为指定标签创建 (projectId, versionId) 复合索引 */
    public static final String CREATE_INDEX_PROJECT_VERSION =
            "CREATE INDEX IF NOT EXISTS FOR (n:%s) ON (n.projectId, n.versionId)";

    /** 为指定标签创建 nodeKey 索引（MERGE/去重的关键索引） */
    public static final String CREATE_INDEX_NODE_KEY =
            "CREATE INDEX IF NOT EXISTS FOR (n:%s) ON (n.nodeKey)";

    /** 为指定关系类型创建 (projectId, versionId) 属性索引 */
    public static final String CREATE_INDEX_RELATIONSHIP =
            "CREATE INDEX IF NOT EXISTS FOR ()-[r:%s]-() ON (r.projectId, r.versionId)";
}
