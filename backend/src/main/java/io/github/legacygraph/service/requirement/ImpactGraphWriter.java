package io.github.legacygraph.service.requirement;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.requirement.ImpactNodeRisk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 影响子图回写 Neo4j 服务（G-19）。
 * <p>将需求影响分析结果写回 Neo4j 节点 properties，使图谱节点携带影响标记，
 * 供前端分层高亮、风险排序以及影响范围查询使用。</p>
 * <p>写入的节点属性：</p>
 * <ul>
 *   <li>{@code impactDepth} — 距离变更起点的跳数</li>
 *   <li>{@code impactType} — 影响层级（L0~L4）</li>
 *   <li>{@code riskScore} — 风险分数</li>
 *   <li>{@code impactSource} — 触发影响的需求 ID</li>
 *   <li>{@code impactUpdatedAt} — 写入时间戳</li>
 * </ul>
 */
@Slf4j
@Service
public class ImpactGraphWriter {

    private final Neo4jGraphDao neo4jGraphDao;

    public ImpactGraphWriter(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * 将影响信息写回 Neo4j 节点。
     * <p>对每个受影响节点执行 Cypher SET，按 nodeKey 或 id 匹配节点，
     * 写入影响层级、风险分数等标记。写入前先清除同一需求的旧标记，避免脏数据。</p>
     *
     * @param requirementId 需求 ID（作为 impactSource）
     * @param nodeRisks      受影响节点的风险评估列表
     */
    public void writeback(String requirementId, List<ImpactNodeRisk> nodeRisks) {
        if (requirementId == null || requirementId.isBlank()) {
            log.warn("writeback 跳过：requirementId 为空");
            return;
        }
        if (nodeRisks == null || nodeRisks.isEmpty()) {
            log.info("writeback 跳过：nodeRisks 为空 (requirementId={})", requirementId);
            return;
        }

        // 先清除同一 requirementId 的旧影响标记
        clearByRequirement(requirementId);

        int success = 0;
        int fail = 0;
        for (ImpactNodeRisk risk : nodeRisks) {
            if (risk.getNodeId() == null || risk.getNodeId().isBlank()) {
                log.warn("writeback 跳过节点：nodeId 为空 (requirementId={})", requirementId);
                continue;
            }
            try {
                String cypher = """
                        MATCH (n) WHERE n.nodeKey = $nodeId OR n.id = $nodeId
                        SET n.impactDepth = $depth,
                            n.impactType = $impactType,
                            n.riskScore = $riskScore,
                            n.impactSource = $requirementId,
                            n.impactUpdatedAt = timestamp()
                        """;
                Map<String, Object> params = new HashMap<>();
                params.put("nodeId", risk.getNodeId());
                params.put("depth", risk.getDepth());
                params.put("impactType", risk.getImpactLevel());
                params.put("riskScore", risk.getRiskScore());
                params.put("requirementId", requirementId);
                neo4jGraphDao.executeWriteQuery(cypher, params);
                success++;
            } catch (Exception e) {
                log.warn("writeback 节点失败: nodeId={}, requirementId={}, err={}",
                        risk.getNodeId(), requirementId, e.getMessage());
                fail++;
            }
        }
        log.info("Impact writeback 完成: requirementId={}, total={}, success={}, fail={}",
                requirementId, nodeRisks.size(), success, fail);
    }

    /**
     * 清除同一 requirementId 的旧影响标记。
     * <p>在重新写入影响信息前调用，确保不会残留过期标记。</p>
     *
     * @param requirementId 需求 ID
     */
    public void clearByRequirement(String requirementId) {
        if (requirementId == null || requirementId.isBlank()) {
            return;
        }
        try {
            String cypher = """
                    MATCH (n) WHERE n.impactSource = $requirementId
                    REMOVE n.impactDepth, n.impactType, n.riskScore,
                           n.impactSource, n.impactUpdatedAt
                    """;
            neo4jGraphDao.executeWriteQuery(cypher, Map.of("requirementId", requirementId));
            log.info("Impact markers cleared for requirementId={}", requirementId);
        } catch (Exception e) {
            log.warn("clearByRequirement 失败: requirementId={}, err={}",
                    requirementId, e.getMessage());
        }
    }

    /**
     * 查询被某需求影响的节点列表。
     * <p>按 impactSource 属性过滤，返回受影响节点的关键信息。</p>
     *
     * @param requirementId 需求 ID
     * @return 受影响节点列表，每行包含 {nodeId, nodeKey, nodeName, nodeType,
     *         impactDepth, impactType, riskScore, impactSource, impactUpdatedAt}
     */
    public List<Map<String, Object>> queryImpactedNodes(String requirementId) {
        if (requirementId == null || requirementId.isBlank()) {
            return List.of();
        }
        String cypher = """
                MATCH (n) WHERE n.impactSource = $requirementId
                RETURN n.id AS nodeId,
                       n.nodeKey AS nodeKey,
                       n.nodeName AS nodeName,
                       n.nodeType AS nodeType,
                       n.impactDepth AS impactDepth,
                       n.impactType AS impactType,
                       n.riskScore AS riskScore,
                       n.impactSource AS impactSource,
                       n.impactUpdatedAt AS impactUpdatedAt
                ORDER BY n.impactDepth ASC, n.riskScore DESC
                """;
        return neo4jGraphDao.executeReadQuery(cypher, Map.of("requirementId", requirementId));
    }
}
