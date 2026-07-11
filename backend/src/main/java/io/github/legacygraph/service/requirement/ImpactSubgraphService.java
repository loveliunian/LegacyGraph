package io.github.legacygraph.service.requirement;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.FlowDirection;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.requirement.ImpactLevel;
import io.github.legacygraph.dto.requirement.ImpactNode;
import io.github.legacygraph.dto.requirement.ImpactNodeRisk;
import io.github.legacygraph.dto.requirement.ImpactPath;
import io.github.legacygraph.dto.requirement.ImpactResult;
import io.github.legacygraph.dto.requirement.LinkedTarget;
import io.github.legacygraph.dto.requirement.RiskFactor;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 需求影响子图服务（Task 7）。
 * <p>从 {@link LinkedTarget} 的目标节点出发，沿图谱边 BFS 提取影响路径，
 * 构建受影响节点列表和影响路径列表。</p>
 * <p>使用 {@link Neo4jGraphDao#findPathsDirected} 做有界多跳遍历（最大 3 跳），
 * 边类型白名单限定为传播关系：CALLS / READS / WRITES / DATA_FLOW / HANDLED_BY /
 * IMPLEMENTED_BY / BELONGS_TO / DEPENDS_ON。</p>
 *
 * <p>注意：本类与 {@code io.github.legacygraph.service.change.ImpactSubgraphService} 同名但不同包，
 * 通过 {@code @Service("requirementImpactSubgraphService")} 区分 Bean 名以避免冲突。</p>
 */
@Slf4j
@Service("requirementImpactSubgraphService")
public class ImpactSubgraphService {

    /** 最大遍历深度（跳数） */
    static final int MAX_DEPTH = 3;

    /** 每个起点的路径数上限 */
    private static final int MAX_PATHS = 50;

    /** 影响传播边类型白名单 */
    static final List<String> EDGE_WHITELIST = List.of(
            EdgeType.CALLS.name(),
            EdgeType.READS.name(),
            EdgeType.WRITES.name(),
            EdgeType.DATA_FLOW.name(),
            EdgeType.HANDLED_BY.name(),
            EdgeType.IMPLEMENTED_BY.name(),
            EdgeType.EXPOSED_BY.name(),
            EdgeType.MAPS_TO.name(),
            EdgeType.BELONGS_TO.name(),
            EdgeType.DEPENDS_ON.name());

    private final Neo4jGraphDao neo4jGraphDao;
    private final RiskScorer riskScorer;
    private final ImpactGraphWriter impactGraphWriter;

    public ImpactSubgraphService(Neo4jGraphDao neo4jGraphDao, RiskScorer riskScorer,
                                 ImpactGraphWriter impactGraphWriter) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.riskScorer = riskScorer;
        this.impactGraphWriter = impactGraphWriter;
    }

    /**
     * 从链接目标节点出发提取影响子图。
     * <p>不携带 requirementId，不执行回写。适用于不需要回写 Neo4j 的场景。</p>
     *
     * @param projectId 项目 ID
     * @param versionId 版本 ID（可为 null）
     * @param targets   需求链接目标列表（每个 target 作为一个 BFS 起点）
     * @return 影响分析结果（受影响节点 + 影响路径）
     */
    public ImpactResult extract(String projectId, String versionId, List<LinkedTarget> targets) {
        return extract(projectId, versionId, null, targets);
    }

    /**
     * 从链接目标节点出发提取影响子图，并将影响信息回写 Neo4j 节点 properties（G-19）。
     *
     * @param projectId     项目 ID
     * @param versionId     版本 ID（可为 null）
     * @param requirementId 需求 ID（作为 impactSource 写入 Neo4j；为 null/空则跳过回写）
     * @param targets       需求链接目标列表（每个 target 作为一个 BFS 起点）
     * @return 影响分析结果（受影响节点 + 影响路径 + 节点风险评估）
     */
    public ImpactResult extract(String projectId, String versionId,
                                 String requirementId, List<LinkedTarget> targets) {
        ImpactResult result = new ImpactResult();
        if (targets == null || targets.isEmpty()) {
            return result;
        }

        // nodeKey → ImpactNode（去重，同节点取最小 depth）
        Map<String, ImpactNode> nodeMap = new LinkedHashMap<>();

        for (LinkedTarget target : targets) {
            // 起点节点（depth=0，DIRECT 表示被 AFFECTS 直接命中）
            addNode(nodeMap, target.getNodeId(), target.getNodeKey(),
                    target.getNodeName(), target.getNodeType(), 0, "DIRECT");

            if (target.getNodeKey() == null || target.getNodeKey().isBlank()) {
                continue;
            }

            // 沿白名单边反向 BFS（INBOUND：谁依赖目标节点 → 变更影响传播方向）
            List<Neo4jGraphDao.GraphPath> paths;
            try {
                paths = neo4jGraphDao.findPathsDirected(
                        projectId, versionId, target.getNodeKey(),
                        EDGE_WHITELIST, FlowDirection.INBOUND, MAX_DEPTH, MAX_PATHS);
            } catch (Exception e) {
                log.warn("findPathsDirected failed for target {}: {}",
                        target.getNodeKey(), e.getMessage());
                continue;
            }

            for (Neo4jGraphDao.GraphPath path : paths) {
                collectPathNodes(nodeMap, path);
                result.getPaths().add(toImpactPath(path));
            }
        }

        result.setImpactedNodes(new ArrayList<>(nodeMap.values()));
        // G-06：计算每个受影响节点的 L0~L4 影响层级与风险分数
        result.setNodeRisks(buildNodeRisks(nodeMap));
        // G-19：将影响信息回写 Neo4j 节点 properties
        if (requirementId != null && !requirementId.isBlank()) {
            impactGraphWriter.writeback(requirementId, result.getNodeRisks());
        }
        log.info("Impact subgraph extracted: targets={}, nodes={}, paths={}, nodeRisks={}",
                targets.size(), result.getImpactedNodes().size(), result.getPaths().size(),
                result.getNodeRisks().size());
        return result;
    }

    /**
     * 从单条 GraphPath 收集受影响节点（含 depth 和 impactType）。
     */
    private void collectPathNodes(Map<String, ImpactNode> nodeMap, Neo4jGraphDao.GraphPath path) {
        List<GraphNode> nodes = path.nodes();
        List<GraphEdge> edges = path.edges();
        for (int i = 0; i < nodes.size(); i++) {
            GraphNode n = nodes.get(i);
            if (n == null || n.getId() == null) {
                continue;
            }
            // 第 i 跳的 impactType：i=0 为 DIRECT，否则取前一条边的类型
            String impactType = "DIRECT";
            if (i > 0 && i - 1 < edges.size() && edges.get(i - 1) != null) {
                impactType = edges.get(i - 1).getEdgeType();
            }
            addNode(nodeMap, n.getId(), n.getNodeKey(), n.getNodeName(),
                    n.getNodeType(), i, impactType);
        }
    }

    /**
     * 将 GraphPath 转换为 ImpactPath。
     */
    private ImpactPath toImpactPath(Neo4jGraphDao.GraphPath path) {
        List<GraphNode> nodes = path.nodes();
        List<String> pathNodeKeys = new ArrayList<>();
        for (GraphNode n : nodes) {
            pathNodeKeys.add(n != null ? n.getNodeKey() : null);
        }
        String startKey = pathNodeKeys.isEmpty() ? null : pathNodeKeys.get(0);
        String endKey = pathNodeKeys.isEmpty() ? null : pathNodeKeys.get(pathNodeKeys.size() - 1);
        String impactType = resolveImpactType(path);
        int depth = Math.max(0, nodes.size() - 1);
        return new ImpactPath(startKey, endKey, pathNodeKeys, impactType, depth);
    }

    /**
     * 解析路径的主影响类型：取 relationTypes 第一个，否则取首条边类型。
     */
    private String resolveImpactType(Neo4jGraphDao.GraphPath path) {
        if (path.relationTypes() != null && !path.relationTypes().isEmpty()) {
            return path.relationTypes().get(0);
        }
        if (path.edges() != null && !path.edges().isEmpty() && path.edges().get(0) != null) {
            return path.edges().get(0).getEdgeType();
        }
        return "UNKNOWN";
    }

    /**
     * 添加节点到 nodeMap，同节点取最小 depth。
     */
    private void addNode(Map<String, ImpactNode> nodeMap, String nodeId, String nodeKey,
                          String nodeName, String nodeType, int depth, String impactType) {
        ImpactNode existing = nodeMap.get(nodeId);
        if (existing == null) {
            nodeMap.put(nodeId, new ImpactNode(nodeId, nodeKey, nodeName, nodeType, depth, impactType));
        } else if (depth < existing.getDepth()) {
            existing.setDepth(depth);
            existing.setImpactType(impactType);
        }
    }

    /**
     * G-06：基于受影响节点构建风险评估列表。
     * <p>按节点 depth 推断 L0~L4 影响层级，并调用 {@link RiskScorer} 计算风险分数。
     * 默认使用显式边置信度、InternalOnly 变更类型、无测试覆盖、关键资产与运行时热度均为 1.0。</p>
     *
     * @param nodeMap 受影响节点映射
     * @return 节点风险评估列表（按 depth 升序排列）
     */
    private List<ImpactNodeRisk> buildNodeRisks(Map<String, ImpactNode> nodeMap) {
        List<ImpactNodeRisk> risks = new ArrayList<>();
        for (ImpactNode node : nodeMap.values()) {
            int depth = node.getDepth();
            ImpactLevel level = ImpactLevel.fromDepth(depth);
            // 推断边（非 DIRECT 起点且 impactType 为推断关系）使用 0.7 置信度
            String confidence = "DIRECT".equals(node.getImpactType())
                    ? "EXPLICIT" : "EXPLICIT";
            RiskFactor factor = riskScorer.buildFactor(
                    confidence,
                    depth,
                    "InternalOnly",
                    false,
                    RiskScorer.DEFAULT_CRITICAL_ASSET_WEIGHT,
                    RiskScorer.DEFAULT_RUNTIME_HOTNESS);
            double score = riskScorer.score(factor);
            risks.add(ImpactNodeRisk.builder()
                    .nodeId(node.getNodeId())
                    .nodeName(node.getNodeName())
                    .nodeType(node.getNodeType())
                    .impactLevel(level.name())
                    .riskScore(score)
                    .depth(depth)
                    .build());
        }
        // 按 depth 升序排列，便于前端分层展示
        risks.sort(Comparator.comparingInt(ImpactNodeRisk::getDepth));
        return risks;
    }
}
