package io.github.legacygraph.extractors.bpmn;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 流程引擎运行时数据分析器。
 * <p>
 * 查询 {@code act_hi_taskinst} / {@code act_hi_procinst} 历史表,为 {@link BpmnProcessFact}
 * 填充运行时属性(execCount/avgDurationMs/rejectRate/flowCount),并构建 RUNTIME_FLOW_TO 边。
 * </p>
 *
 * <h3>防 OOM 措施</h3>
 * <ul>
 *   <li>历史查询加 {@code START_TIME_ > {now-90d}} 时间范围过滤</li>
 *   <li>加 {@code LIMIT 10000} 限制最大行数</li>
 *   <li>聚合查询用 GROUP BY 在数据库侧完成,不拉全表到内存</li>
 * </ul>
 */
@Slf4j
@Component
public class ProcessRuntimeAnalyzer {

    /** 运行时查询时间范围(最近 90 天),避免全量历史数据 OOM */
    private static final int RECENT_DAYS = 90;

    /** 路径频次查询最大记录数 */
    private static final int MAX_HISTORY_ROWS = 10000;

    private final Neo4jGraphDao neo4jGraphDao;

    public ProcessRuntimeAnalyzer(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * 用运行时数据增强 BpmnProcessFact。
     * 修改 fact 中每个 FlowNodeFact 的 execCount/avgDurationMs/rejectRate,
     * 修改每个 SequenceFlowFact 的 flowCount。
     *
     * @param facts  待增强的流程事实列表(会被原地修改)
     * @param conn   流程引擎数据库连接
     * @param prefix 表前缀(通常 "act_")
     */
    public void enrichWithRuntimeData(List<BpmnProcessFact> facts, Connection conn, String prefix) {
        if (facts == null || facts.isEmpty()) return;
        try {
            // 构建 nodeKey 查找表: processKey.bpmnId → FlowNodeFact
            Map<String, BpmnProcessFact.FlowNodeFact> nodeByKey = new HashMap<>();
            for (var fact : facts) {
                if (fact.getNodes() == null) continue;
                for (var node : fact.getNodes()) {
                    String key = fact.getProcessKey() + "." + node.getBpmnId();
                    nodeByKey.put(key, node);
                }
            }
            if (nodeByKey.isEmpty()) return;

            // 1. 节点执行频次
            Map<String, Long> execCounts = queryNodeExecCounts(conn, prefix);
            for (var entry : execCounts.entrySet()) {
                var node = nodeByKey.get(entry.getKey());
                if (node != null) node.setExecCount(entry.getValue());
            }

            // 2. 节点平均时长(毫秒)
            Map<String, Long> avgDurations = queryNodeAvgDurations(conn, prefix);
            for (var entry : avgDurations.entrySet()) {
                var node = nodeByKey.get(entry.getKey());
                if (node != null) node.setAvgDurationMs(entry.getValue());
            }

            // 3. 驳回率(0-1)
            Map<String, Double> rejectRates = queryNodeRejectRates(conn, prefix);
            for (var entry : rejectRates.entrySet()) {
                var node = nodeByKey.get(entry.getKey());
                if (node != null) node.setRejectRate(entry.getValue());
            }

            // 4. 路径频次 → 填充 SequenceFlowFact.flowCount
            Map<String, Long> flowCounts = queryPathFlowCounts(conn, prefix);
            for (var fact : facts) {
                if (fact.getFlows() == null) continue;
                for (var sf : fact.getFlows()) {
                    String key = fact.getProcessKey() + "." + sf.getSourceBpmnId() + "->" + sf.getTargetBpmnId();
                    Long count = flowCounts.get(key);
                    if (count != null) sf.setFlowCount(count);
                }
            }

            log.info("Runtime data enriched: {} nodes, {} flows with runtime stats",
                    nodeByKey.size(), flowCounts.size());
        } catch (Exception e) {
            log.warn("Runtime data enrichment failed (non-blocking): {}", e.getMessage());
        }
    }

    /**
     * 构建 RUNTIME_FLOW_TO 边:历史数据中实际发生的流转。
     * <p>
     * 用于发现定义里没有但实际发生的路径、高频/低频路径、瓶颈节点。
     * 对每条实际发生的迁移:
     * <ul>
     *   <li>若对应的 FLOW_TO 边已存在:跳过(不重复建边)</li>
     *   <li>若不存在(动态流转/驳回):新建 RUNTIME_FLOW_TO 边,confidence=0.85, status=PENDING_CONFIRM</li>
     * </ul>
     *
     * @return 新建边数
     */
    public int buildRuntimeFlowEdges(String projectId, String versionId,
                                      Connection conn, String prefix, GraphBuilder graphBuilder) {
        try {
            // 1. 查询所有实际发生的节点迁移: (procKey, fromBpmnId, toBpmnId, count)
            List<RuntimeTransition> transitions = queryRuntimeTransitions(conn, prefix);
            if (transitions.isEmpty()) return 0;

            // 2. 收集已存在的 FLOW_TO 边的 edgeKey 集合,避免重复
            Set<String> existingFlowEdgeKeys = collectExistingFlowEdgeKeys(projectId, versionId);

            // 3. 构建节点查找表: bpmnId → GraphNode (按 processKey 分组)
            Map<String, Map<String, GraphNode>> nodesByProcess = loadFlowNodesByProcess(projectId, versionId);

            // 4. 构建 RUNTIME_FLOW_TO 边
            List<GraphEdge> newEdges = new ArrayList<>();
            Set<String> runtimeEdgeKeys = new HashSet<>();
            for (var trans : transitions) {
                String edgeKey = "bpmn.runtime_flow:" + trans.processKey().toLowerCase()
                        + "." + trans.fromKey() + "->" + trans.toKey();
                // 若已有 FLOW_TO 边,跳过
                String defEdgeKey = "bpmn.flow:" + trans.processKey().toLowerCase()
                        + "." + trans.fromKey() + "->" + trans.toKey();
                if (existingFlowEdgeKeys.contains(defEdgeKey)) continue;
                // 避免重复
                if (!runtimeEdgeKeys.add(edgeKey)) continue;

                Map<String, GraphNode> procNodes = nodesByProcess.get(trans.processKey());
                if (procNodes == null) continue;
                GraphNode fromNode = procNodes.get(trans.fromKey());
                GraphNode toNode = procNodes.get(trans.toKey());
                if (fromNode == null || toNode == null) continue;

                GraphEdge edge = new GraphEdge();
                edge.setId(UUID.randomUUID().toString());
                edge.setProjectId(projectId);
                edge.setVersionId(versionId);
                edge.setFromNodeId(fromNode.getId());
                edge.setToNodeId(toNode.getId());
                edge.setEdgeType(EdgeType.RUNTIME_FLOW_TO.name());
                edge.setEdgeKey(edgeKey);
                edge.setSourceType("BPMN_RUNTIME");
                edge.setConfidence(BigDecimal.valueOf(0.85));
                edge.setStatus(NodeStatus.PENDING_CONFIRM.name());
                edge.setProperties("{\"flowCount\":" + trans.count() + "}");
                newEdges.add(edge);
            }

            if (newEdges.isEmpty()) return 0;
            int merged = neo4jGraphDao.mergeEdgesBatch(newEdges);
            log.info("Built {} RUNTIME_FLOW_TO edges (of {} transitions)", merged, transitions.size());
            return merged;
        } catch (Exception e) {
            log.warn("Build runtime flow edges failed (non-blocking): {}", e.getMessage());
            return 0;
        }
    }

    // ==================== 查询方法 ====================

    /** 节点执行频次: 按 PROC_DEF_ID_ + TASK_DEF_KEY_ 分组,key 格式为 "processKey.bpmnId" */
    private Map<String, Long> queryNodeExecCounts(Connection conn, String prefix) throws SQLException {
        String sql = "SELECT PROC_DEF_ID_, TASK_DEF_KEY_, COUNT(*) AS cnt FROM " + prefix + "HI_TASKINST " +
                     "WHERE START_TIME_ > DATEADD(DAY, -" + RECENT_DAYS + ", CURRENT_TIMESTAMP) " +
                     "AND TASK_DEF_KEY_ IS NOT NULL " +
                     "GROUP BY PROC_DEF_ID_, TASK_DEF_KEY_";
        Map<String, Long> result = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String procDefId = getStringCI(rs, "PROC_DEF_ID_");
                String taskKey = getStringCI(rs, "TASK_DEF_KEY_");
                long cnt = rs.getLong("cnt");
                if (taskKey != null) {
                    String procKey = extractProcKeyFromDefId(procDefId);
                    result.put(procKey + "." + taskKey, cnt);
                }
            }
        }
        return result;
    }

    /** 节点平均时长(毫秒): 按 PROC_DEF_ID_ + TASK_DEF_KEY_ 聚合 END_TIME_-START_TIME_ */
    private Map<String, Long> queryNodeAvgDurations(Connection conn, String prefix) throws SQLException {
        String durationExpr = durationExpr(conn);
        String sql = "SELECT PROC_DEF_ID_, TASK_DEF_KEY_, AVG(" + durationExpr + ") AS avg_sec FROM " + prefix + "HI_TASKINST " +
                     "WHERE START_TIME_ > DATEADD(DAY, -" + RECENT_DAYS + ", CURRENT_TIMESTAMP) " +
                     "AND END_TIME_ IS NOT NULL AND TASK_DEF_KEY_ IS NOT NULL " +
                     "GROUP BY PROC_DEF_ID_, TASK_DEF_KEY_";
        Map<String, Long> result = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String procDefId = getStringCI(rs, "PROC_DEF_ID_");
                String taskKey = getStringCI(rs, "TASK_DEF_KEY_");
                long avgSec = rs.getLong("avg_sec");
                if (taskKey != null) {
                    String procKey = extractProcKeyFromDefId(procDefId);
                    result.put(procKey + "." + taskKey, avgSec * 1000L); // 秒 → 毫秒
                }
            }
        }
        return result;
    }

    /**
     * 驳回率:同一 PROC_INST_ID_ 中 TASK_DEF_KEY_ 重复出现次数 / 总执行次数。
     * 驳回 = 回退后再次到达同一节点。
     */
    private Map<String, Double> queryNodeRejectRates(Connection conn, String prefix) throws SQLException {
        // 先查总执行次数
        Map<String, Long> totalCounts = queryNodeExecCounts(conn, prefix);
        // 查重复出现次数: 按 PROC_INST_ID_ + PROC_DEF_ID_ + TASK_DEF_KEY_ 分组,HAVING COUNT > 1
        String sql = "SELECT PROC_DEF_ID_, TASK_DEF_KEY_, COUNT(*) AS repeat_cnt FROM (" +
                     "  SELECT PROC_INST_ID_, PROC_DEF_ID_, TASK_DEF_KEY_ FROM " + prefix + "HI_TASKINST " +
                     "  WHERE START_TIME_ > DATEADD(DAY, -" + RECENT_DAYS + ", CURRENT_TIMESTAMP) " +
                     "  AND TASK_DEF_KEY_ IS NOT NULL " +
                     "  GROUP BY PROC_INST_ID_, PROC_DEF_ID_, TASK_DEF_KEY_ HAVING COUNT(*) > 1" +
                     ") t GROUP BY PROC_DEF_ID_, TASK_DEF_KEY_";
        Map<String, Long> repeatCounts = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String procDefId = getStringCI(rs, "PROC_DEF_ID_");
                String taskKey = getStringCI(rs, "TASK_DEF_KEY_");
                long cnt = rs.getLong("repeat_cnt");
                if (taskKey != null) {
                    String procKey = extractProcKeyFromDefId(procDefId);
                    repeatCounts.put(procKey + "." + taskKey, cnt);
                }
            }
        }
        // 驳回率 = 重复次数 / 总次数
        Map<String, Double> result = new HashMap<>();
        for (var entry : repeatCounts.entrySet()) {
            Long total = totalCounts.get(entry.getKey());
            if (total != null && total > 0) {
                result.put(entry.getKey(), Math.min(1.0, entry.getValue() * 1.0 / total));
            }
        }
        return result;
    }

    /**
     * 路径频次:按 PROC_INST_ID_ 分组,START_TIME_ 排序,相邻 TASK_DEF_KEY_ 迁移计数。
     * 返回 key = "fromKey->toKey" (不含 processKey,需调用方补充)。
     */
    private Map<String, Long> queryPathFlowCounts(Connection conn, String prefix) throws SQLException {
        // 查询最近 90 天的历史任务,按 PROC_INST_ID_ 分组,START_TIME_ 排序
        String sql = "SELECT PROC_DEF_ID_, PROC_INST_ID_, TASK_DEF_KEY_, START_TIME_ FROM " + prefix + "HI_TASKINST " +
                     "WHERE START_TIME_ > DATEADD(DAY, -" + RECENT_DAYS + ", CURRENT_TIMESTAMP) " +
                     "AND TASK_DEF_KEY_ IS NOT NULL AND PROC_INST_ID_ IS NOT NULL " +
                     "ORDER BY PROC_INST_ID_, START_TIME_ LIMIT " + MAX_HISTORY_ROWS;
        Map<String, Long> result = new HashMap<>();
        String currentProcInst = null;
        String currentProcDef = null;
        String prevTaskKey = null;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String procDefId = getStringCI(rs, "PROC_DEF_ID_");
                String procInstId = getStringCI(rs, "PROC_INST_ID_");
                String taskKey = getStringCI(rs, "TASK_DEF_KEY_");
                if (procInstId == null || taskKey == null) continue;

                if (!procInstId.equals(currentProcInst)) {
                    currentProcInst = procInstId;
                    currentProcDef = procDefId;
                    prevTaskKey = null;
                }
                if (prevTaskKey != null && currentProcDef != null) {
                    String procKey = extractProcKeyFromDefId(currentProcDef);
                    String key = procKey + "." + prevTaskKey + "->" + taskKey;
                    result.merge(key, 1L, Long::sum);
                }
                prevTaskKey = taskKey;
            }
        }
        return result;
    }

    /** 查询实际发生的节点迁移列表(含 processKey) */
    private List<RuntimeTransition> queryRuntimeTransitions(Connection conn, String prefix) throws SQLException {
        Map<String, Long> counts = queryPathFlowCounts(conn, prefix);
        List<RuntimeTransition> result = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            String key = entry.getKey();
            int sep = key.indexOf(".");
            if (sep < 0) continue;
            String procKey = key.substring(0, sep);
            String rest = key.substring(sep + 1);
            int arrow = rest.indexOf("->");
            if (arrow < 0) continue;
            String fromKey = rest.substring(0, arrow);
            String toKey = rest.substring(arrow + 2);
            result.add(new RuntimeTransition(procKey, fromKey, toKey, entry.getValue()));
        }
        return result;
    }

    /** 从 PROC_DEF_ID_(如 "leaveProcess:1:12345")提取 processKey(冒号前的部分) */
    private String extractProcKeyFromDefId(String procDefId) {
        if (procDefId == null) return "";
        int idx = procDefId.indexOf(':');
        return idx > 0 ? procDefId.substring(0, idx) : procDefId;
    }

    // ==================== 辅助方法 ====================

    /**
     * TIMESTAMPDIFF 跨数据库兼容。
     * H2 用 DATEDIFF('SECOND', a, b); MySQL 用 TIMESTAMPDIFF(SECOND, a, b)。
     */
    private String durationExpr(Connection conn) throws SQLException {
        String dbName = conn.getMetaData().getDatabaseProductName();
        if ("H2".equalsIgnoreCase(dbName)) {
            return "DATEDIFF('SECOND', START_TIME_, END_TIME_)";
        }
        return "TIMESTAMPDIFF(SECOND, START_TIME_, END_TIME_)";
    }

    /**
     * 大小写不敏感地从 ResultSet 读取字符串列。
     * H2/MySQL/PostgreSQL 对列名大小写处理不同。
     */
    private String getStringCI(ResultSet rs, String columnName) throws SQLException {
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (meta.getColumnLabel(i).equalsIgnoreCase(columnName)) {
                return rs.getString(i);
            }
        }
        return rs.getString(columnName); // fallback
    }

    /** 收集已存在的 FLOW_TO 边的 edgeKey 集合,避免重复建 RUNTIME_FLOW_TO 边 */
    private Set<String> collectExistingFlowEdgeKeys(String projectId, String versionId) {
        Set<String> keys = new HashSet<>();
        try {
            List<GraphEdge> edges = neo4jGraphDao.queryEdges(projectId, versionId,
                    EdgeType.FLOW_TO.name(), null, 10000);
            if (edges != null) {
                for (GraphEdge e : edges) {
                    if (e.getEdgeKey() != null) keys.add(e.getEdgeKey());
                }
            }
        } catch (Exception e) {
            log.debug("Failed to query existing FLOW_TO edges: {}", e.getMessage());
        }
        return keys;
    }

    /** 加载所有 BPMN 流程节点,按 processKey → bpmnId → GraphNode 分组 */
    private Map<String, Map<String, GraphNode>> loadFlowNodesByProcess(String projectId, String versionId) {
        Map<String, Map<String, GraphNode>> result = new HashMap<>();
        String[] types = {NodeType.UserTask.name(), NodeType.ServiceTask.name(), NodeType.Gateway.name()};
        for (String type : types) {
            try {
                List<GraphNode> nodes = neo4jGraphDao.queryNodes(projectId, versionId, type, null, null, null, 0);
                if (nodes == null) continue;
                for (GraphNode node : nodes) {
                    String nodeKey = node.getNodeKey();
                    if (nodeKey == null) continue;
                    // nodeKey 格式: bpmn.{type}:{processKey}.{bpmnId}
                    int colon = nodeKey.indexOf(':');
                    if (colon < 0) continue;
                    String afterColon = nodeKey.substring(colon + 1);
                    int dot = afterColon.indexOf('.');
                    if (dot < 0) continue;
                    String procKey = afterColon.substring(0, dot);
                    String bpmnId = afterColon.substring(dot + 1);
                    result.computeIfAbsent(procKey, k -> new HashMap<>()).put(bpmnId, node);
                }
            } catch (Exception e) {
                log.debug("Failed to query {} nodes: {}", type, e.getMessage());
            }
        }
        return result;
    }

    /** 运行时迁移记录 */
    private record RuntimeTransition(String processKey, String fromKey, String toKey, long count) {}
}
