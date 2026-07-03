package io.github.legacygraph.service.change;

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
