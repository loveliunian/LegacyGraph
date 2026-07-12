package io.github.legacygraph.service.graph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * G-06: ImpactAnalysisService — 影响分析分层。
 * <p>基于 L-11 的 8 类边（CALLS/READS/WRITES/DATA_FLOW/CONTAINS/IMPLEMENTS/GRANTS/DEPENDS_ON）
 * 作为 L0/L1 输入，逐层展开影响范围。</p>
 *
 * <p>S3-T6: 返回结构含 {@code direct}（L1 直接影响）和 {@code transitive}（L2+ 传递影响）字段，
 * 同时保留 {@code levels} L0-L4 分层结构供向后兼容。</p>
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
     * 分层影响分析 — 从起始节点出发，按 BFS 层次返回影响节点。
     * <p>S3-T6: 返回 {@link ImpactResult}，包含 direct（L1 直接影响）/ transitive（L2+ 传递影响）/ levels（L0-L4 分层）。</p>
     *
     * @param projectId   项目 ID
     * @param versionId   版本 ID
     * @param startNodeId 起始节点 ID
     * @param maxDepth    最大深度（默认 4，对应 L4，clamp ≤ 8）
     * @return 影响分析结果
     */
    public ImpactResult analyzeImpact(String projectId, String versionId,
                                      String startNodeId, int maxDepth) {
        // S3-T4: clamp ≤ 8，对齐 GraphPathReadModel 的 depth 软上限
        int effectiveDepth = Math.max(1, Math.min(maxDepth, 8));
        Map<Integer, List<Map<String, Object>>> levels = new LinkedHashMap<>();

        Set<String> visited = new HashSet<>();
        visited.add(startNodeId);

        // L0: 起始节点本身
        List<Map<String, Object>> l0 = new ArrayList<>();
        levels.put(0, l0);

        Set<String> currentFrontier = new HashSet<>();
        currentFrontier.add(startNodeId);

        // S3-T6: direct = L1（直接影响），transitive = L2+（传递影响）
        List<Map<String, Object>> direct = new ArrayList<>();
        List<Map<String, Object>> transitive = new ArrayList<>();

        for (int level = 1; level <= effectiveDepth; level++) {
            List<Map<String, Object>> outgoingEdges = neo4jGraphDao.queryOutgoingEdges(projectId, versionId, currentFrontier);
            Set<String> nextFrontier = new HashSet<>();
            List<Map<String, Object>> levelNodes = new ArrayList<>();

            for (Map<String, Object> edge : outgoingEdges) {
                String targetId = (String) edge.get("target");
                if (targetId != null && !visited.contains(targetId)) {
                    visited.add(targetId);
                    nextFrontier.add(targetId);
                    Map<String, Object> nodeInfo = new HashMap<>();
                    nodeInfo.put("id", targetId);
                    nodeInfo.put("nodeType", edge.get("toType"));
                    nodeInfo.put("nodeName", edge.get("toName"));
                    nodeInfo.put("displayName", edge.get("toDisplayName"));
                    nodeInfo.put("confidence", edge.get("toConfidence"));
                    nodeInfo.put("status", edge.get("toStatus"));
                    nodeInfo.put("sourcePath", edge.get("toSourcePath"));
                    nodeInfo.put("edgeType", edge.get("type"));
                    nodeInfo.put("level", level);
                    levelNodes.add(nodeInfo);

                    if (level == 1) {
                        direct.add(nodeInfo);
                    } else {
                        transitive.add(nodeInfo);
                    }
                }
            }

            levels.put(level, levelNodes);
            if (nextFrontier.isEmpty()) break;
            currentFrontier = nextFrontier;
        }

        ImpactResult result = new ImpactResult();
        result.startNodeId = startNodeId;
        result.direct = direct;
        result.transitive = transitive;
        result.levels = levels;
        result.directCount = direct.size();
        result.transitiveCount = transitive.size();

        log.info("Impact analysis: startNode={}, direct={}, transitive={}",
                startNodeId, direct.size(), transitive.size());
        return result;
    }

    /**
     * S3-T6: 影响分析结果 — 含 direct / transitive 字段。
     */
    public static class ImpactResult {
        /** 起始节点 ID */
        public String startNodeId;
        /** 直接影响（L1：一跳可达节点） */
        public List<Map<String, Object>> direct;
        /** 传递影响（L2+：多跳可达节点） */
        public List<Map<String, Object>> transitive;
        /** L0-L4 分层结构（向后兼容） */
        public Map<Integer, List<Map<String, Object>>> levels;
        /** 直接影响节点数 */
        public int directCount;
        /** 传递影响节点数 */
        public int transitiveCount;
    }
}
