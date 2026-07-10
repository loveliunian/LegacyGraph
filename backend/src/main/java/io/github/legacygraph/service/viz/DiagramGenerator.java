package io.github.legacygraph.service.viz;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图表生成器（v6.0 P10：VISUALIZATION）— 从图谱数据生成 Mermaid 图表代码。
 * <p>支持 5 种图类型：
 * <ul>
 *   <li>时序图（sequenceDiagram）：从入口方法沿 CALLS 边生成方法调用时序</li>
 *   <li>依赖图（graph LR）：沿 DEPENDS_ON 边生成包/模块依赖</li>
 *   <li>调用链图（graph TD）：沿 CALLS 边正向/反向生成方法调用链</li>
 *   <li>数据流图（graph LR）：沿 READS/WRITES/DATA_FLOW 边生成数据流向</li>
 *   <li>业务链路图（graph LR）：沿 CONTAINS/IMPLEMENTS/REQUIRES_DOCUMENT 边生成业务流程</li>
 * </ul>
 * 通过 Neo4jGraphDao 查询边数据，BFS 遍历构建图谱子图，深度限制和节点数量限制避免图过大。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagramGenerator {

    private final Neo4jGraphDao neo4jGraphDao;

    /** 默认遍历深度限制 */
    private static final int DEFAULT_DEPTH_LIMIT = 5;
    /** 默认节点数量限制（避免 mermaid 代码过长） */
    private static final int DEFAULT_NODE_LIMIT = 30;

    /**
     * 生成时序图 — 从入口方法沿 CALLS 边遍历，生成 mermaid sequenceDiagram。
     * <p>参与者=类（从 Method 节点的 className 提取），消息=方法调用。</p>
     *
     * @param projectId      项目 ID
     * @param versionId      版本 ID
     * @param entryMethodId  入口方法节点 ID（nodeKey 或 nodeId）
     * @param depth          遍历深度限制
     * @return mermaid sequenceDiagram 代码
     */
    public String generateSequenceDiagram(String projectId, String versionId,
                                          String entryMethodId, int depth) {
        int maxDepth = depth > 0 ? depth : DEFAULT_DEPTH_LIMIT;
        GraphNode entryNode = resolveNode(projectId, versionId, entryMethodId, NodeType.Method.name());
        if (entryNode == null) {
            return mermaidFallback("sequenceDiagram", "未找到入口方法: " + entryMethodId);
        }

        // BFS 遍历 CALLS 边（正向），收集方法调用序列
        List<GraphNode> methods = new ArrayList<>();
        List<GraphEdge> calls = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        traverseBfs(projectId, versionId, entryNode, List.of(EdgeType.CALLS.name()),
                true, maxDepth, DEFAULT_NODE_LIMIT, methods, calls, visited, edgeKeys);

        if (methods.isEmpty()) {
            return mermaidFallback("sequenceDiagram", "入口方法无 CALLS 边: " + entryMethodId);
        }

        // 构建 mermaid sequenceDiagram
        StringBuilder sb = new StringBuilder();
        sb.append("```mermaid\n");
        sb.append("sequenceDiagram\n");

        // 参与者：从方法列表提取去重的类名
        Map<String, String> participants = new LinkedHashMap<>();
        for (GraphNode method : methods) {
            String className = extractClassName(method);
            if (className != null && !participants.containsKey(className)) {
                String alias = "P" + (participants.size() + 1);
                participants.put(className, alias);
                sb.append("    participant ").append(alias).append(" as ").append(simpleName(className)).append("\n");
            }
        }

        // 消息：遍历 CALLS 边，生成 from->>to: methodName
        for (GraphEdge edge : calls) {
            GraphNode fromNode = findInList(methods, edge.getFromNodeId());
            GraphNode toNode = findInList(methods, edge.getToNodeId());
            if (fromNode == null || toNode == null) {
                continue;
            }
            String fromClass = extractClassName(fromNode);
            String toClass = extractClassName(toNode);
            String fromAlias = participants.get(fromClass);
            String toAlias = participants.get(toClass);
            if (fromAlias == null || toAlias == null) {
                continue;
            }
            String message = toNode.getNodeName() + "()";
            sb.append("    ").append(fromAlias).append("->>").append(toAlias)
                    .append(": ").append(message).append("\n");
        }
        sb.append("```\n");
        return sb.toString();
    }

    /**
     * 生成依赖图 — 沿 DEPENDS_ON 边生成 mermaid graph LR。
     *
     * @param projectId 项目 ID
     * @param versionId 版本 ID
     * @param packageId 起始包节点 ID（nodeKey 或 nodeId）
     * @return mermaid graph LR 代码
     */
    public String generateDependencyGraph(String projectId, String versionId, String packageId) {
        return generateDirectedGraph(projectId, versionId, packageId, NodeType.Package.name(),
                List.of(EdgeType.DEPENDS_ON.name()), "依赖图", true, DEFAULT_DEPTH_LIMIT);
    }

    /**
     * 生成调用链图 — 沿 CALLS 边正向/反向生成 mermaid graph TD。
     *
     * @param projectId 项目 ID
     * @param versionId 版本 ID
     * @param methodId  起始方法节点 ID（nodeKey 或 nodeId）
     * @param direction 方向：forward 正向（下游调用）/ reverse 反向（上游调用方）
     * @return mermaid graph TD 代码
     */
    public String generateCallChain(String projectId, String versionId, String methodId, String direction) {
        boolean forward = !"reverse".equalsIgnoreCase(direction);
        return generateDirectedGraph(projectId, versionId, methodId, NodeType.Method.name(),
                List.of(EdgeType.CALLS.name()), "调用链图", forward, DEFAULT_DEPTH_LIMIT, "graph TD");
    }

    /**
     * 生成数据流图 — 沿 READS/WRITES/DATA_FLOW 边生成 mermaid graph LR。
     *
     * @param projectId 项目 ID
     * @param versionId 版本 ID
     * @param tableId   起始表节点 ID（nodeKey 或 nodeId）
     * @return mermaid graph LR 代码
     */
    public String generateDataFlowDiagram(String projectId, String versionId, String tableId) {
        GraphNode startNode = resolveNode(projectId, versionId, tableId, NodeType.Table.name());
        if (startNode == null) {
            return mermaidFallback("graph LR", "未找到数据表: " + tableId);
        }

        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        traverseBfs(projectId, versionId, startNode,
                List.of(EdgeType.READS.name(), EdgeType.WRITES.name(), EdgeType.DATA_FLOW.name()),
                true, DEFAULT_DEPTH_LIMIT, DEFAULT_NODE_LIMIT, nodes, edges, visited, edgeKeys);

        return buildMermaidGraph(nodes, edges, "graph LR", "数据流图");
    }

    /**
     * 生成业务链路图 — 沿 CONTAINS/IMPLEMENTS/REQUIRES_DOCUMENT 边生成 mermaid graph LR。
     *
     * @param projectId 项目 ID
     * @param versionId 版本 ID
     * @param processId 起始业务流程/业务域节点 ID（nodeKey 或 nodeId）
     * @return mermaid graph LR 代码
     */
    public String generateBusinessFlowDiagram(String projectId, String versionId, String processId) {
        GraphNode startNode = resolveNodeFlexible(projectId, versionId, processId,
                List.of(NodeType.BusinessDomain.name(), NodeType.BusinessProcess.name(),
                        NodeType.FeatureModule.name()));
        if (startNode == null) {
            return mermaidFallback("graph LR", "未找到业务流程/业务域: " + processId);
        }

        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        traverseBfs(projectId, versionId, startNode,
                List.of(EdgeType.CONTAINS.name(), EdgeType.IMPLEMENTS.name(),
                        EdgeType.IMPLEMENTED_BY.name(), EdgeType.REQUIRES_DOCUMENT.name()),
                true, DEFAULT_DEPTH_LIMIT, DEFAULT_NODE_LIMIT, nodes, edges, visited, edgeKeys);

        return buildMermaidGraph(nodes, edges, "graph LR", "业务链路图");
    }

    // ==================== 内部工具方法 ====================

    /**
     * 生成有向图（graph LR / graph TD）— 通用遍历 + mermaid 输出。
     */
    private String generateDirectedGraph(String projectId, String versionId, String startNodeId,
                                          String nodeType, List<String> edgeTypes,
                                          String title, boolean forward, int maxDepth) {
        return generateDirectedGraph(projectId, versionId, startNodeId, nodeType,
                edgeTypes, title, forward, maxDepth, "graph LR");
    }

    /**
     * 生成有向图（可指定方向 LR/TD）— 通用遍历 + mermaid 输出。
     */
    private String generateDirectedGraph(String projectId, String versionId, String startNodeId,
                                          String nodeType, List<String> edgeTypes,
                                          String title, boolean forward, int maxDepth, String graphDir) {
        GraphNode startNode = resolveNode(projectId, versionId, startNodeId, nodeType);
        if (startNode == null) {
            return mermaidFallback(graphDir, "未找到起始节点: " + startNodeId);
        }

        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        traverseBfs(projectId, versionId, startNode, edgeTypes,
                forward, maxDepth, DEFAULT_NODE_LIMIT, nodes, edges, visited, edgeKeys);

        return buildMermaidGraph(nodes, edges, graphDir, title);
    }

    /**
     * BFS 遍历图谱 — 从起点沿指定边类型遍历，收集节点和边。
     *
     * @param startNode  起始节点
     * @param edgeTypes  遍历的边类型集合
     * @param forward    true=正向（outgoing 边）/ false=反向（incoming 边）
     * @param maxDepth   最大深度
     * @param maxNodes    最大节点数
     * @param outNodes    收集的节点列表（输出）
     * @param outEdges    收集的边列表（输出）
     * @param visited     已访问节点 ID 集合（避免环路）
     * @param edgeKeys    已收集的边 key 集合（避免重复边）
     */
    private void traverseBfs(String projectId, String versionId, GraphNode startNode,
                            List<String> edgeTypes, boolean forward,
                            int maxDepth, int maxNodes,
                            List<GraphNode> outNodes, List<GraphEdge> outEdges,
                            Set<String> visited, Set<String> edgeKeys) {
        if (startNode == null) {
            return;
        }
        // 队列元素：[node, depth]
        List<Object[]> queue = new ArrayList<>();
        queue.add(new Object[]{startNode, 0});
        visited.add(startNode.getId());
        outNodes.add(startNode);

        while (!queue.isEmpty() && outNodes.size() < maxNodes) {
            Object[] current = queue.remove(0);
            GraphNode node = (GraphNode) current[0];
            int depth = (int) current[1];
            if (depth >= maxDepth) {
                continue;
            }

            // 查询连接到当前节点的边
            for (String edgeType : edgeTypes) {
                List<GraphEdge> connected = neo4jGraphDao.queryEdges(
                        projectId, versionId, edgeType, null, node.getId(),
                        null, null, 100);
                for (GraphEdge edge : connected) {
                    // 过滤重复边
                    if (edge.getEdgeKey() != null && edgeKeys.contains(edge.getEdgeKey())) {
                        continue;
                    }
                    // 确定目标节点
                    String targetNodeId;
                    if (forward) {
                        // 正向：当前节点是 from → 目标是 to
                        if (!node.getId().equals(edge.getFromNodeId())) {
                            continue;
                        }
                        targetNodeId = edge.getToNodeId();
                    } else {
                        // 反向：当前节点是 to → 目标是 from
                        if (!node.getId().equals(edge.getToNodeId())) {
                            continue;
                        }
                        targetNodeId = edge.getFromNodeId();
                    }
                    if (targetNodeId == null || visited.contains(targetNodeId)) {
                        // 仍收集边但跳过已访问节点
                        if (edge.getEdgeKey() != null) {
                            edgeKeys.add(edge.getEdgeKey());
                        }
                        outEdges.add(edge);
                        continue;
                    }
                    visited.add(targetNodeId);

                    // 加载目标节点
                    GraphNode targetNode = neo4jGraphDao.findNodeById(targetNodeId).orElse(null);
                    if (targetNode == null) {
                        continue;
                    }
                    outNodes.add(targetNode);
                    if (edge.getEdgeKey() != null) {
                        edgeKeys.add(edge.getEdgeKey());
                    }
                    outEdges.add(edge);
                    queue.add(new Object[]{targetNode, depth + 1});

                    if (outNodes.size() >= maxNodes) {
                        break;
                    }
                }
                if (outNodes.size() >= maxNodes) {
                    break;
                }
            }
        }
    }

    /**
     * 构建 mermaid graph 代码（通用方法）。
     */
    private String buildMermaidGraph(List<GraphNode> nodes, List<GraphEdge> edges,
                                     String graphDir, String title) {
        if (nodes.isEmpty()) {
            return mermaidFallback(graphDir, title + "：无图谱数据");
        }
        // nodeId → mermaid 别名（N1, N2...）
        Map<String, String> aliases = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        sb.append("```mermaid\n");
        sb.append(graphDir).append("\n");

        for (int i = 0; i < nodes.size(); i++) {
            GraphNode node = nodes.get(i);
            String alias = "N" + (i + 1);
            aliases.put(node.getId(), alias);
            String label = sanitizeMermaidLabel(node.getNodeName() != null
                    ? node.getNodeName() : node.getNodeKey());
            sb.append("    ").append(alias).append("[\"").append(label).append("\"]\n");
        }

        for (GraphEdge edge : edges) {
            String fromAlias = aliases.get(edge.getFromNodeId());
            String toAlias = aliases.get(edge.getToNodeId());
            if (fromAlias == null || toAlias == null) {
                continue;
            }
            String label = sanitizeMermaidLabel(edge.getEdgeType());
            sb.append("    ").append(fromAlias).append(" -->|").append(label)
                    .append("| ").append(toAlias).append("\n");
        }
        sb.append("```\n");
        return sb.toString();
    }

    /**
     * 解析节点 — 先按 nodeKey 查找，再按 nodeId 查找。
     */
    private GraphNode resolveNode(String projectId, String versionId, String nodeKeyOrId, String nodeType) {
        if (nodeKeyOrId == null || nodeKeyOrId.isBlank()) {
            return null;
        }
        // 先按 nodeKey 查找
        GraphNode node = neo4jGraphDao.findNode(projectId, versionId, nodeType, nodeKeyOrId).orElse(null);
        if (node != null) {
            return node;
        }
        // 回退：按 nodeId 查找
        return neo4jGraphDao.findNodeById(nodeKeyOrId).orElse(null);
    }

    /**
     * 解析节点 — 在多个候选 nodeType 中查找（业务链路图场景）。
     */
    private GraphNode resolveNodeFlexible(String projectId, String versionId,
                                          String nodeKeyOrId, List<String> nodeTypes) {
        for (String nodeType : nodeTypes) {
            GraphNode node = resolveNode(projectId, versionId, nodeKeyOrId, nodeType);
            if (node != null) {
                return node;
            }
        }
        // 回退：按 nodeId 查找（不限类型）
        return neo4jGraphDao.findNodeById(nodeKeyOrId).orElse(null);
    }

    /**
     * 从 Method 节点提取类名（className 属性优先，回退到 nodeKey 的类名部分）。
     */
    private String extractClassName(GraphNode methodNode) {
        if (methodNode == null) {
            return null;
        }
        if (methodNode.getClassName() != null && !methodNode.getClassName().isBlank()) {
            return methodNode.getClassName();
        }
        // 回退：从 nodeKey 提取（格式：qualifiedClassName.methodSignature）
        String nodeKey = methodNode.getNodeKey();
        if (nodeKey != null) {
            int lastDot = nodeKey.lastIndexOf('.');
            if (lastDot > 0) {
                return nodeKey.substring(0, lastDot);
            }
        }
        return methodNode.getNodeName();
    }

    /**
     * 在节点列表中按 ID 查找节点。
     */
    private GraphNode findInList(List<GraphNode> nodes, String nodeId) {
        for (GraphNode node : nodes) {
            if (nodeId.equals(node.getId())) {
                return node;
            }
        }
        return null;
    }

    /**
     * 获取类的简单名（去掉包路径）。
     */
    private String simpleName(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return "";
        }
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
    }

    /**
     * 清理 mermaid 标签中的特殊字符（引号、换行等）。
     */
    private String sanitizeMermaidLabel(String label) {
        if (label == null) {
            return "";
        }
        return label.replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }

    /**
     * 生成 mermaid 兜底输出（数据不足时）。
     */
    private String mermaidFallback(String graphType, String message) {
        return "```mermaid\n" + graphType + "\n    %% " + message + "\n```\n";
    }
}
