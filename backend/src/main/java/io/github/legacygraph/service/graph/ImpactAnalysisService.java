package io.github.legacygraph.service.graph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * G-06: ImpactAnalysisService — 影响分析 L0~L4 分层。
 * <p>基于 L-11 的 8 类边（CALLS/READS/WRITES/DATA_FLOW/CONTAINS/IMPLEMENTS/GRANTS/DEPENDS_ON）
 * 作为 L0/L1 输入，逐层展开影响范围。</p>
 *
 * <p>分层定义：</p>
 * <ul>
 *   <li>L0: 直接影响（起始节点的直接邻居）</li>
 *   <li>L1: 一跳影响（起始节点的 1-hop 可达节点）</li>
 *   <li>L2: 两跳影响</li>
 *   <li>L3: 三跳影响</li>
 *   <li>L4: 四跳及以上（全图可达）</li>
 * </ul>
 */
@Slf4j
@Service
public class ImpactAnalysisService {

    private final io.github.legacygraph.dao.Neo4jGraphDao neo4jGraphDao;

    @Autowired
    public ImpactAnalysisService(io.github.legacygraph.dao.Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * 分层影响分析 — 从起始节点出发，按 BFS 层次返回 L0~L4 影响节点。
     *
     * @param projectId  项目 ID
     * @param versionId  版本 ID
     * @param startNodeId 起始节点 ID
     * @param maxDepth   最大深度（默认 4，对应 L4）
     * @return 分层影响结果：level → 节点列表
     */
    public Map<Integer, List<Map<String, Object>>> analyzeImpact(String projectId, String versionId,
                                                                   String startNodeId, int maxDepth) {
        int effectiveDepth = Math.max(1, Math.min(maxDepth, 4));
        Map<Integer, List<Map<String, Object>>> result = new LinkedHashMap<>();

        Set<String> visited = new HashSet<>();
        visited.add(startNodeId);

        // L0: 起始节点本身
        List<Map<String, Object>> l0 = new ArrayList<>();
        // 不额外查询起始节点详情，仅标记为 L0
        result.put(0, l0);

        Set<String> currentFrontier = new HashSet<>();
        currentFrontier.add(startNodeId);

        for (int level = 1; level <= effectiveDepth; level++) {
            List<Map<String, Object>> outgoingEdges = neo4jGraphDao.queryOutgoingEdges(projectId, versionId, currentFrontier);
            Set<String> nextFrontier = new HashSet<>();
            List<Map<String, Object>> levelNodes = new ArrayList<>();

            for (Map<String, Object> edge : outgoingEdges) {
                String targetId = (String) edge.get("target");
                if (targetId != null && !visited.contains(targetId)) {
                    visited.add(targetId);
                    nextFrontier.add(targetId);
                    // 从 edge Map 中提取目标节点信息
                    Map<String, Object> nodeInfo = new HashMap<>();
                    nodeInfo.put("id", targetId);
                    nodeInfo.put("nodeType", edge.get("toType"));
                    nodeInfo.put("nodeName", edge.get("toName"));
                    nodeInfo.put("displayName", edge.get("toDisplayName"));
                    nodeInfo.put("confidence", edge.get("toConfidence"));
                    nodeInfo.put("status", edge.get("toStatus"));
                    nodeInfo.put("sourcePath", edge.get("toSourcePath"));
                    nodeInfo.put("edgeType", edge.get("type"));
                    levelNodes.add(nodeInfo);
                }
            }

            result.put(level, levelNodes);
            if (nextFrontier.isEmpty()) break;
            currentFrontier = nextFrontier;
        }

        log.info("Impact analysis: startNode={}, levels={}", startNodeId, result.size());
        return result;
    }
}
