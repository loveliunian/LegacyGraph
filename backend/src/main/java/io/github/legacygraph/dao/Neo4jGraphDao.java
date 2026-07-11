package io.github.legacygraph.dao;

import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Neo4j 图谱数据访问 Facade — 向后兼容层。
 * <p>保留原有全部 public 方法签名，内部委托给 5 个职责单一的 Repository：</p>
 * <ul>
 *   <li>{@link Neo4jQueryRepository} — 查询方法</li>
 *   <li>{@link Neo4jWriteRepository} — 写入方法</li>
 *   <li>{@link Neo4jProjectionRepository} — projection/stats 方法</li>
 *   <li>{@link Neo4jAdminRepository} — 删除/清理方法</li>
 *   <li>{@link Neo4jSchemaRepository} — 索引/constraint</li>
 * </ul>
 */
@Slf4j
@Component
public class Neo4jGraphDao {

    private final Neo4jQueryRepository queryRepo;
    private final Neo4jWriteRepository writeRepo;
    private final Neo4jProjectionRepository projectionRepo;
    private final Neo4jAdminRepository adminRepo;
    private final Neo4jSchemaRepository schemaRepo;

    public Neo4jGraphDao(Neo4jQueryRepository queryRepo,
                         Neo4jWriteRepository writeRepo,
                         Neo4jProjectionRepository projectionRepo,
                         Neo4jAdminRepository adminRepo,
                         Neo4jSchemaRepository schemaRepo) {
        this.queryRepo = queryRepo;
        this.writeRepo = writeRepo;
        this.projectionRepo = projectionRepo;
        this.adminRepo = adminRepo;
        this.schemaRepo = schemaRepo;
    }

    // ==================== 静态工具方法 ====================

    /** 将 null 或空字符串统一为 null，便于 Cypher 参数处理 */
    public static String normalizeId(String id) {
        return (id == null || id.isEmpty()) ? null : id;
    }

    // ==================== Record 类型（向后兼容） ====================

    /** mergeNode 的返回结果 */
    public record NodeUpsert(GraphNode node, boolean created) {}

    /** mergeEdge 的返回结果 */
    public record EdgeUpsert(GraphEdge edge, boolean created) {}

    /** 批量写入节点 */
    public record BatchNodeUpsert(String nodeType, String nodeKey, String nodeName,
                                   Map<String, Object> properties) {}

    /** 批量写入边 */
    public record BatchEdgeUpsert(String fromNodeId, String toNodeId, String edgeType,
                                   String edgeKey, Map<String, Object> properties) {}

    /** Graphify 子图删除结果。 */
    public record GraphifyDeleteResult(long nodeCount, long edgeCount) {}

    /**
     * 有界路径查询返回的单条路径。
     * <p>
     * 携带完整 nodes/edges（满足"返回节点、边"），同时附带轻量结构字段
     * （relationTypes / sourcePath / startLine / endLine / confidence / fromKey / toKey），
     * 供 executor 折叠为 EvidenceCard 而无需再次遍历全量对象。
     * </p>
     */
    public record GraphPath(List<GraphNode> nodes, List<GraphEdge> edges,
                             List<String> relationTypes, String sourcePath,
                             Integer startLine, Integer endLine,
                             BigDecimal confidence, String fromKey, String toKey) {}

    // ==================== 查询方法 → QueryRepository ====================

    public Optional<GraphNode> findNode(String projectId, String versionId,
                                         String nodeType, String nodeKey) {
        return queryRepo.findNode(projectId, versionId, nodeType, nodeKey);
    }

    public Optional<GraphNode> findNodeById(String nodeId) {
        return queryRepo.findNodeById(nodeId);
    }

    public List<GraphNode> findNodesByIds(List<String> nodeIds) {
        return queryRepo.findNodesByIds(nodeIds);
    }

    public List<GraphNode> queryNodes(String projectId, String versionId,
                                       String nodeType, String sourceType,
                                       Double minConfidence, String status,
                                       int limit) {
        return queryRepo.queryNodes(projectId, versionId, nodeType, sourceType,
                minConfidence, status, limit);
    }

    public List<GraphNode> queryNodes(String projectId, String versionId,
                                       String nodeType, String nodeKey,
                                       String sourceType, Double minConfidence,
                                       String status, int limit) {
        return queryRepo.queryNodes(projectId, versionId, nodeType, nodeKey,
                sourceType, minConfidence, status, limit);
    }

    public long countNodes(String projectId, String versionId, String status) {
        return queryRepo.countNodes(projectId, versionId, status);
    }

    public Optional<GraphEdge> findEdge(String projectId, String versionId,
                                         String fromNodeId, String toNodeId,
                                         String edgeType, String edgeKey) {
        return queryRepo.findEdge(projectId, versionId, fromNodeId, toNodeId, edgeType, edgeKey);
    }

    public List<GraphEdge> queryEdges(String projectId, String versionId,
                                       String edgeType, String toNodeId, int limit) {
        return queryRepo.queryEdges(projectId, versionId, edgeType, toNodeId, null,
                null, null, limit);
    }

    public List<GraphEdge> queryEdges(String projectId, String versionId,
                                       String edgeType, String toNodeId, String connectedNodeId,
                                       Double minConfidence, String status,
                                       int limit) {
        return queryRepo.queryEdges(projectId, versionId, edgeType, toNodeId, connectedNodeId,
                minConfidence, status, limit);
    }

    public Set<String> findNeighborNodeIds(String projectId, String nodeId) {
        return queryRepo.findNeighborNodeIds(projectId, nodeId);
    }

    /**
     * 批量查询多个源节点的邻居（一次 Cypher 替代 N 次单节点查询）。
     * @return sourceNodeId → neighborIds 映射
     */
    public Map<String, Set<String>> findNeighborNodeIdsBySources(
            String projectId, Collection<String> sourceNodeIds, int perNodeLimit) {
        return queryRepo.findNeighborNodeIdsBySources(projectId, sourceNodeIds, perNodeLimit);
    }

    /**
     * 有界无向路径查询 — 在 from/to 节点之间查找最多 maxDepth 跳的路径。
     *
     * @param relationshipTypes 关系类型白名单（空列表 → 全类型）
     * @param maxDepth          最大跳数（repository 内钳制到 [1,4]）
     * @param limit             返回路径数上限
     */
    public List<GraphPath> findPaths(String projectId, String versionId,
                                      String fromKey, String toKey,
                                      List<String> relationshipTypes,
                                      int maxDepth, int limit) {
        return queryRepo.findPaths(projectId, versionId, fromKey, toKey,
                relationshipTypes, maxDepth, limit);
    }

    /**
     * 有向有界路径查询 — 从单一起点沿指定方向遍历依赖链。
     * <p>
     * 与 {@link #findPaths} 的区别：① 单起点（非 from-to 双端）；
     * ② 有向（INBOUND 反向往上游，OUTBOUND 正向往下游）。
     * 用于变更影响多跳反查（如 Table←SQL←Mapper←Service←Api←Feature）。
     * </p>
     *
     * @param startNodeKey 起点节点 key（非 nodeId）
     * @param flow         INBOUND 反向 / OUTBOUND 正向
     */
    public List<GraphPath> findPathsDirected(String projectId, String versionId,
                                             String startNodeKey,
                                             List<String> relationshipTypes,
                                             io.github.legacygraph.common.FlowDirection flow,
                                             int maxDepth, int limit) {
        return queryRepo.findPathsDirected(projectId, versionId, startNodeKey,
                relationshipTypes, flow, maxDepth, limit);
    }

    public Optional<GraphEdge> findEdgeById(String edgeId) {
        return queryRepo.findEdgeById(edgeId);
    }

    public long countEdges(String projectId, String versionId, String status) {
        return queryRepo.countEdges(projectId, versionId, status);
    }

    public Set<String> queryNodeKeysBySourceTypes(String projectId, String versionId, List<String> sourceTypes) {
        return queryRepo.queryNodeKeysBySourceTypes(projectId, versionId, sourceTypes);
    }

    public Set<String> queryEdgeKeysBySourceTypes(String projectId, String versionId, List<String> sourceTypes) {
        return queryRepo.queryEdgeKeysBySourceTypes(projectId, versionId, sourceTypes);
    }

    public List<GraphNode> queryLowConfidenceNodes(String projectId, int limit) {
        return queryRepo.queryLowConfidenceNodes(projectId, limit);
    }

    public List<GraphNode> queryDisconnectedNodes(String projectId, int limit) {
        return queryRepo.queryDisconnectedNodes(projectId, limit);
    }

    public List<GraphNode> queryLowConfidenceNodes(String projectId, String versionId,
                                                    double threshold, int limit) {
        return queryRepo.queryLowConfidenceNodes(projectId, versionId, threshold, limit);
    }

    public List<GraphNode> queryDisconnectedNodes(String projectId, String versionId, int limit) {
        return queryRepo.queryDisconnectedNodes(projectId, versionId, limit);
    }

    /**
     * 查询指定版本中 affected=true 的指定类型节点（用于 Blast Radius 影响分析结果查询）。
     * <p>affected 标记由 BlastRadiusAnalyzer.markAffectedNodes 写入 Neo4j 节点顶层属性。
     * 详见 {@link Neo4jQueryRepository#queryAffectedNodes}。</p>
     *
     * @param projectId 项目 ID
     * @param versionId 版本 ID
     * @param nodeType  节点类型
     * @return 受影响节点列表
     */
    public List<GraphNode> queryAffectedNodes(String projectId, String versionId, String nodeType) {
        return queryRepo.queryAffectedNodes(projectId, versionId, nodeType);
    }

    public long countEdgesConnectedToNodes(String projectId, String versionId, List<String> nodeIds) {
        return queryRepo.countEdgesConnectedToNodes(projectId, versionId, nodeIds);
    }

    public Map<String, long[]> queryTableStats(String projectId, String versionId, List<String> nodeIds) {
        return queryRepo.queryTableStats(projectId, versionId, nodeIds);
    }

    // ==================== 写入方法 → WriteRepository ====================

    public GraphNode createNode(GraphNode node) {
        return writeRepo.createNode(node);
    }

    public NodeUpsert mergeNode(GraphNode node) {
        var result = writeRepo.mergeNode(node);
        return new NodeUpsert(result.node(), result.created());
    }

    public EdgeUpsert mergeEdge(GraphEdge edge) {
        var result = writeRepo.mergeEdge(edge);
        return new EdgeUpsert(result.edge(), result.created());
    }

    public void setNodeProperty(String nodeId, String property, Object value) {
        writeRepo.setNodeProperty(nodeId, property, value);
    }

    /**
     * 通用写入 Cypher 执行（不返回结果集），供 service 层调用，避免直接依赖 Neo4j Driver。
     * <p>委托给 {@link Neo4jWriteRepository#executeWriteQuery}。</p>
     *
     * @param cypher  Cypher 写入语句（SET / REMOVE / MERGE 等）
     * @param params  Cypher 参数
     */
    public void executeWriteQuery(String cypher, Map<String, Object> params) {
        writeRepo.executeWriteQuery(cypher, params);
    }

    public void setEdgeProperty(String edgeId, String property, Object value) {
        writeRepo.setEdgeProperty(edgeId, property, value);
    }

    /**
     * 清除指定版本中所有节点的 affected 和 affectedReason 标记。
     * <p>与 {@link #setNodeProperty} 配对：markAffectedNodes 写入 affected/affectedReason，
     * 本方法在增量重扫前清除上一轮标记。仅清除 affected=true 的节点。
     * 详见 {@link Neo4jWriteRepository#clearAffectedMarkers}。</p>
     *
     * @param projectId 项目 ID
     * @param versionId 版本 ID
     * @return 清除标记的节点数
     */
    public int clearAffectedMarkers(String projectId, String versionId) {
        return writeRepo.clearAffectedMarkers(projectId, versionId);
    }

    /**
     * 将 sourceNodeId 的所有关系迁移到 targetNodeId 后删除 sourceNodeId。
     * 用于跨语言 Feature 去重合并。
     * @return 迁移的边数
     */
    public int moveEdgesAndDeleteNode(String projectId, String versionId,
                                       String sourceNodeId, String targetNodeId) {
        return writeRepo.moveEdgesAndDeleteNode(projectId, versionId, sourceNodeId, targetNodeId);
    }

    public void mergeNodesBatch(String projectId, String versionId,
                                 List<Neo4jWriteRepository.BatchNodeUpsert> nodes) {
        writeRepo.mergeNodesBatch(projectId, versionId, nodes);
    }

    public void mergeEdgesBatch(String projectId, String versionId,
                                 List<Neo4jWriteRepository.BatchEdgeUpsert> edges) {
        writeRepo.mergeEdgesBatch(projectId, versionId, edges);
    }

    public void mergeEdgesByKeyBatch(String projectId, String versionId,
                                      List<Neo4jWriteRepository.BatchEdgeByKeyUpsert> edges) {
        writeRepo.mergeEdgesByKeyBatch(projectId, versionId, edges);
    }

    public GraphEdge createEdge(GraphEdge edge) {
        return writeRepo.createEdge(edge);
    }

    public void updateNode(GraphNode node) {
        writeRepo.updateNode(node);
    }

    public void updateEdge(GraphEdge edge) {
        writeRepo.updateEdge(edge);
    }

    public int mergeNodesBatch(List<GraphNode> nodes) {
        return writeRepo.mergeNodesBatch(nodes);
    }

    public int mergeEdgesBatch(List<GraphEdge> edges) {
        return writeRepo.mergeEdgesBatch(edges);
    }

    public void updateEdgeFromNode(String oldNodeId, String newNodeId, String projectId) {
        writeRepo.updateEdgeFromNode(oldNodeId, newNodeId, projectId);
    }

    public void updateEdgeToNode(String oldNodeId, String newNodeId, String projectId) {
        writeRepo.updateEdgeToNode(oldNodeId, newNodeId, projectId);
    }

    // ==================== Projection / Stats → ProjectionRepository ====================

    public List<Map<String, Object>> queryNodesProjection(String projectId, String versionId,
                                                          Double minConfidence, String status) {
        return projectionRepo.queryNodesProjection(projectId, versionId, minConfidence, status);
    }

    public List<Map<String, Object>> queryEdgesProjection(String projectId, String versionId,
                                                           Double minConfidence, String status) {
        return projectionRepo.queryEdgesProjection(projectId, versionId, minConfidence, status);
    }

    /** 窗口查询节点（游标分页 + 多条件过滤） */
    public List<Map<String, Object>> queryNodesWindow(String projectId, String versionId,
                                                       List<String> nodeTypes, List<String> sourceTypes,
                                                       String status, Double minConfidence,
                                                       String cursor, int limit) {
        return projectionRepo.queryNodesWindow(projectId, versionId, nodeTypes, sourceTypes,
                status, minConfidence, cursor, limit);
    }

    /** 查询指定节点集合之间的边 */
    public List<Map<String, Object>> queryEdgesForNodes(String projectId, String versionId,
                                                         List<String> nodeIds) {
        return projectionRepo.queryEdgesForNodes(projectId, versionId, nodeIds);
    }

    public Map<String, Object> graphStats(String projectId) {
        return projectionRepo.graphStats(projectId);
    }

    public List<Map<String, Object>> nodeTypeStats(String projectId) {
        return projectionRepo.nodeTypeStats(projectId);
    }

    public Map<String, Object> versionGraphStats(String projectId, String versionId) {
        return projectionRepo.versionGraphStats(projectId, versionId);
    }

    public List<Map<String, Object>> confidenceTrendDaily(String projectId, String versionId) {
        return projectionRepo.confidenceTrendDaily(projectId, versionId);
    }

    /**
     * 每个 ApiEndpoint 的实现层回溯（Controller / Service / Table），用于系统关系总览投影。
     * <p>双向遍历真实边方向，避免有向 BFS 在 Method 处断链。详见
     * {@link Neo4jProjectionRepository#apiImplementationRelations}。</p>
     */
    public List<Map<String, Object>> apiImplementationRelations(String projectId, String versionId) {
        return projectionRepo.apiImplementationRelations(projectId, versionId);
    }

    /**
     * 每个 Mapper 访问的 Table 集合（Mapper→SqlStatement→Table），用于补全数据表访问关系。
     * 详见 {@link Neo4jProjectionRepository#tableAccessRelations}。
     */
    public List<Map<String, Object>> tableAccessRelations(String projectId, String versionId) {
        return projectionRepo.tableAccessRelations(projectId, versionId);
    }

    /**
     * 查询真实 BusinessDomain 节点及其 CONTAINS 目标，用于系统关系总览摄入。
     * 详见 {@link Neo4jProjectionRepository#businessDomainContains}。
     */
    public List<Map<String, Object>> businessDomainContains(String projectId, String versionId) {
        return projectionRepo.businessDomainContains(projectId, versionId);
    }

    public List<Map<String, Object>> confidenceDistribution(String projectId, String versionId) {
        return projectionRepo.confidenceDistribution(projectId, versionId);
    }

    public double averageNodeDegree(String projectId, String versionId) {
        return projectionRepo.averageNodeDegree(projectId, versionId);
    }

    // ---- 图谱质量评估查询 ----

    /** 版本级节点类型分布，返回每行 {nodeType, cnt} */
    public List<Map<String, Object>> nodeTypeDistribution(String projectId, String versionId) {
        return projectionRepo.nodeTypeDistribution(projectId, versionId);
    }

    /** 版本级边类型分布，返回每行 {edgeType, cnt} */
    public List<Map<String, Object>> edgeTypeDistribution(String projectId, String versionId) {
        return projectionRepo.edgeTypeDistribution(projectId, versionId);
    }

    /** 统计孤立节点数（无任何边的节点） */
    public long countIsolatedNodes(String projectId, String versionId) {
        return projectionRepo.countIsolatedNodes(projectId, versionId);
    }

    /**
     * 统计指定标签中缺少必需边类型的节点数（约束违反计数）。
     *
     * @param nodeLabel 节点标签
     * @param edgeTypes 必需的边类型列表（满足任意一个即可）
     * @return 缺少所有指定边类型的节点数
     */
    public long countNodesWithoutEdgeTypes(String projectId, String versionId,
                                            String nodeLabel, List<String> edgeTypes) {
        return projectionRepo.countNodesWithoutEdgeTypes(projectId, versionId, nodeLabel, edgeTypes);
    }

    /**
     * 抽样边用于准确性评估：返回指定数量的边，每条边包含 fromNodeId/toNodeId/edgeType/fromNodeType/toNodeType。
     */
    public List<Map<String, Object>> sampleEdgesForAccuracy(String projectId, String versionId, int sampleSize) {
        String cypher = "MATCH (n)-[r]-(m) WHERE n.projectId=$projectId AND n.versionId=$versionId " +
                "RETURN n.nodeId as fromNodeId, n.nodeType as fromNodeType, " +
                "m.nodeId as toNodeId, m.nodeType as toNodeType, type(r) as edgeType LIMIT $limit";
        Map<String, Object> params = Map.of("projectId", projectId, "versionId", versionId, "limit", sampleSize);
        return projectionRepo.executeReadQuery(cypher, params);
    }

    /**
     * 统计悬空边数 — 端点节点 id 为空的关系数。
     * <p>Neo4j 关系模型中关系两端节点由图结构保证存在，此查询为防御性检查
     * （端点节点 id 属性缺失视为悬空）。真实环境通常返回 0。</p>
     */
    public long countDanglingEdges(String projectId, String versionId) {
        String cypher = "MATCH (n)-[r]->(m) WHERE r.projectId=$projectId AND r.versionId=$versionId " +
                "AND (n.id IS NULL OR n.id = '' OR m.id IS NULL OR m.id = '') " +
                "RETURN count(r) AS cnt";
        Map<String, Object> params = Map.of("projectId", projectId, "versionId", normalizeId(versionId));
        List<Map<String, Object>> rows = projectionRepo.executeReadQuery(cypher, params);
        if (rows.isEmpty()) return 0L;
        Object v = rows.get(0).get("cnt");
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * 统计重复节点数 — 按 (projectId, versionId, nodeType, nodeKey) 分组，计数大于 1 的节点总数。
     */
    public long countDuplicateNodes(String projectId, String versionId) {
        String cypher = "MATCH (n) WHERE n.projectId=$projectId AND n.versionId=$versionId " +
                "WITH n.nodeType AS nodeType, n.nodeKey AS nodeKey, count(n) AS cnt " +
                "WHERE cnt > 1 RETURN sum(cnt) AS total";
        Map<String, Object> params = Map.of("projectId", projectId, "versionId", normalizeId(versionId));
        List<Map<String, Object>> rows = projectionRepo.executeReadQuery(cypher, params);
        if (rows.isEmpty()) return 0L;
        Object v = rows.get(0).get("total");
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * 抽样边用于三元组准确率评估 — 返回指定数量的边，每条边包含 edgeId 及端点信息。
     * <p>与 {@link #sampleEdgesForAccuracy} 的区别：额外返回 {@code r.id} 作为 edgeId，
     * 供调用方批量查询 EdgeEvidence 关联记录以判定是否有证据支撑。</p>
     */
    public List<Map<String, Object>> sampleEdgesWithEvidence(String projectId, String versionId, int sampleSize) {
        String cypher = "MATCH (n)-[r]->(m) WHERE r.projectId=$projectId AND r.versionId=$versionId " +
                "RETURN r.id AS edgeId, n.id AS fromNodeId, n.nodeType AS fromNodeType, " +
                "m.id AS toNodeId, m.nodeType AS toNodeType, type(r) AS edgeType LIMIT $limit";
        Map<String, Object> params = Map.of("projectId", projectId, "versionId", normalizeId(versionId),
                "limit", sampleSize);
        return projectionRepo.executeReadQuery(cypher, params);
    }

    // ---- Blast Radius 传播分析查询 ----

    /**
     * 按 sourcePath 查询文件中包含的所有图谱节点（用于变更影响分析）。
     * <p>versionId 为 null 时查询项目下所有版本。详见
     * {@link Neo4jProjectionRepository#findNodesBySourcePath}。</p>
     *
     * @return 每行包含 {nodeId, nodeKey, nodeName, nodeType, sourcePath}
     */
    public List<Map<String, Object>> findNodesBySourcePath(String projectId, String versionId,
                                                            String sourcePath) {
        return projectionRepo.findNodesBySourcePath(projectId, versionId, sourcePath);
    }

    /**
     * 反向依赖查询 — 查找指向目标节点集合的入边源节点（Blast Radius 传播分析核心查询）。
     * <p>versionId 为 null 时查询项目下所有版本。详见
     * {@link Neo4jProjectionRepository#findReverseDependents}。</p>
     *
     * @param targetNodeIds 变更文件中节点的 ID 集合
     * @param edgeTypes     反向遍历的边类型白名单
     * @return 每行包含 {sourceId, sourceKey, sourceName, sourceType, targetId, targetKey, edgeType}
     */
    public List<Map<String, Object>> findReverseDependents(String projectId, String versionId,
                                                            Collection<String> targetNodeIds,
                                                            List<String> edgeTypes) {
        return projectionRepo.findReverseDependents(projectId, versionId, targetNodeIds, edgeTypes);
    }

    /**
     * 查询指定节点集合之间的所有边（仅按 projectId 过滤，不限定 versionId）。
     * <p>用于 Blast Radius 受影响子图的边集构建。详见
     * {@link Neo4jProjectionRepository#queryEdgesForNodesByProject}。</p>
     *
     * @return 每行包含 {id, type, source, target}
     */
    public List<Map<String, Object>> queryEdgesForNodesByProject(String projectId, List<String> nodeIds) {
        return projectionRepo.queryEdgesForNodesByProject(projectId, nodeIds);
    }

    /**
     * 通用只读 Cypher 查询委托，避免 service 层直接依赖 Neo4j Driver。
     *
     * @return 每行结果为 Map
     */
    public List<Map<String, Object>> executeReadQuery(String cypher, Map<String, Object> params) {
        return projectionRepo.executeReadQuery(cypher, params);
    }

    // ==================== 删除 / 清理 → AdminRepository ====================

    public void deleteGraph(String projectId, String versionId) {
        adminRepo.deleteGraph(projectId, versionId);
    }

    public void deleteProjectGraph(String projectId) {
        adminRepo.deleteProjectGraph(projectId);
    }

    public void deleteNode(String projectId, String versionId, String nodeId) {
        adminRepo.deleteNode(projectId, versionId, nodeId);
    }

    /** 按 sourcePath 删除指定版本中来自某个源文件的所有节点（用于增量删除）。 */
    public int deleteNodesBySourcePath(String projectId, String versionId, String sourcePath) {
        return adminRepo.deleteNodesBySourcePath(projectId, versionId, sourcePath);
    }

    /** 批量按 sourcePath 列表删除节点（用于增量删除场景）。 */
    public int deleteNodesBySourcePaths(String projectId, String versionId, java.util.List<String> sourcePaths) {
        return adminRepo.deleteNodesBySourcePaths(projectId, versionId, sourcePaths);
    }

    public GraphifyDeleteResult deleteGraphifyClaims(String projectId, String versionId) {
        long[] result = adminRepo.deleteGraphifyClaims(projectId, versionId);
        return new GraphifyDeleteResult(result[0], result[1]);
    }

    /**
     * 克隆版本图谱：将旧版本的所有节点和边复制到新版本。
     * 用于增量扫描前继承上一版本的完整图谱，确保新版本拥有全量数据。
     *
     * @param projectId     项目 ID
     * @param fromVersionId 源版本 ID（上一成功版本）
     * @param toVersionId   目标版本 ID（当前增量版本）
     * @return 克隆的节点数 + 边数
     */
    public int cloneVersionGraph(String projectId, String fromVersionId, String toVersionId) {
        return writeRepo.cloneVersionGraph(projectId, fromVersionId, toVersionId);
    }

    // ==================== Schema → SchemaRepository ====================

    public void createConstraints() {
        schemaRepo.createConstraints();
    }

    public void createIndexes() {
        schemaRepo.createIndexes();
    }

    public void ensureIndexesAndConstraints() {
        schemaRepo.ensureIndexesAndConstraints();
    }

    /**
     * 存根：查找孤立节点（无入边和出边的节点），待完整实现。
     */
    public List<GraphNode> findIsolatedNodes(String projectId, String versionId, String nodeType) {
        log.debug("findIsolatedNodes stub: projectId={}, versionId={}, nodeType={}", projectId, versionId, nodeType);
        return List.of();
    }
}
