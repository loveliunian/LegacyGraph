package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 图谱投影读模型 — 功能视图和业务视图的投影查询。
 * <p>Phase 2.6: 替代 GraphQueryService 中 getFeatureView/getBusinessView 的
 * 原始 Cypher 查询，使用 Neo4jGraphDao + 强类型 DTO。</p>
 */
@Slf4j
@Component
public class GraphProjectionReadModel {

    private final Neo4jGraphDao neo4jGraphDao;

    public GraphProjectionReadModel(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    private static final Set<String> FEATURE_NODE_TYPES = Set.of(
            "Feature", "ApiEndpoint", "Service", "Repository", "Page");
    private static final Set<String> BUSINESS_NODE_TYPES = Set.of(
            "BusinessDomain", "BusinessProcess", "BusinessObject", "BusinessRule");

    /**
     * 获取功能视图：指定模块或全部 Feature/Page/Api/Service/Repository 节点 + 边。
     */
    public ProjectionView getFeatureView(String projectId, String versionId, String module) {
        ProjectionView view = new ProjectionView();
        view.module = module;
        view.projectId = projectId;
        view.versionId = versionId;

        for (String nodeType : FEATURE_NODE_TYPES) {
            List<GraphNode> nodes = neo4jGraphDao.queryNodes(
                    projectId, versionId, nodeType, null, null, null, 200);
            for (GraphNode node : nodes) {
                if (module != null && !module.isBlank()) {
                    // 按 module 过滤（如果节点有 module 属性）
                    // 简化：按 nodeName 或 displayName 匹配
                    String name = node.getNodeName() != null ? node.getNodeName() : "";
                    if (!name.contains(module)) continue;
                }
                view.nodes.add(toNodeView(node));
            }
        }

        // 查边
        List<GraphEdge> edges = neo4jGraphDao.queryEdges(projectId, versionId, null, null, 500);
        for (GraphEdge edge : edges) {
            view.edges.add(toEdgeView(edge));
        }

        return view;
    }

    /**
     * 获取业务视图：指定域或全部 BusinessDomain/Process/Object/Rule 节点 + 边。
     */
    public ProjectionView getBusinessView(String projectId, String versionId, String domain) {
        ProjectionView view = new ProjectionView();
        view.module = domain;
        view.projectId = projectId;
        view.versionId = versionId;

        for (String nodeType : BUSINESS_NODE_TYPES) {
            List<GraphNode> nodes = neo4jGraphDao.queryNodes(
                    projectId, versionId, nodeType, null, null, null, 200);
            for (GraphNode node : nodes) {
                if (domain != null && !domain.isBlank()) {
                    String name = node.getNodeName() != null ? node.getNodeName() : "";
                    if (!name.contains(domain)) continue;
                }
                view.nodes.add(toNodeView(node));
            }
        }

        List<GraphEdge> edges = neo4jGraphDao.queryEdges(projectId, versionId, null, null, 500);
        for (GraphEdge edge : edges) {
            view.edges.add(toEdgeView(edge));
        }

        return view;
    }

    /** 获取图谱统计（一次 Cypher 获取总量和已确认数） */
    public GraphStats getGraphStats(String projectId, String versionId) {
        Map<String, Object> stats = neo4jGraphDao.versionGraphStats(projectId, versionId);
        long totalNodes = toLong(stats.get("totalNodes"));
        long confirmedNodes = toLong(stats.get("confirmedNodes"));
        return new GraphStats(totalNodes, confirmedNodes);
    }

    private static long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }

    private NodeView toNodeView(GraphNode node) {
        return new NodeView(node.getId(), node.getNodeType(), node.getNodeName(),
                node.getDisplayName(), node.getProperties(), node.getConfidence(), node.getStatus());
    }

    private EdgeView toEdgeView(GraphEdge edge) {
        return new EdgeView(edge.getId(), edge.getFromNodeId(), edge.getToNodeId(),
                edge.getEdgeType(), edge.getConfidence(), edge.getStatus());
    }

    // ==================== DTO ====================

    public static class ProjectionView {
        public String projectId;
        public String versionId;
        public String module;
        public List<NodeView> nodes = new ArrayList<>();
        public List<EdgeView> edges = new ArrayList<>();
    }

    public record NodeView(String id, String type, String name, String displayName,
                           String properties, java.math.BigDecimal confidence, String status) {}

    public record EdgeView(String id, String fromNodeId, String toNodeId,
                           String type, java.math.BigDecimal confidence, String status) {}

    public record GraphStats(long totalNodes, long confirmedNodes) {}
}
