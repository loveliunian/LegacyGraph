package io.github.legacygraph.verification;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.extractors.adapter.ScanContext;
import io.github.legacygraph.understanding.tool.adapter.McpClientFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 MCP (codebase-memory) 的外部验证适配器。
 * <p>
 * 通过 MCP Server 对照校验本地抽取的图谱，提供三类验证：
 * <ul>
 *   <li>CALLS 边验证：用 {@code queryGraph} 逐条验证，确认或标记可疑</li>
 *   <li>缺失 CALLS 边补全：用 {@code searchGraph} 搜索 Service 类，补全本地遗漏</li>
 *   <li>未解析继承边补全：对 EXTENDS/IMPLEMENTS 中 toNode 未解析的，用 {@code searchGraph} 查找补全</li>
 * </ul>
 * 所有 MCP 调用均做异常降级；MCP 不可达时 {@link #verify} 返回空结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpVerificationAdapter implements ExternalVerificationAdapter {

    private static final String ADAPTER_NAME = "mcp-verification";
    private static final String SOURCE_TOOL = "mcp";

    /** 缺失边补全的置信度 */
    private static final double MISSING_EDGE_CONFIDENCE = 0.85;
    /** MCP 确认边的置信度 */
    private static final double CONFIRMED_EDGE_CONFIDENCE = 0.9;
    /** 本地有但 MCP 未发现（可疑）边的置信度 */
    private static final double SUSPICIOUS_EDGE_CONFIDENCE = 0.5;

    private final McpClientFacade mcpClientFacade;
    private final Neo4jGraphDao neo4jGraphDao;

    @Override
    public String adapterName() {
        return ADAPTER_NAME;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean supports(ScanContext context) {
        // MCP 验证不依赖特定语言/框架
        return true;
    }

    @Override
    public boolean checkHealth() {
        try {
            mcpClientFacade.indexStatus("default");
            return true;
        } catch (Exception e) {
            log.warn("MCP health check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public VerificationResult verify(String projectId, String versionId, ScanContext context) {
        // MCP 不可达时直接返回空结果
        if (!checkHealth()) {
            log.warn("MCP client unavailable, skip verification for project={}, version={}",
                    projectId, versionId);
            return VerificationResult.empty(ADAPTER_NAME);
        }

        List<VerifiedEdge> confirmedEdges = new ArrayList<>();
        List<VerifiedEdge> missingEdges = new ArrayList<>();
        List<VerifiedEdge> suspiciousEdges = new ArrayList<>();

        int totalChecked = 0;
        int totalConfirmed = 0;

        try {
            // 1. CALLS 边验证
            CallsVerification callsOutcome = verifyCallsEdges(projectId, versionId);
            confirmedEdges.addAll(callsOutcome.confirmed);
            suspiciousEdges.addAll(callsOutcome.suspicious);
            totalChecked += callsOutcome.checked;
            totalConfirmed += callsOutcome.confirmed.size();

            // 2. 缺失 CALLS 边补全
            List<VerifiedEdge> missingCalls = discoverMissingCallsEdges(projectId, versionId);
            missingEdges.addAll(missingCalls);

            // 3. 继承边补全（EXTENDS / IMPLEMENTS）
            List<VerifiedEdge> missingInherited = discoverMissingInheritanceEdges(projectId, versionId);
            missingEdges.addAll(missingInherited);

        } catch (Exception e) {
            log.error("MCP verification failed for project={}, version={}: {}",
                    projectId, versionId, e.getMessage(), e);
            return VerificationResult.empty(ADAPTER_NAME);
        }

        return VerificationResult.builder()
                .adapterName(ADAPTER_NAME)
                .confirmedEdges(confirmedEdges)
                .missingEdges(missingEdges)
                .suspiciousEdges(suspiciousEdges)
                .totalChecked(totalChecked)
                .totalConfirmed(totalConfirmed)
                .build();
    }

    // ==================== 1. CALLS 边验证 ====================

    /**
     * 查询本地 CALLS 边，逐条用 MCP queryGraph 验证。
     * MCP 确认存在 → confirmedEdges；未发现 → suspiciousEdges。
     */
    private CallsVerification verifyCallsEdges(String projectId, String versionId) {
        CallsVerification outcome = new CallsVerification();

        List<GraphEdge> callsEdges = neo4jGraphDao.queryEdges(projectId, versionId, "CALLS", null, 500);
        if (callsEdges.isEmpty()) {
            return outcome;
        }

        Map<String, GraphNode> nodeMap = loadNodes(callsEdges);

        for (GraphEdge edge : callsEdges) {
            GraphNode fromNode = nodeMap.get(edge.getFromNodeId());
            GraphNode toNode = nodeMap.get(edge.getToNodeId());
            String fromKey = fromNode != null ? fromNode.getNodeKey() : null;
            String toKey = toNode != null ? toNode.getNodeKey() : null;

            if (fromKey == null || toKey == null) {
                log.debug("Skip CALLS edge with null node key: from={}, to={}", fromKey, toKey);
                continue;
            }

            outcome.checked++;

            if (mcpConfirmsCallsEdge(fromKey, toKey)) {
                outcome.confirmed.add(VerifiedEdge.builder()
                        .fromNodeKey(fromKey)
                        .toNodeKey(toKey)
                        .edgeType("CALLS")
                        .confidence(CONFIRMED_EDGE_CONFIDENCE)
                        .sourceTool(SOURCE_TOOL)
                        .build());
            } else {
                outcome.suspicious.add(VerifiedEdge.builder()
                        .fromNodeKey(fromKey)
                        .toNodeKey(toKey)
                        .edgeType("CALLS")
                        .confidence(SUSPICIOUS_EDGE_CONFIDENCE)
                        .sourceTool(SOURCE_TOOL)
                        .build());
            }
        }
        return outcome;
    }

    /**
     * 用 Cypher 查询 MCP 图谱，验证 fromKey -> toKey 的 CALLS 关系是否存在。
     */
    private boolean mcpConfirmsCallsEdge(String fromKey, String toKey) {
        try {
            String cypher = String.format(
                    "MATCH (a)-[r:CALLS]->(b) " +
                    "WHERE a.nodeKey = '%s' AND b.nodeKey = '%s' " +
                    "RETURN count(r) AS cnt",
                    escapeCypher(fromKey), escapeCypher(toKey));
            Map<String, Object> result = mcpClientFacade.queryGraph(cypher);
            List<?> results = extractList(result, "results");
            if (results == null || results.isEmpty()) {
                return false;
            }
            Object first = results.get(0);
            if (first instanceof Map<?, ?>) {
                Object cnt = ((Map<?, ?>) first).get("cnt");
                if (cnt instanceof Number) {
                    return ((Number) cnt).longValue() > 0;
                }
            }
            // 无 cnt 字段但返回了结果，视为存在
            return true;
        } catch (Exception e) {
            log.warn("MCP queryGraph failed for CALLS edge {} -> {}: {}", fromKey, toKey, e.getMessage());
            return false;
        }
    }

    // ==================== 2. 缺失 CALLS 边补全 ====================

    /**
     * 用 MCP queryGraph 查询外部工具已知的 CALLS 关系，
     * 对比本地 CALLS 边集合，找出本地缺失的调用关系。
     */
    private List<VerifiedEdge> discoverMissingCallsEdges(String projectId, String versionId) {
        List<VerifiedEdge> missing = new ArrayList<>();

        // 1. 收集本地已有 CALLS 边的 (fromKey -> toKey) 集合
        List<GraphEdge> localCalls;
        try {
            localCalls = neo4jGraphDao.queryEdges(projectId, versionId, "CALLS", null, 1000);
        } catch (Exception e) {
            log.warn("查询本地 CALLS 边失败: {}", e.getMessage());
            return missing;
        }
        Set<String> localEdgeKeys = new HashSet<>();
        Map<String, GraphNode> nodeMap = loadNodes(localCalls);
        for (GraphEdge edge : localCalls) {
            GraphNode fromNode = nodeMap.get(edge.getFromNodeId());
            GraphNode toNode = nodeMap.get(edge.getToNodeId());
            if (fromNode != null && toNode != null
                    && fromNode.getNodeKey() != null && toNode.getNodeKey() != null) {
                localEdgeKeys.add(fromNode.getNodeKey() + "->" + toNode.getNodeKey());
            }
        }

        // 2. 用 MCP queryGraph 查询外部工具已知的 CALLS 关系
        String cypher = "MATCH (a)-[:CALLS]->(b) "
                + "WHERE a.nodeKey IS NOT NULL AND b.nodeKey IS NOT NULL "
                + "RETURN a.nodeKey AS fromKey, b.nodeKey AS toKey LIMIT 500";
        Map<String, Object> mcpResult;
        try {
            mcpResult = mcpClientFacade.queryGraph(cypher);
        } catch (Exception e) {
            log.warn("MCP queryGraph 查询 CALLS 关系失败: {}", e.getMessage());
            return missing;
        }

        List<?> results = extractList(mcpResult, "results");
        if (results == null) {
            return missing;
        }

        // 3. 对比找出本地缺失的 CALLS 边
        for (Object item : results) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> row = (Map<?, ?>) item;
            String fromKey = stringOrEmpty(row.get("fromKey"));
            String toKey = stringOrEmpty(row.get("toKey"));
            if (fromKey.isEmpty() || toKey.isEmpty()) {
                continue;
            }
            String edgeKey = fromKey + "->" + toKey;
            if (!localEdgeKeys.contains(edgeKey)) {
                // 本地缺失此 CALLS 边 → 记录为 missing
                missing.add(VerifiedEdge.builder()
                        .fromNodeKey(fromKey)
                        .toNodeKey(toKey)
                        .edgeType("CALLS")
                        .confidence(MISSING_EDGE_CONFIDENCE)
                        .sourceTool(SOURCE_TOOL)
                        .build());
            }
        }
        return missing;
    }

    // ==================== 3. 继承边补全 ====================

    /**
     * 查询本地 EXTENDS / IMPLEMENTS 边，对 toNode 未解析的（toNodeId 为空或
     * toNode 的 nodeKey/nodeName 未确定），用 searchGraph 查找目标符号补全。
     */
    private List<VerifiedEdge> discoverMissingInheritanceEdges(String projectId, String versionId) {
        List<VerifiedEdge> missing = new ArrayList<>();

        for (String edgeType : new String[]{"EXTENDS", "IMPLEMENTS"}) {
            List<GraphEdge> edges;
            try {
                edges = neo4jGraphDao.queryEdges(projectId, versionId, edgeType, null, 500);
            } catch (Exception e) {
                log.warn("Query {} edges failed: {}", edgeType, e.getMessage());
                continue;
            }
            if (edges.isEmpty()) {
                continue;
            }

            Map<String, GraphNode> nodeMap = loadNodes(edges);

            for (GraphEdge edge : edges) {
                GraphNode fromNode = nodeMap.get(edge.getFromNodeId());
                GraphNode toNode = nodeMap.get(edge.getToNodeId());

                // 判断 toNode 是否未解析
                boolean unresolved = edge.getToNodeId() == null || edge.getToNodeId().isEmpty()
                        || toNode == null
                        || (toNode.getNodeKey() == null && toNode.getNodeName() == null);
                if (!unresolved || fromNode == null) {
                    continue;
                }

                String query = fromNode.getNodeName() != null ? fromNode.getNodeName() : fromNode.getNodeKey();
                if (query == null || query.isEmpty()) {
                    continue;
                }

                String resolvedToKey = searchInheritanceTarget(query, edgeType);
                if (resolvedToKey != null) {
                    String fromKey = fromNode.getNodeKey() != null
                            ? fromNode.getNodeKey() : fromNode.getNodeName();
                    missing.add(VerifiedEdge.builder()
                            .fromNodeKey(fromKey)
                            .toNodeKey(resolvedToKey)
                            .edgeType(edgeType)
                            .confidence(MISSING_EDGE_CONFIDENCE)
                            .sourceTool(SOURCE_TOOL)
                            .build());
                }
            }
        }
        return missing;
    }

    /**
     * 通过 searchGraph 查找继承目标符号。
     * 简化策略：返回第一个非空 symbol_qn。
     */
    private String searchInheritanceTarget(String query, String edgeType) {
        try {
            Map<String, Object> result = mcpClientFacade.searchGraph(query);
            List<?> results = extractList(result, "results");
            if (results == null || results.isEmpty()) {
                return null;
            }
            for (Object item : results) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Object symbolQn = ((Map<?, ?>) item).get("symbol_qn");
                if (symbolQn != null) {
                    String qn = String.valueOf(symbolQn);
                    if (!"null".equals(qn) && !qn.isEmpty()) {
                        return qn;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("MCP searchGraph failed for inheritance target (query={}, type={}): {}",
                    query, edgeType, e.getMessage());
        }
        return null;
    }

    // ==================== 工具方法 ====================

    /**
     * 批量加载边涉及的节点，构建 nodeId -> GraphNode 映射。
     */
    private Map<String, GraphNode> loadNodes(List<GraphEdge> edges) {
        Set<String> nodeIds = new HashSet<>();
        for (GraphEdge edge : edges) {
            if (edge.getFromNodeId() != null && !edge.getFromNodeId().isEmpty()) {
                nodeIds.add(edge.getFromNodeId());
            }
            if (edge.getToNodeId() != null && !edge.getToNodeId().isEmpty()) {
                nodeIds.add(edge.getToNodeId());
            }
        }
        if (nodeIds.isEmpty()) {
            return new HashMap<>();
        }
        List<GraphNode> nodes = neo4jGraphDao.findNodesByIds(new ArrayList<>(nodeIds));
        Map<String, GraphNode> map = new HashMap<>();
        for (GraphNode node : nodes) {
            map.put(node.getId(), node);
        }
        return map;
    }

    /**
     * 从 Map 中安全提取 List 字段。
     */
    @SuppressWarnings("unchecked")
    private List<?> extractList(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        if (value instanceof List<?>) {
            return (List<Object>) value;
        }
        return null;
    }

    /**
     * 转义 Cypher 字符串字面量中的特殊字符。
     */
    private String escapeCypher(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * 将对象安全转为非空字符串，null 返回空串。
     */
    private String stringOrEmpty(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        return "null".equals(s) ? "" : s;
    }

    // ==================== 内部结果容器 ====================

    /** CALLS 边验证中间结果 */
    private static class CallsVerification {
        final List<VerifiedEdge> confirmed = new ArrayList<>();
        final List<VerifiedEdge> suspicious = new ArrayList<>();
        int checked = 0;
    }
}
