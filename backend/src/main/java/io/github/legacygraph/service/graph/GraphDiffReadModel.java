package io.github.legacygraph.service.graph;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 图谱版本差异读取模型 — 对比两个版本的节点和边变化
 */
@Slf4j
@Component
public class GraphDiffReadModel {

    private final Neo4jGraphDao neo4jGraphDao;

    public GraphDiffReadModel(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * 对比两个版本的图谱差异
     *
     * @param projectId   项目ID
     * @param versionIdA  基准版本ID
     * @param versionIdB  目标版本ID
     * @return 差异结果，包含新增/删除的节点和边
     */
    public DiffResult diffVersions(String projectId, String versionIdA, String versionIdB) {
        log.info("Diffing versions: projectId={}, versionA={}, versionB={}", 
                projectId, versionIdA, versionIdB);

        // 查询两个版本的所有节点
        List<GraphNode> nodesA = neo4jGraphDao.queryNodes(projectId, versionIdA, 
                null, null, null, null, 0);
        List<GraphNode> nodesB = neo4jGraphDao.queryNodes(projectId, versionIdB, 
                null, null, null, null, 0);

        // 查询两个版本的所有边
        List<GraphEdge> edgesA = neo4jGraphDao.queryEdges(projectId, versionIdA, 
                null, null, null, null, null, 0);
        List<GraphEdge> edgesB = neo4jGraphDao.queryEdges(projectId, versionIdB, 
                null, null, null, null, null, 0);

        // 节点差异计算（使用 nodeKey + nodeType 作为唯一标识）
        Map<String, GraphNode> nodeMapA = nodesA.stream()
                .collect(Collectors.toMap(
                        n -> buildNodeKey(n),
                        n -> n,
                        (n1, n2) -> n1
                ));
        Map<String, GraphNode> nodeMapB = nodesB.stream()
                .collect(Collectors.toMap(
                        n -> buildNodeKey(n),
                        n -> n,
                        (n1, n2) -> n1
                ));

        List<DiffItem> addedNodes = new ArrayList<>();
        List<DiffItem> removedNodes = new ArrayList<>();

        // 找出新增的节点（在B中但不在A中）
        for (Map.Entry<String, GraphNode> entry : nodeMapB.entrySet()) {
            if (!nodeMapA.containsKey(entry.getKey())) {
                GraphNode node = entry.getValue();
                addedNodes.add(new DiffItem(
                        node.getId(),
                        node.getNodeType(),
                        node.getNodeName(),
                        "NODE"
                ));
            }
        }

        // 找出删除的节点（在A中但不在B中）
        for (Map.Entry<String, GraphNode> entry : nodeMapA.entrySet()) {
            if (!nodeMapB.containsKey(entry.getKey())) {
                GraphNode node = entry.getValue();
                removedNodes.add(new DiffItem(
                        node.getId(),
                        node.getNodeType(),
                        node.getNodeName(),
                        "NODE"
                ));
            }
        }

        // 边差异计算（使用 edgeKey + edgeType 作为唯一标识）
        Map<String, GraphEdge> edgeMapA = edgesA.stream()
                .collect(Collectors.toMap(
                        e -> buildEdgeKey(e),
                        e -> e,
                        (e1, e2) -> e1
                ));
        Map<String, GraphEdge> edgeMapB = edgesB.stream()
                .collect(Collectors.toMap(
                        e -> buildEdgeKey(e),
                        e -> e,
                        (e1, e2) -> e1
                ));

        List<DiffItem> addedEdges = new ArrayList<>();
        List<DiffItem> removedEdges = new ArrayList<>();

        // 找出新增的边（在B中但不在A中）
        for (Map.Entry<String, GraphEdge> entry : edgeMapB.entrySet()) {
            if (!edgeMapA.containsKey(entry.getKey())) {
                GraphEdge edge = entry.getValue();
                addedEdges.add(new DiffItem(
                        edge.getId(),
                        edge.getEdgeType(),
                        edge.getEdgeKey(),
                        "EDGE"
                ));
            }
        }

        // 找出删除的边（在A中但不在B中）
        for (Map.Entry<String, GraphEdge> entry : edgeMapA.entrySet()) {
            if (!edgeMapB.containsKey(entry.getKey())) {
                GraphEdge edge = entry.getValue();
                removedEdges.add(new DiffItem(
                        edge.getId(),
                        edge.getEdgeType(),
                        edge.getEdgeKey(),
                        "EDGE"
                ));
            }
        }

        log.info("Diff completed: addedNodes={}, removedNodes={}, addedEdges={}, removedEdges={}",
                addedNodes.size(), removedNodes.size(), addedEdges.size(), removedEdges.size());

        return new DiffResult(addedNodes, removedNodes, addedEdges, removedEdges);
    }

    /**
     * 构建节点唯一标识：nodeKey + nodeType
     */
    private String buildNodeKey(GraphNode node) {
        String nodeKey = node.getNodeKey() != null ? node.getNodeKey() : "";
        String nodeType = node.getNodeType() != null ? node.getNodeType() : "";
        return nodeKey + "::" + nodeType;
    }

    /**
     * 构建边唯一标识：edgeKey + edgeType
     */
    private String buildEdgeKey(GraphEdge edge) {
        String edgeKey = edge.getEdgeKey() != null ? edge.getEdgeKey() : "";
        String edgeType = edge.getEdgeType() != null ? edge.getEdgeType() : "";
        return edgeKey + "::" + edgeType;
    }

    /**
     * 差异项记录
     */
    public record DiffItem(String id, String type, String name, String category) {
    }

    /**
     * 差异结果
     */
    public record DiffResult(
            List<DiffItem> addedNodes,
            List<DiffItem> removedNodes,
            List<DiffItem> addedEdges,
            List<DiffItem> removedEdges
    ) {
    }
}
