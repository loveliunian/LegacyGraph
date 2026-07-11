package io.github.legacygraph.service.graph;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    /** L-16: API 调用链 BFS 最大深度，默认 12（可配 legacygraph.graph.api-chain.max-depth） */
    @Value("${legacygraph.graph.api-chain.max-depth:12}")
    private int apiChainMaxDepth;

    /** L-16: API 调用链 BFS 每跳最大节点数，防止爆炸式扩展 */
    private static final int API_CHAIN_PER_DEPTH_LIMIT = 500;

    public GraphPathReadModel(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * API 调用链 — L-16: 使用 BFS 邻居展开替代全表 queryEdges(..., 200)。
     *
     * @param projectId  项目 ID
     * @param versionId  扫描版本 ID
     * @param apiKey     API 节点的 nodeKey 或 nodeName
     * @param maxDepth   调用方显式传入的深度限制（null 时使用配置默认值，内部 clamp ≤ 12）
     */
    public PathChain getApiCallChain(String projectId, String versionId, String apiKey, Integer maxDepth) {
        // Neo4j 存储 versionId 无连字符，需规范化
        String normalizedVersionId = IdUtil.normalizeId(versionId);

        // L-16: 深度可配，caller 显式传参时 clamp ≤ 12
        int effectiveDepth = maxDepth != null
                ? Math.max(1, Math.min(maxDepth, 12))
                : Math.max(1, Math.min(apiChainMaxDepth, 12));

        PathChain chain = new PathChain();
        chain.nodes = new ArrayList<>();
        chain.edges = new ArrayList<>();

        Optional<GraphNode> apiNode = neo4jGraphDao.findNode(projectId, normalizedVersionId, "ApiEndpoint", apiKey);
        if (apiNode.isEmpty()) return chain;

        chain.startNodeId = apiNode.get().getId();
        chain.nodes.add(toInfo(apiNode.get()));

        // L-16: BFS 邻居展开 — 每跳只查询 frontier 的出边，不拉全表
        Set<String> visited = new HashSet<>();
        visited.add(apiNode.get().getId());

        for (int depth = 0; depth < effectiveDepth; depth++) {
            // 查询当前 frontier 的所有出边
            List<Map<String, Object>> outgoingEdges = neo4jGraphDao.queryOutgoingEdges(
                    projectId, normalizedVersionId, visited);

            if (outgoingEdges.isEmpty()) {
                break; // 没有更多出边，提前终止
            }

            List<String> nextIds = new ArrayList<>();
            for (Map<String, Object> edge : outgoingEdges) {
                String targetId = (String) edge.get("target");
                if (targetId != null && !visited.contains(targetId)) {
                    nextIds.add(targetId);
                    visited.add(targetId);
                    // 记录边信息
                    chain.edges.add(toEdgeInfoFromMap(edge));
                }
            }

            // 从 edge Map 中直接构建节点信息（无需额外查 Neo4j）
            for (Map<String, Object> edge : outgoingEdges) {
                String targetId = (String) edge.get("target");
                if (targetId != null && nextIds.contains(targetId)) {
                    chain.nodes.add(toNodeInfoFromEdgeMap(edge));
                }
            }

            // 防爆炸：如果本跳节点数过多，截断
            if (visited.size() > effectiveDepth * API_CHAIN_PER_DEPTH_LIMIT) {
                log.warn("API call chain BFS exceeded node limit at depth {}: {} nodes visited (versionId={})",
                        depth, visited.size(), versionId);
                break;
            }
        }
        return chain;
    }

    /**
     * @deprecated 使用 {@link #getApiCallChain(String, String, String, Integer)} 替代。
     * 保留向后兼容：不传 maxDepth，使用配置默认值。
     */
    @Deprecated
    public PathChain getApiCallChain(String projectId, String versionId, String apiKey) {
        return getApiCallChain(projectId, versionId, apiKey, null);
    }

    /** 表影响范围 */
    public PathChain getTableImpact(String projectId, String versionId, String tableName) {
        // Neo4j 存储 versionId 无连字符，需规范化
        String normalizedVersionId = IdUtil.normalizeId(versionId);

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

        // L-16: 反向 BFS 使用 queryIncomingEdges 替代 queryEdges(..., 500)
        Set<String> visited = new HashSet<>();
        visited.add(target.get().getId());

        // 反向遍历：谁依赖/引用这个表（上游：SqlStatement←Method←ApiEndpoint）
        for (int depth = 0; depth < 6; depth++) {
            List<Map<String, Object>> incomingEdges = neo4jGraphDao.queryIncomingEdges(
                    projectId, normalizedVersionId, visited);

            if (incomingEdges.isEmpty()) break;

            Set<String> newNodes = new LinkedHashSet<>();
            for (Map<String, Object> edge : incomingEdges) {
                String sourceId = (String) edge.get("source");
                if (sourceId != null && !visited.contains(sourceId)) {
                    newNodes.add(sourceId);
                    visited.add(sourceId);
                    chain.edges.add(toEdgeInfoFromMap(edge));
                }
            }

            for (Map<String, Object> edge : incomingEdges) {
                String sourceId = (String) edge.get("source");
                if (sourceId != null && newNodes.contains(sourceId)) {
                    chain.nodes.add(toNodeInfoFromIncomingEdgeMap(edge));
                }
            }

            if (newNodes.isEmpty()) break;
        }

        // 正向遍历：查找关联表（通过 REFERENCES / JOINS 边，当前表→引用表）
        Set<String> relatedVisited = new HashSet<>(visited);
        for (int depth = 0; depth < 3; depth++) {
            List<Map<String, Object>> outgoingEdges = neo4jGraphDao.queryOutgoingEdges(
                    projectId, normalizedVersionId, relatedVisited);

            if (outgoingEdges.isEmpty()) break;

            Set<String> relatedIds = new LinkedHashSet<>();
            for (Map<String, Object> edge : outgoingEdges) {
                String edgeType = (String) edge.get("type");
                String targetId = (String) edge.get("target");
                if (relatedVisited.contains(targetId)) continue;

                // 收集 Table 类型的关联节点（REFERENCES/JOINS）
                if ("REFERENCES".equalsIgnoreCase(edgeType) || "JOINS".equalsIgnoreCase(edgeType)) {
                    relatedIds.add(targetId);
                    relatedVisited.add(targetId);
                    chain.edges.add(toEdgeInfoFromMap(edge));
                }
                // SqlStatement 通过 READS/WRITES 访问的表
                else if ("READS".equalsIgnoreCase(edgeType) || "WRITES".equalsIgnoreCase(edgeType)) {
                    relatedIds.add(targetId);
                    relatedVisited.add(targetId);
                    chain.edges.add(toEdgeInfoFromMap(edge));
                }
            }

            // 反向：其他表通过 REFERENCES 引用当前表
            List<Map<String, Object>> incomingEdges = neo4jGraphDao.queryIncomingEdges(
                    projectId, normalizedVersionId, relatedVisited);
            for (Map<String, Object> edge : incomingEdges) {
                String edgeType = (String) edge.get("type");
                String sourceId = (String) edge.get("source");
                if (relatedVisited.contains(sourceId)) continue;
                if ("REFERENCES".equalsIgnoreCase(edgeType)) {
                    relatedIds.add(sourceId);
                    relatedVisited.add(sourceId);
                    chain.edges.add(toEdgeInfoFromMap(edge));
                }
            }

            for (Map<String, Object> edge : outgoingEdges) {
                String targetId = (String) edge.get("target");
                if (targetId != null && relatedIds.contains(targetId)) {
                    chain.nodes.add(toNodeInfoFromEdgeMap(edge));
                }
            }
            for (Map<String, Object> edge : incomingEdges) {
                String sourceId = (String) edge.get("source");
                if (sourceId != null && relatedIds.contains(sourceId)) {
                    chain.nodes.add(toNodeInfoFromIncomingEdgeMap(edge));
                }
            }

            if (relatedIds.isEmpty()) break;
        }

        // 补充：如果反向遍历没有找到上游（SqlStatement 缺失），尝试通过共享访问推断隐式关联
        if (chain.nodes.size() <= 1) {
            log.debug("Table {} has minimal upstream impact, attempting implicit association inference", tableName);
            inferImplicitTableAssociations(projectId, normalizedVersionId, target.get(), chain, relatedVisited);
        }

        return chain;
    }

    /**
     * 隐式表关联推断：查找所有访问过当前表的 SqlStatement，然后收集它们访问的其他表。
     */
    private void inferImplicitTableAssociations(String projectId, String versionId,
                                                  GraphNode target, PathChain chain,
                                                  Set<String> relatedVisited) {
        // 找到所有通过 READS/WRITES 访问当前表的 SqlStatement
        Set<String> sqlStatementIds = new HashSet<>();
        List<Map<String, Object>> incomingEdges = neo4jGraphDao.queryIncomingEdges(
                projectId, versionId, Set.of(target.getId()));
        for (Map<String, Object> edge : incomingEdges) {
            String edgeType = (String) edge.get("type");
            if ("READS".equalsIgnoreCase(edgeType) || "WRITES".equalsIgnoreCase(edgeType)) {
                sqlStatementIds.add((String) edge.get("source"));
            }
        }

        if (sqlStatementIds.isEmpty()) return;

        // 收集这些 SqlStatement 访问的其他表
        List<Map<String, Object>> outgoingEdges = neo4jGraphDao.queryOutgoingEdges(
                projectId, versionId, sqlStatementIds);
        Set<String> implicitTableIds = new HashSet<>();
        for (Map<String, Object> edge : outgoingEdges) {
            String edgeType = (String) edge.get("type");
            String targetId = (String) edge.get("target");
            if (("READS".equalsIgnoreCase(edgeType) || "WRITES".equalsIgnoreCase(edgeType))
                    && !target.getId().equals(targetId)) {
                implicitTableIds.add(targetId);
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

    private NodeInfo toInfo(GraphNode node) {
        return new NodeInfo(node.getId(), node.getNodeType(), node.getNodeName(),
                node.getDisplayName(), node.getSourcePath(),
                node.getConfidence() != null ? node.getConfidence().doubleValue() : 1.0,
                node.getStatus());
    }

    /** L-16: 从 queryOutgoingEdges 的 Map 构建目标节点信息 */
    private NodeInfo toNodeInfoFromEdgeMap(Map<String, Object> edge) {
        String id = (String) edge.get("target");
        String type = (String) edge.get("toType");
        String name = (String) edge.get("toName");
        String displayName = (String) edge.get("toDisplayName");
        String sourcePath = (String) edge.get("toSourcePath");
        Object confObj = edge.get("toConfidence");
        double confidence = 1.0;
        if (confObj instanceof BigDecimal) {
            confidence = ((BigDecimal) confObj).doubleValue();
        } else if (confObj instanceof Number) {
            confidence = ((Number) confObj).doubleValue();
        }
        String status = (String) edge.get("toStatus");
        return new NodeInfo(id, type != null ? type : "Unknown",
                name != null ? name : "", displayName, sourcePath, confidence, status);
    }

    /** L-16: 从 queryIncomingEdges 的 Map 构建源节点信息 */
    private NodeInfo toNodeInfoFromIncomingEdgeMap(Map<String, Object> edge) {
        String id = (String) edge.get("source");
        String type = (String) edge.get("fromType");
        String name = (String) edge.get("fromName");
        String displayName = (String) edge.get("fromDisplayName");
        String sourcePath = (String) edge.get("fromSourcePath");
        Object confObj = edge.get("fromConfidence");
        double confidence = 1.0;
        if (confObj instanceof BigDecimal) {
            confidence = ((BigDecimal) confObj).doubleValue();
        } else if (confObj instanceof Number) {
            confidence = ((Number) confObj).doubleValue();
        }
        String status = (String) edge.get("fromStatus");
        return new NodeInfo(id, type != null ? type : "Unknown",
                name != null ? name : "", displayName, sourcePath, confidence, status);
    }

    private EdgeInfo toEdgeInfo(GraphEdge edge) {
        return new EdgeInfo(edge.getId(), edge.getEdgeType(),
                edge.getFromNodeId(), edge.getToNodeId());
    }

    /** L-16: 从 queryOutgoingEdges/queryIncomingEdges 的 Map 构建边信息 */
    private EdgeInfo toEdgeInfoFromMap(Map<String, Object> edge) {
        String id = (String) edge.get("id");
        String type = (String) edge.get("type");
        String source = (String) edge.get("source");
        String target = (String) edge.get("target");
        return new EdgeInfo(id, type, source, target);
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
