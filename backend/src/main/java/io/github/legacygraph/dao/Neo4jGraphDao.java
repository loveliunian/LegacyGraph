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

    public void setEdgeProperty(String edgeId, String property, Object value) {
        writeRepo.setEdgeProperty(edgeId, property, value);
    }

    public void mergeNodesBatch(String projectId, String versionId,
                                 List<Neo4jWriteRepository.BatchNodeUpsert> nodes) {
        writeRepo.mergeNodesBatch(projectId, versionId, nodes);
    }

    public void mergeEdgesBatch(String projectId, String versionId,
                                 List<Neo4jWriteRepository.BatchEdgeUpsert> edges) {
        writeRepo.mergeEdgesBatch(projectId, versionId, edges);
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

    public List<Map<String, Object>> confidenceDistribution(String projectId, String versionId) {
        return projectionRepo.confidenceDistribution(projectId, versionId);
    }

    public double averageNodeDegree(String projectId, String versionId) {
        return projectionRepo.averageNodeDegree(projectId, versionId);
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
}
