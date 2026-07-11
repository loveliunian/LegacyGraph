package io.github.legacygraph.service.scan;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.llm.LlmGateway;
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
    private final LlmGateway llmGateway;

    public CommunityDetectionService(Neo4jGraphDao graphDao, LlmGateway llmGateway) {
        this.graphDao = graphDao;
        this.llmGateway = llmGateway;
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
     * 生成社区摘要 — 调用 LLM 为每个社区生成一句话职责描述，写入节点 properties.communitySummary。
     *
     * <p>流程：
     * <ol>
     *   <li>调用 {@link #detectCommunities} 获取社区映射（nodeKey → communityLabel）</li>
     *   <li>反转为 communityLabel → List&lt;GraphNode&gt;</li>
     *   <li>对每个社区收集 nodeName + nodeType，按 nodeType 分组统计，调用 LLM 生成摘要</li>
     *   <li>调用 {@link #writeCommunitySummaryToNodes} 写入节点 properties.communitySummary</li>
     * </ol>
     * LLM 调用失败时使用 fallback：基于节点统计生成简单摘要。</p>
     *
     * @param projectId 项目 ID
     * @return communityLabel → 摘要 映射
     */
    public Map<String, String> generateCommunitySummaries(String projectId) {
        Map<String, String> communityMap = detectCommunities(projectId);
        if (communityMap == null || communityMap.isEmpty()) {
            log.info("CommunityDetectionService: no communities to summarize (projectId={})", projectId);
            return Collections.emptyMap();
        }

        // 反转映射：communityLabel → List<GraphNode>
        List<GraphNode> packages = graphDao.queryNodes(projectId, null,
                NodeType.Package.name(), null, null, null, QUERY_LIMIT);
        Map<String, List<GraphNode>> communityNodes = new LinkedHashMap<>();
        if (packages != null) {
            for (GraphNode pkg : packages) {
                String label = communityMap.get(pkg.getNodeKey());
                if (label == null && pkg.getNodeName() != null) {
                    label = communityMap.get(pkg.getNodeName());
                }
                if (label != null) {
                    communityNodes.computeIfAbsent(label, k -> new ArrayList<>()).add(pkg);
                }
            }
        }

        // 为每个社区生成摘要
        Map<String, String> summaryMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<GraphNode>> entry : communityNodes.entrySet()) {
            String label = entry.getKey();
            List<GraphNode> nodes = entry.getValue();
            String summary = generateSummaryForCommunity(projectId, label, nodes);
            summaryMap.put(label, summary);
        }

        // 转换为 nodeKey → summary 并写入节点
        Map<String, String> nodeSummaryMap = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : communityMap.entrySet()) {
            String nodeKey = entry.getKey();
            String label = entry.getValue();
            String summary = summaryMap.get(label);
            if (summary != null) {
                nodeSummaryMap.put(nodeKey, summary);
            }
        }
        writeCommunitySummaryToNodes(projectId, nodeSummaryMap);

        log.info("CommunityDetectionService: generated {} community summaries (projectId={})",
                summaryMap.size(), projectId);
        return summaryMap;
    }

    /**
     * 将社区摘要写入 Package 节点的 properties.communitySummary 字段。
     *
     * @param projectId  项目 ID
     * @param summaryMap nodeKey → 摘要 映射
     */
    public void writeCommunitySummaryToNodes(String projectId, Map<String, String> summaryMap) {
        if (summaryMap == null || summaryMap.isEmpty()) {
            return;
        }
        List<GraphNode> packages = graphDao.queryNodes(projectId, null,
                NodeType.Package.name(), null, null, null, QUERY_LIMIT);
        if (packages == null || packages.isEmpty()) {
            return;
        }
        int updated = 0;
        for (GraphNode pkg : packages) {
            String summary = summaryMap.get(pkg.getNodeKey());
            if (summary == null && pkg.getNodeName() != null) {
                summary = summaryMap.get(pkg.getNodeName());
            }
            if (summary != null && pkg.getId() != null) {
                try {
                    graphDao.setNodeProperty(pkg.getId(), "communitySummary", summary);
                    updated++;
                } catch (Exception e) {
                    log.warn("CommunityDetectionService: failed to set communitySummary for Package {}: {}",
                            pkg.getNodeKey(), e.getMessage());
                }
            }
        }
        log.info("CommunityDetectionService: wrote community summaries to {} Package nodes (projectId={})",
                updated, projectId);
    }

    /**
     * 为单个社区生成摘要 — 调用 LLM，失败时回退到基于节点统计的简单摘要。
     */
    private String generateSummaryForCommunity(String projectId, String communityLabel, List<GraphNode> nodes) {
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        for (GraphNode node : nodes) {
            String type = node.getNodeType();
            if (type != null) {
                typeCounts.merge(type, 1, Integer::sum);
            }
        }
        String fallbackSummary = buildFallbackSummary(typeCounts);

        try {
            String systemPrompt = "你是一个代码分析助手。根据社区内的节点信息，用一句话概括这个代码社区/子系统的职责。";
            String userPrompt = buildCommunityPrompt(communityLabel, nodes, typeCounts);
            String result = llmGateway.call(projectId, systemPrompt, userPrompt, String.class);
            if (result != null && !result.isBlank()) {
                return result.trim();
            }
        } catch (Exception e) {
            log.warn("CommunityDetectionService: LLM summary failed for community {}, using fallback: {}",
                    communityLabel, e.getMessage());
        }
        return fallbackSummary;
    }

    /**
     * 构建基于节点统计的 fallback 摘要（如"社区包含 3 个 Controller、5 个 Service、2 个 Mapper"）。
     */
    private String buildFallbackSummary(Map<String, Integer> typeCounts) {
        if (typeCounts.isEmpty()) {
            return "空社区";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : typeCounts.entrySet()) {
            parts.add(entry.getValue() + " 个 " + entry.getKey());
        }
        return "社区包含 " + String.join("、", parts);
    }

    /**
     * 组装社区摘要的 LLM prompt 输入。
     */
    private String buildCommunityPrompt(String communityLabel, List<GraphNode> nodes, Map<String, Integer> typeCounts) {
        StringBuilder sb = new StringBuilder();
        sb.append("社区标签：").append(communityLabel).append("\n\n");
        sb.append("社区内节点：\n");
        for (GraphNode node : nodes) {
            sb.append("- ").append(node.getNodeName());
            if (node.getNodeType() != null) {
                sb.append(" (").append(node.getNodeType()).append(")");
            }
            sb.append("\n");
        }
        sb.append("\n节点类型统计：");
        List<String> stats = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : typeCounts.entrySet()) {
            stats.add(entry.getKey() + ":" + entry.getValue());
        }
        sb.append(String.join(", ", stats));
        sb.append("\n\n请用一句话概括这个代码社区/子系统的职责。");
        return sb.toString();
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
