package io.github.legacygraph.service.scan;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 社区检测服务 — 扫描完成后对图谱执行标签传播算法，发现代码模块/子系统。
 *
 * <p>由于 Neo4j GDS（Graph Data Science）库可能未安装，这里实现简化版的
 * 标签传播算法（Label Propagation Algorithm）作为 Leiden 的替代：</p>
 * <ul>
 *   <li>初始化每个节点的 community 标签为其自身名称</li>
 *   <li>迭代：每个节点采用其邻居中出现频率最高的 community 标签（平局随机选一个）</li>
 *   <li>收敛条件：迭代不再改变标签分配，或达到最大迭代次数（默认 10）</li>
 * </ul>
 *
 * <p>支持两个粒度：</p>
 * <ul>
 *   <li>{@link #detectCommunities(String)} — Package 级别，基于 DEPENDS_ON 边</li>
 *   <li>{@link #detectCommunitiesByClasses(String)} — Class 级别（Controller/Service/Mapper），
 *       基于 CALLS/EXTENDS/IMPLEMENTS 边</li>
 * </ul>
 */
@Slf4j
@Service
public class CommunityDetectionService {

    /** 标签传播最大迭代次数 */
    private static final int MAX_ITERATIONS = 10;

    /** 单次查询的节点/边上限 */
    private static final int QUERY_LIMIT = 10000;

    /** 视为「类节点」的 nodeType 集合（无独立 Class 类型，由 Controller/Service/Mapper 承担） */
    private static final List<String> CLASS_NODE_TYPES = List.of(
            NodeType.Controller.name(),
            NodeType.Service.name(),
            NodeType.Mapper.name());

    /** 类间依赖边类型 */
    private static final List<String> CLASS_EDGE_TYPES = List.of(
            EdgeType.CALLS.name(),
            EdgeType.EXTENDS.name(),
            EdgeType.IMPLEMENTS.name());

    private final Neo4jGraphDao graphDao;

    public CommunityDetectionService(Neo4jGraphDao graphDao) {
        this.graphDao = graphDao;
    }

    /**
     * 基于 Package 节点和 DEPENDS_ON 边的社区检测（标签传播算法）。
     *
     * @param projectId 项目 ID
     * @return packageName → communityLabel 映射
     */
    public Map<String, String> detectCommunities(String projectId) {
        List<GraphNode> packages = graphDao.queryNodes(projectId, null,
                NodeType.Package.name(), null, null, null, QUERY_LIMIT);
        if (packages == null || packages.isEmpty()) {
            log.debug("CommunityDetectionService: no Package nodes for projectId={}", projectId);
            return Collections.emptyMap();
        }
        List<GraphEdge> edges = graphDao.queryEdges(projectId, null,
                EdgeType.DEPENDS_ON.name(), null, QUERY_LIMIT);
        Map<String, String> result = runLabelPropagation(packages, edges);
        log.info("CommunityDetectionService: detected {} communities from {} packages (projectId={})",
                countCommunities(result), packages.size(), projectId);
        return result;
    }

    /**
     * 基于类节点（Controller/Service/Mapper）和 CALLS/EXTENDS/IMPLEMENTS 边的社区检测。
     *
     * @param projectId 项目 ID
     * @return className → communityLabel 映射
     */
    public Map<String, String> detectCommunitiesByClasses(String projectId) {
        List<GraphNode> classNodes = new ArrayList<>();
        for (String nodeType : CLASS_NODE_TYPES) {
            List<GraphNode> nodes = graphDao.queryNodes(projectId, null,
                    nodeType, null, null, null, QUERY_LIMIT);
            if (nodes != null) {
                classNodes.addAll(nodes);
            }
        }
        if (classNodes.isEmpty()) {
            log.debug("CommunityDetectionService: no class nodes for projectId={}", projectId);
            return Collections.emptyMap();
        }
        List<GraphEdge> edges = new ArrayList<>();
        for (String edgeType : CLASS_EDGE_TYPES) {
            List<GraphEdge> typeEdges = graphDao.queryEdges(projectId, null,
                    edgeType, null, QUERY_LIMIT);
            if (typeEdges != null) {
                edges.addAll(typeEdges);
            }
        }
        Map<String, String> result = runLabelPropagation(classNodes, edges);
        log.info("CommunityDetectionService: detected {} communities from {} class nodes (projectId={})",
                countCommunities(result), classNodes.size(), projectId);
        return result;
    }

    /**
     * 将社区检测结果写入 Package 节点的 properties.community 字段。
     *
     * @param projectId   项目 ID
     * @param communityMap packageName → communityLabel 映射
     */
    public void writeCommunityToNodes(String projectId, Map<String, String> communityMap) {
        if (communityMap == null || communityMap.isEmpty()) {
            return;
        }
        List<GraphNode> packages = graphDao.queryNodes(projectId, null,
                NodeType.Package.name(), null, null, null, QUERY_LIMIT);
        if (packages == null || packages.isEmpty()) {
            return;
        }
        int updated = 0;
        for (GraphNode pkg : packages) {
            String label = communityMap.get(pkg.getNodeKey());
            if (label == null && pkg.getNodeName() != null) {
                label = communityMap.get(pkg.getNodeName());
            }
            if (label != null && pkg.getId() != null) {
                try {
                    graphDao.setNodeProperty(pkg.getId(), "community", label);
                    updated++;
                } catch (Exception e) {
                    log.warn("CommunityDetectionService: failed to set community for Package {}: {}",
                            pkg.getNodeKey(), e.getMessage());
                }
            }
        }
        log.info("CommunityDetectionService: wrote community labels to {} Package nodes (projectId={})",
                updated, projectId);
    }

    /**
     * 标签传播算法核心实现。
     * <p>
     * 初始化每个节点的标签为其自身 nodeKey，然后迭代更新：
     * 每个节点采用其邻居中出现频率最高的标签（平局随机选一个）。
     * 收敛条件：迭代不再改变标签分配，或达到最大迭代次数。
     * </p>
     *
     * @param nodes 参与社区检测的节点列表
     * @param edges 节点间的关系边（将视为无向边构建邻接表）
     * @return nodeKey → communityLabel 映射
     */
    Map<String, String> runLabelPropagation(List<GraphNode> nodes, List<GraphEdge> edges) {
        // 构建 nodeId ↔ nodeKey 映射
        Map<String, String> nodeIdToKey = new HashMap<>();
        for (GraphNode node : nodes) {
            String key = (node.getNodeKey() != null && !node.getNodeKey().isBlank())
                    ? node.getNodeKey() : node.getId();
            nodeIdToKey.put(node.getId(), key);
        }
        Set<String> nodeIds = nodeIdToKey.keySet();

        // 构建无向邻接表（仅保留在节点集合内的边）
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (String id : nodeIds) {
            adjacency.put(id, new HashSet<>());
        }
        if (edges != null) {
            for (GraphEdge edge : edges) {
                String from = edge.getFromNodeId();
                String to = edge.getToNodeId();
                if (from != null && to != null && nodeIds.contains(from) && nodeIds.contains(to)
                        && !from.equals(to)) {
                    adjacency.get(from).add(to);
                    adjacency.get(to).add(from);
                }
            }
        }

        // 初始化标签：每个节点的标签为其自身 nodeKey
        Map<String, String> labels = new HashMap<>();
        for (String id : nodeIds) {
            labels.put(id, nodeIdToKey.get(id));
        }

        // 迭代标签传播
        Random random = new Random();
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            boolean changed = false;
            List<String> shuffledIds = new ArrayList<>(nodeIds);
            Collections.shuffle(shuffledIds, random);
            for (String id : shuffledIds) {
                Set<String> neighbors = adjacency.get(id);
                if (neighbors == null || neighbors.isEmpty()) {
                    continue;
                }
                // 统计邻居标签频率
                Map<String, Integer> labelCounts = new HashMap<>();
                for (String neighborId : neighbors) {
                    String neighborLabel = labels.get(neighborId);
                    if (neighborLabel != null) {
                        labelCounts.merge(neighborLabel, 1, Integer::sum);
                    }
                }
                // 选择频率最高的标签（平局随机选一个）
                String bestLabel = selectMaxLabel(labelCounts, random);
                if (bestLabel != null && !bestLabel.equals(labels.get(id))) {
                    labels.put(id, bestLabel);
                    changed = true;
                }
            }
            if (!changed) {
                log.debug("Label propagation converged at iteration {}", iter + 1);
                break;
            }
        }

        // 转换为 nodeKey → label
        Map<String, String> result = new LinkedHashMap<>();
        for (String id : nodeIds) {
            result.put(nodeIdToKey.get(id), labels.get(id));
        }
        return result;
    }

    /**
     * 选择频率最高的标签，平局时随机选一个。
     */
    private String selectMaxLabel(Map<String, Integer> labelCounts, Random random) {
        if (labelCounts.isEmpty()) {
            return null;
        }
        int maxCount = 0;
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : labelCounts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                candidates.clear();
                candidates.add(entry.getKey());
            } else if (entry.getValue() == maxCount) {
                candidates.add(entry.getKey());
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * 统计社区数量（不同 label 的个数）。
     */
    private int countCommunities(Map<String, String> communityMap) {
        if (communityMap == null || communityMap.isEmpty()) {
            return 0;
        }
        return new HashSet<>(communityMap.values()).size();
    }
}
