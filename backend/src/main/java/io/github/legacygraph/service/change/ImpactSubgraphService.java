package io.github.legacygraph.service.change;

import io.github.legacygraph.common.TraversalDirection;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 影响子图服务（增强版2：ChangeTask 管道）。
 * <p>
 * 从 Neo4j 目标节点邻域提取 impacted subgraph，回填 {@code ChangeImpactAgent} 当前
 * 纯 String 的 {@code dependencies} 入参（见 doc §现有 Agent 到 PatchPlan 的差距）。
 * 复用 {@link Neo4jGraphDao} 的 8 参 {@code queryEdges} 按 connectedNodeId 取邻域，
 * 避免全量扫描。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImpactSubgraphService {

    private final Neo4jGraphDao neo4jGraphDao;

    /** 邻域跳数上限（默认 1 跳；ChangeTask MVP 只需直接邻域） */
    private static final int MAX_NEIGHBORS = 200;

    /** 多跳路径数上限（变更影响需更全，高于 QA GraphRAG 的 20） */
    private static final int MAX_PATHS = 50;

    /**
     * 以目标节点为中心提取影响子图。
     *
     * @param projectId 项目ID
     * @param versionId 版本ID（可空）
     * @param targetNodeId 变更目标节点ID
     */
    public ImpactSubgraph extractByNode(String projectId, String versionId, String targetNodeId) {
        Optional<GraphNode> targetOpt = neo4jGraphDao.findNodeById(targetNodeId);
        if (targetOpt.isEmpty()) {
            log.warn("ImpactSubgraph: target node not found: {}", targetNodeId);
            return ImpactSubgraph.builder()
                    .targetNodeId(targetNodeId)
                    .nodeIds(new ArrayList<>())
                    .edgeIds(new ArrayList<>())
                    .impactedFiles(new ArrayList<>())
                    .dependencySummary("目标节点不存在，无法定位影响子图。")
                    .build();
        }

        GraphNode target = targetOpt.get();
        // 取与目标节点直接相连的所有边（Cypher 内 connectedNodeId 过滤，非全量扫描）
        List<GraphEdge> edges = neo4jGraphDao.queryEdges(
                projectId, versionId, null, null, targetNodeId, null, null, MAX_NEIGHBORS);

        Set<String> nodeIds = new LinkedHashSet<>();
        nodeIds.add(targetNodeId);
        List<String> edgeIds = new ArrayList<>();
        for (GraphEdge edge : edges) {
            edgeIds.add(edge.getId());
            if (edge.getFromNodeId() != null) nodeIds.add(edge.getFromNodeId());
            if (edge.getToNodeId() != null) nodeIds.add(edge.getToNodeId());
        }

        // 批量取邻域节点详情，收集受影响文件（范围校验白名单）
        List<GraphNode> nodes = neo4jGraphDao.findNodesByIds(new ArrayList<>(nodeIds));
        Set<String> impactedFiles = new LinkedHashSet<>();
        Map<String, GraphNode> nodeById = new HashMap<>();
        for (GraphNode n : nodes) {
            nodeById.put(n.getId(), n);
            if (n.getSourcePath() != null && !n.getSourcePath().isBlank()) {
                impactedFiles.add(n.getSourcePath());
            }
        }

        String summary = buildDependencySummary(target, edges, nodeById);

        String targetName = target.getDisplayName() != null && !target.getDisplayName().isBlank()
                ? target.getDisplayName() : target.getNodeName();

        log.info("ImpactSubgraph extracted: target={}, nodes={}, edges={}, files={}",
                targetNodeId, nodeIds.size(), edgeIds.size(), impactedFiles.size());

        return ImpactSubgraph.builder()
                .targetNodeId(targetNodeId)
                .targetName(targetName)
                .targetNodeType(target.getNodeType())
                .nodeIds(new ArrayList<>(nodeIds))
                .edgeIds(edgeIds)
                .impactedFiles(new ArrayList<>(impactedFiles))
                .dependencySummary(summary)
                .build();
    }

    /**
     * 以目标节点为中心，沿指定方向做有界多跳反向遍历。
     * <p>
     * 修正 {@code doc/系统关系总览/02 §13.4} 偏移：原 {@link #extractByNode} 仅 1 跳，
     * 本方法支持多跳依赖链（Table←SQL←Mapper←Service←Api←Feature）。
     * </p>
     * <p>
     * 关键：{@code findPathsDirected} 按 nodeKey 匹配端点（非 nodeId），本方法入参是
     * targetNodeId，须先 {@code findNodeById} 取 nodeKey 再查路径。
     * </p>
     *
     * @param direction 反向链路类型，决定遍历的边类型与方向
     * @param maxDepth  最大跳数（钳制 [1,4]）
     */
    public ImpactSubgraph extractByNodeMultiHop(String projectId, String versionId,
                                                String targetNodeId,
                                                TraversalDirection direction,
                                                int maxDepth) {
        Optional<GraphNode> targetOpt = neo4jGraphDao.findNodeById(targetNodeId);
        if (targetOpt.isEmpty()) {
            log.warn("ImpactSubgraphMultiHop: target node not found: {}", targetNodeId);
            return ImpactSubgraph.builder()
                    .targetNodeId(targetNodeId)
                    .nodeIds(new ArrayList<>())
                    .edgeIds(new ArrayList<>())
                    .impactedFiles(new ArrayList<>())
                    .pathNodeKeys(new ArrayList<>())
                    .dependencySummary("目标节点不存在，无法定位影响子图。")
                    .build();
        }
        GraphNode target = targetOpt.get();
        int depth = Math.max(1, Math.min(4, maxDepth));

        List<Neo4jGraphDao.GraphPath> paths = neo4jGraphDao.findPathsDirected(
                projectId, versionId, target.getNodeKey(),
                direction.edgeTypes(), direction.flow(), depth, MAX_PATHS);

        Set<String> nodeIds = new LinkedHashSet<>();
        nodeIds.add(targetNodeId);
        List<String> edgeIds = new ArrayList<>();
        Set<String> impactedFiles = new LinkedHashSet<>();
        List<List<String>> pathNodeKeys = new ArrayList<>();
        Map<String, GraphNode> nodeById = new HashMap<>();
        nodeById.put(target.getId(), target);
        if (target.getSourcePath() != null && !target.getSourcePath().isBlank()) {
            impactedFiles.add(target.getSourcePath());
        }

        for (Neo4jGraphDao.GraphPath path : paths) {
            List<String> keys = new ArrayList<>();
            for (GraphNode n : path.nodes()) {
                nodeIds.add(n.getId());
                nodeById.put(n.getId(), n);
                if (n.getSourcePath() != null && !n.getSourcePath().isBlank()) {
                    impactedFiles.add(n.getSourcePath());
                }
                keys.add(n.getNodeKey());
            }
            if (!keys.isEmpty()) pathNodeKeys.add(keys);
            for (GraphEdge e : path.edges()) {
                edgeIds.add(e.getId());
            }
        }

        String summary = buildDependencySummaryMultiHop(target, paths);
        String targetName = target.getDisplayName() != null && !target.getDisplayName().isBlank()
                ? target.getDisplayName() : target.getNodeName();

        log.info("ImpactSubgraphMultiHop extracted: target={}, paths={}, nodes={}, files={}",
                targetNodeId, paths.size(), nodeIds.size(), impactedFiles.size());

        return ImpactSubgraph.builder()
                .targetNodeId(targetNodeId)
                .targetName(targetName)
                .targetNodeType(target.getNodeType())
                .nodeIds(new ArrayList<>(nodeIds))
                .edgeIds(edgeIds)
                .impactedFiles(new ArrayList<>(impactedFiles))
                .pathNodeKeys(pathNodeKeys)
                .dependencySummary(summary)
                .build();
    }

    /**
     * 把多跳路径序列化为 ChangeImpactAgent 可读的依赖摘要文本（路径链格式）。
     */
    private String buildDependencySummaryMultiHop(GraphNode target,
                                                  List<Neo4jGraphDao.GraphPath> paths) {
        StringBuilder sb = new StringBuilder();
        String targetName = target.getDisplayName() != null && !target.getDisplayName().isBlank()
                ? target.getDisplayName() : target.getNodeName();
        sb.append("变更目标：").append(targetName)
                .append("（类型 ").append(target.getNodeType()).append("）\n");
        if (paths.isEmpty()) {
            sb.append("该节点无多跳依赖路径。");
            return sb.toString();
        }
        sb.append("多跳依赖路径（").append(paths.size()).append(" 条）：\n");
        int shown = 0;
        for (Neo4jGraphDao.GraphPath path : paths) {
            if (shown++ >= 30) {
                sb.append("… 其余 ").append(paths.size() - 30).append(" 条省略\n");
                break;
            }
            sb.append("- ");
            List<GraphNode> nodes = path.nodes();
            List<GraphEdge> edges = path.edges();
            for (int i = 0; i < nodes.size(); i++) {
                sb.append(displayOf(nodes.get(i), nodes.get(i).getId()));
                if (i < edges.size()) {
                    sb.append(" -[").append(edges.get(i).getEdgeType()).append("]-> ");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 把影响子图序列化为 ChangeImpactAgent 可读的依赖摘要文本。
     */
    private String buildDependencySummary(GraphNode target, List<GraphEdge> edges,
                                          Map<String, GraphNode> nodeById) {
        StringBuilder sb = new StringBuilder();
        String targetName = target.getDisplayName() != null && !target.getDisplayName().isBlank()
                ? target.getDisplayName() : target.getNodeName();
        sb.append("变更目标：").append(targetName)
                .append("（类型 ").append(target.getNodeType()).append("）\n");
        if (edges.isEmpty()) {
            sb.append("该节点无直接依赖关系。");
            return sb.toString();
        }
        sb.append("直接依赖关系（").append(edges.size()).append(" 条）：\n");
        int shown = 0;
        for (GraphEdge edge : edges) {
            if (shown++ >= 50) {
                sb.append("… 其余 ").append(edges.size() - 50).append(" 条省略\n");
                break;
            }
            String fromName = displayOf(nodeById.get(edge.getFromNodeId()), edge.getFromNodeId());
            String toName = displayOf(nodeById.get(edge.getToNodeId()), edge.getToNodeId());
            sb.append("- ").append(fromName)
                    .append(" -[").append(edge.getEdgeType()).append("]-> ")
                    .append(toName).append("\n");
        }
        return sb.toString();
    }

    private String displayOf(GraphNode node, String fallbackId) {
        if (node == null) return fallbackId != null ? fallbackId : "?";
        return node.getDisplayName() != null && !node.getDisplayName().isBlank()
                ? node.getDisplayName() : node.getNodeName();
    }
}
