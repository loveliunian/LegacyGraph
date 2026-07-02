package io.github.legacygraph.service;

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
        PathChain chain = new PathChain();
        chain.nodes = new ArrayList<>();

        Optional<GraphNode> apiNode = neo4jGraphDao.findNode(projectId, versionId, "ApiEndpoint", apiKey);
        if (apiNode.isEmpty()) return chain;

        chain.startNodeId = apiNode.get().getId();
        chain.nodes.add(toInfo(apiNode.get()));

        // 扫描该版本所有边，构建从 API 出发的下游路径
        List<GraphEdge> allEdges = neo4jGraphDao.queryEdges(projectId, versionId, null, null, 200);
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
        PathChain chain = new PathChain();
        chain.nodes = new ArrayList<>();

        List<GraphNode> tables = neo4jGraphDao.queryNodes(projectId, versionId, "Table",
                null, null, null, 50);
        Optional<GraphNode> target = tables.stream()
                .filter(t -> tableName.equalsIgnoreCase(t.getNodeName()))
                .findFirst();
        if (target.isEmpty()) return chain;

        chain.startNodeId = target.get().getId();
        chain.nodes.add(toInfo(target.get()));

        // 反向查找：谁写到这个表
        List<GraphEdge> allEdges = neo4jGraphDao.queryEdges(projectId, versionId, null, null, 200);
        Set<String> visited = new HashSet<>();
        visited.add(target.get().getId());

        for (int depth = 0; depth < 8; depth++) {
            List<String> prevIds = new ArrayList<>();
            for (GraphEdge edge : allEdges) {
                if (visited.contains(edge.getToNodeId()) && !visited.contains(edge.getFromNodeId())) {
                    prevIds.add(edge.getFromNodeId());
                    visited.add(edge.getFromNodeId());
                }
            }
            // 批量加载本跳所有节点，替代逐条 findNodeById
            if (!prevIds.isEmpty()) {
                List<GraphNode> nodes = neo4jGraphDao.findNodesByIds(prevIds);
                for (GraphNode n : nodes) {
                    chain.nodes.add(toInfo(n));
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

    // ==================== DTO ====================

    public static class PathChain {
        public String startNodeId;
        public List<NodeInfo> nodes;
    }

    public record NodeInfo(String id, String type, String name, String displayName,
                           String sourcePath, double confidence, String status) {}
}
