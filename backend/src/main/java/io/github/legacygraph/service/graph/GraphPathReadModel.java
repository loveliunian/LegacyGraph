package io.github.legacygraph.service.graph;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 图谱路径读模型 — 查询 API 调用链和表影响范围。
 * <p>Phase 2.6: 将 GraphQueryService 的原始 Cypher 收口到 Neo4jGraphDao。
 * 通过 queryEdges + findNodeById 构建路径，不直接持有 Driver。</p>
 */
@Slf4j
@Component
public class GraphPathReadModel {

    private final Neo4jGraphDao neo4jGraphDao;

    public GraphPathReadModel(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /** API 调用链 */
    public PathChain getApiCallChain(String projectId, String versionId, String apiKey) {
        // Neo4j 存储 versionId 无连字符，需规范化
        String normalizedVersionId = versionId != null ? versionId.replace("-", "") : null;

        PathChain chain = new PathChain();
        chain.nodes = new ArrayList<>();
        chain.edges = new ArrayList<>();

        Optional<GraphNode> apiNode = neo4jGraphDao.findNode(projectId, normalizedVersionId, "ApiEndpoint", apiKey);
        if (apiNode.isEmpty()) return chain;

        chain.startNodeId = apiNode.get().getId();
        chain.nodes.add(toInfo(apiNode.get()));

        // 扫描该版本所有边，构建从 API 出发的下游路径
        List<GraphEdge> allEdges = neo4jGraphDao.queryEdges(projectId, normalizedVersionId, null, null, 200);
        Set<String> visited = new HashSet<>();
        visited.add(apiNode.get().getId());

        for (int depth = 0; depth < 8; depth++) {
            List<String> nextIds = new ArrayList<>();
            for (GraphEdge edge : allEdges) {
                if (visited.contains(edge.getFromNodeId()) && !visited.contains(edge.getToNodeId())) {
                    nextIds.add(edge.getToNodeId());
                    visited.add(edge.getToNodeId());
                }
            }
            // 批量加载本跳所有节点，替代逐条 findNodeById
            if (!nextIds.isEmpty()) {
                List<GraphNode> nodes = neo4jGraphDao.findNodesByIds(nextIds);
                for (GraphNode n : nodes) {
                    chain.nodes.add(toInfo(n));
                }
            }
        }
        return chain;
    }

    /** 表影响范围 */
    public PathChain getTableImpact(String projectId, String versionId, String tableName) {
        // Neo4j 存储 versionId 无连字符，需规范化
        String normalizedVersionId = versionId != null ? versionId.replace("-", "") : null;

        PathChain chain = new PathChain();
        chain.nodes = new ArrayList<>();
        chain.edges = new ArrayList<>();

        List<GraphNode> tables = neo4jGraphDao.queryNodes(projectId, normalizedVersionId, "Table",
                null, null, null, 200);
        Optional<GraphNode> target = tables.stream()
                .filter(t -> tableName.equalsIgnoreCase(t.getNodeName()))
                .findFirst();
        if (target.isEmpty()) return chain;

        chain.startNodeId = target.get().getId();
        chain.nodes.add(toInfo(target.get()));

        // 加载所有边
        List<GraphEdge> allEdges = neo4jGraphDao.queryEdges(projectId, normalizedVersionId, null, null, 500);
        Set<String> visited = new HashSet<>();
        visited.add(target.get().getId());

        // 反向遍历：谁依赖/引用这个表（上游：SqlStatement←Method←ApiEndpoint）
        // 同时记录经过的边
        for (int depth = 0; depth < 6; depth++) {
            List<String> prevIds = new ArrayList<>();
            for (GraphEdge edge : allEdges) {
                if (visited.contains(edge.getToNodeId()) && !visited.contains(edge.getFromNodeId())) {
                    prevIds.add(edge.getFromNodeId());
                    visited.add(edge.getFromNodeId());
                    // 记录边：上游节点→当前节点
                    chain.edges.add(toEdgeInfo(edge));
                }
            }
            if (!prevIds.isEmpty()) {
                List<GraphNode> nodes = neo4jGraphDao.findNodesByIds(prevIds);
                for (GraphNode n : nodes) {
                    chain.nodes.add(toInfo(n));
                }
            }
        }

        // 正向遍历：查找关联表（通过 REFERENCES / JOINS 边，当前表→引用表）
        Set<String> relatedVisited = new HashSet<>(visited);
        for (int depth = 0; depth < 3; depth++) {
            List<String> relatedIds = new ArrayList<>();
            for (GraphEdge edge : allEdges) {
                String edgeType = edge.getEdgeType();
                // 从已访问节点出发的正向边（fromNodeId 已访问，toNodeId 未访问）
                if (relatedVisited.contains(edge.getFromNodeId()) && !relatedVisited.contains(edge.getToNodeId())) {
                    // 收集 Table 类型的关联节点（REFERENCES/JOINS）
                    if ("REFERENCES".equalsIgnoreCase(edgeType) || "JOINS".equalsIgnoreCase(edgeType)) {
                        relatedIds.add(edge.getToNodeId());
                        relatedVisited.add(edge.getToNodeId());
                        // 记录边
                        chain.edges.add(toEdgeInfo(edge));
                    }
                    // 补充：SqlStatement 通过 READS/WRITES 访问的表（SQL-mediated 隐式关联）
                    else if ("READS".equalsIgnoreCase(edgeType) || "WRITES".equalsIgnoreCase(edgeType)) {
                        // 如果当前已访问节点是 SqlStatement，则收集它访问的 Table
                        relatedIds.add(edge.getToNodeId());
                        relatedVisited.add(edge.getToNodeId());
                        chain.edges.add(toEdgeInfo(edge));
                    }
                }
                // 反向：其他表通过 REFERENCES 引用当前表（即当前表是被引用方）
                if (relatedVisited.contains(edge.getToNodeId()) && !relatedVisited.contains(edge.getFromNodeId())) {
                    if ("REFERENCES".equalsIgnoreCase(edgeType)) {
                        relatedIds.add(edge.getFromNodeId());
                        relatedVisited.add(edge.getFromNodeId());
                        // 记录边
                        chain.edges.add(toEdgeInfo(edge));
                    }
                }
            }
            if (!relatedIds.isEmpty()) {
                List<GraphNode> nodes = neo4jGraphDao.findNodesByIds(relatedIds);
                for (GraphNode n : nodes) {
                    chain.nodes.add(toInfo(n));
                }
            }
        }

        // 补充：如果反向遍历没有找到上游（SqlStatement 缺失），尝试通过共享访问推断隐式关联
        // 查找所有访问过当前表的 SqlStatement，然后收集它们访问的其他表
        if (chain.nodes.size() <= 1) {
            log.debug("Table {} has minimal upstream impact, attempting implicit association inference", tableName);
            Set<String> implicitTableIds = new HashSet<>();
            
            // 找到所有通过 READS/WRITES 访问当前表的 SqlStatement
            Set<String> sqlStatementIds = new HashSet<>();
            for (GraphEdge edge : allEdges) {
                if (("READS".equalsIgnoreCase(edge.getEdgeType()) || "WRITES".equalsIgnoreCase(edge.getEdgeType()))
                        && target.get().getId().equals(edge.getToNodeId())) {
                    sqlStatementIds.add(edge.getFromNodeId());
                }
            }
            
            // 收集这些 SqlStatement 访问的其他表
            for (GraphEdge edge : allEdges) {
                if (("READS".equalsIgnoreCase(edge.getEdgeType()) || "WRITES".equalsIgnoreCase(edge.getEdgeType()))
                        && sqlStatementIds.contains(edge.getFromNodeId())
                        && !target.get().getId().equals(edge.getToNodeId())) {
                    implicitTableIds.add(edge.getToNodeId());
                }
            }
            
            // 添加隐式关联的表
            if (!implicitTableIds.isEmpty()) {
                List<GraphNode> implicitTables = neo4jGraphDao.findNodesByIds(new ArrayList<>(implicitTableIds));
                for (GraphNode n : implicitTables) {
                    if ("Table".equals(n.getNodeType()) && !relatedVisited.contains(n.getId())) {
                        chain.nodes.add(toInfo(n));
                        relatedVisited.add(n.getId());
                    }
                }
            }
        }

        return chain;
    }

    private NodeInfo toInfo(GraphNode node) {
        return new NodeInfo(node.getId(), node.getNodeType(), node.getNodeName(),
                node.getDisplayName(), node.getSourcePath(),
                node.getConfidence() != null ? node.getConfidence().doubleValue() : 1.0,
                node.getStatus());
    }

    private EdgeInfo toEdgeInfo(GraphEdge edge) {
        return new EdgeInfo(edge.getId(), edge.getEdgeType(),
                edge.getFromNodeId(), edge.getToNodeId());
    }

    // ==================== DTO ====================

    public static class PathChain {
        public String startNodeId;
        public List<NodeInfo> nodes;
        public List<EdgeInfo> edges;
    }

    public record NodeInfo(String id, String type, String name, String displayName,
                           String sourcePath, double confidence, String status) {}

    public record EdgeInfo(String id, String type, String source, String target) {}
}
