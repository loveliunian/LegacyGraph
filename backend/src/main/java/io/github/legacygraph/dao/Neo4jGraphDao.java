package io.github.legacygraph.dao;

import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    public Optional<GraphEdge> findEdgeById(String edgeId) {
        return queryRepo.findEdgeById(edgeId);
    }

    public long countEdges(String projectId, String versionId, String status) {
        return queryRepo.countEdges(projectId, versionId, status);
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
