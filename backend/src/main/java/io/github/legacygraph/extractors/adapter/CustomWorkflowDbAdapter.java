package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.bpmn.BpmnProcessFact;
import io.github.legacygraph.extractors.bpmn.EngineType;
import io.github.legacygraph.extractors.bpmn.ProcessEngineConnectionInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自研流程引擎数据库适配器 — 通过配置驱动的表名/字段映射,支持自研流程引擎(状态机表/流转配置表)。
 * <p>
 * 不通过 {@link #supports(ScanContext, SourceAsset)} 触发,由 ProjectScanner 主动调用
 * {@link #scanFromDatabase(ScanContext)}。失败不阻塞,仅 log.warn。
 * </p>
 *
 * <h3>配置约定</h3>
 * 从目标项目 application.yml 读取 workflow.tables.* / workflow.columns.*:
 * <pre>
 * workflow:
 *   datasource:
 *     url: jdbc:mysql://...
 *   tables:
 *     processDefinition: t_flow_definition
 *     flowNode: t_flow_node
 *     sequenceFlow: t_flow_transition
 *     runtimeLog: t_flow_log
 *   columns:
 *     processKey: proc_key
 *     processName: proc_name
 *     nodeId: node_id
 *     nodeName: node_name
 *     nodeType: node_type   # 值映射: 1=UserTask, 2=ServiceTask, 3=Gateway
 *     sourceNode: from_node
 *     targetNode: to_node
 *     condition: condition_expr
 * </pre>
 *
 * <h3>nodeType 值映射</h3>
 * <ul>
 *   <li>1 / "userTask" / "user" → USER_TASK</li>
 *   <li>2 / "serviceTask" / "service" → SERVICE_TASK</li>
 *   <li>3 / "gateway" / "decision" → GATEWAY</li>
 * </ul>
 */
@Slf4j
@Component
public class CustomWorkflowDbAdapter implements ExtractionAdapter {

    private final GraphBuilder graphBuilder;

    public CustomWorkflowDbAdapter(GraphBuilder graphBuilder) {
        this.graphBuilder = graphBuilder;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        return false; // 由 ProjectScanner 主动调用
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        return ExtractionResult.builder().processedAssets(0).build();
    }

    /**
     * 从自研流程引擎数据库扫描流程定义/节点/流转/运行时日志。
     * 全部通过 customTableMapping / customColumnMapping 动态拼接 SQL,不硬编码表名/字段名。
     */
    public ExtractionResult scanFromDatabase(ScanContext context) {
        ProcessEngineConnectionInfo connInfo =
                (ProcessEngineConnectionInfo) context.getConfig().get("processEngine.db");
        if (connInfo == null || !connInfo.isConnectable()
                || connInfo.getEngineType() != EngineType.CUSTOM) {
            return ExtractionResult.builder().processedAssets(0).summary("Custom workflow DB scan skipped").build();
        }

        Map<String, String> tableMap = connInfo.getCustomTableMapping();
        Map<String, String> colMap = connInfo.getCustomColumnMapping();
        if (tableMap == null || tableMap.isEmpty()) {
            log.warn("Custom workflow DB scan: no table mapping configured, skipping");
            return ExtractionResult.builder().processedAssets(0).build();
        }

        int totalNodes = 0, totalEdges = 0;
        try (Connection conn = createConnection(connInfo)) {
            // 1. 读流程定义表 → BpmnProcessFact (复用,虽然不是 BPMN 标准,但中间表示兼容)
            List<BpmnProcessFact> facts = readCustomProcessDefinitions(conn, tableMap, colMap);
            log.info("Custom workflow DB scan: read {} process definitions", facts.size());

            // 2. 读流程节点表 → 填充 FlowNodeFact
            for (var fact : facts) {
                enrichCustomNodes(fact, conn, tableMap, colMap);
            }

            // 3. 读流转配置表 → 填充 SequenceFlowFact
            for (var fact : facts) {
                enrichCustomFlows(fact, conn, tableMap, colMap);
            }

            // 4. 读运行时日志表 → 填充运行时属性(execCount/flowCount)
            for (var fact : facts) {
                enrichCustomRuntime(fact, conn, tableMap, colMap);
            }

            // 5. 构建图谱
            for (var fact : facts) {
                graphBuilder.buildBpmnProcessGraph(
                        context.getProjectId(), context.getVersionId(), fact);
                int nodeSize = fact.getNodes() != null ? fact.getNodes().size() : 0;
                int flowSize = fact.getFlows() != null ? fact.getFlows().size() : 0;
                totalNodes += nodeSize + 1;
                totalEdges += flowSize + nodeSize;
            }

            log.info("Custom workflow DB scan completed: {} processes, {} nodes, {} edges",
                    facts.size(), totalNodes, totalEdges);
            return ExtractionResult.builder()
                    .processedAssets(facts.size())
                    .nodeCount(totalNodes)
                    .edgeCount(totalEdges)
                    .summary("Scanned " + facts.size() + " custom workflow processes")
                    .build();
        } catch (Exception e) {
            log.warn("Custom workflow DB scan failed (non-blocking): {}", e.getMessage());
            return ExtractionResult.builder().processedAssets(0)
                    .summary("Custom workflow DB scan failed: " + e.getMessage()).build();
        }
    }

    /** 读流程定义表 */
    private List<BpmnProcessFact> readCustomProcessDefinitions(Connection conn,
                                                                Map<String, String> tableMap,
                                                                Map<String, String> colMap) throws SQLException {
        String table = tableMap.get("processDefinition");
        if (table == null || table.isBlank()) return List.of();
        String colKey = colMap.getOrDefault("processKey", "proc_key");
        String colName = colMap.getOrDefault("processName", "proc_name");
        String sql = "SELECT " + colKey + ", " + colName + " FROM " + table;

        List<BpmnProcessFact> facts = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String procKey = getStringCI(rs, colKey);
                String procName = getStringCI(rs, colName);
                if (procKey == null || procKey.isBlank()) continue;
                facts.add(BpmnProcessFact.builder()
                        .processKey(procKey)
                        .processName(procName != null ? procName : procKey)
                        .sourcePath("custom-db:" + procKey)
                        .sourceType("DB")
                        .nodes(new ArrayList<>())
                        .flows(new ArrayList<>())
                        .classRefs(new ArrayList<>())
                        .exprRefs(new ArrayList<>())
                        .build());
            }
        }
        return facts;
    }

    /** 读流程节点表 → 填充 FlowNodeFact */
    private void enrichCustomNodes(BpmnProcessFact fact, Connection conn,
                                    Map<String, String> tableMap,
                                    Map<String, String> colMap) throws SQLException {
        String table = tableMap.get("flowNode");
        if (table == null || table.isBlank()) return;
        String colProcKey = colMap.getOrDefault("processKey", "proc_key");
        String colNodeId = colMap.getOrDefault("nodeId", "node_id");
        String colNodeName = colMap.getOrDefault("nodeName", "node_name");
        String colNodeType = colMap.getOrDefault("nodeType", "node_type");
        String sql = "SELECT " + colNodeId + ", " + colNodeName + ", " + colNodeType
                + " FROM " + table + " WHERE " + colProcKey + " = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fact.getProcessKey());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String nodeId = getStringCI(rs, colNodeId);
                    String nodeName = getStringCI(rs, colNodeName);
                    String nodeTypeRaw = getStringCI(rs, colNodeType);
                    if (nodeId == null || nodeId.isBlank()) continue;
                    fact.getNodes().add(BpmnProcessFact.FlowNodeFact.builder()
                            .bpmnId(nodeId)
                            .name(nodeName)
                            .type(mapCustomNodeType(nodeTypeRaw))
                            .build());
                }
            }
        }
    }

    /** 读流转配置表 → 填充 SequenceFlowFact */
    private void enrichCustomFlows(BpmnProcessFact fact, Connection conn,
                                    Map<String, String> tableMap,
                                    Map<String, String> colMap) throws SQLException {
        String table = tableMap.get("sequenceFlow");
        if (table == null || table.isBlank()) return;
        String colProcKey = colMap.getOrDefault("processKey", "proc_key");
        String colFrom = colMap.getOrDefault("sourceNode", "from_node");
        String colTo = colMap.getOrDefault("targetNode", "to_node");
        String colCond = colMap.getOrDefault("condition", "condition_expr");
        String sql = "SELECT " + colFrom + ", " + colTo + ", " + colCond
                + " FROM " + table + " WHERE " + colProcKey + " = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fact.getProcessKey());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String from = getStringCI(rs, colFrom);
                    String to = getStringCI(rs, colTo);
                    String cond = getStringCI(rs, colCond);
                    if (from == null || to == null) continue;
                    fact.getFlows().add(BpmnProcessFact.SequenceFlowFact.builder()
                            .flowId(from + "->" + to)
                            .sourceBpmnId(from)
                            .targetBpmnId(to)
                            .condition(cond)
                            .build());
                }
            }
        }
    }

    /** 读运行时日志表 → 填充节点 execCount + SequenceFlow flowCount */
    private void enrichCustomRuntime(BpmnProcessFact fact, Connection conn,
                                      Map<String, String> tableMap,
                                      Map<String, String> colMap) throws SQLException {
        String table = tableMap.get("runtimeLog");
        if (table == null || table.isBlank()) return;
        String colProcKey = colMap.getOrDefault("processKey", "proc_key");
        String colNode = colMap.getOrDefault("nodeId", "node_id");
        String colFrom = colMap.getOrDefault("sourceNode", "from_node");
        String colTo = colMap.getOrDefault("targetNode", "to_node");

        // 1. 节点执行频次
        String sqlNodes = "SELECT " + colNode + ", COUNT(*) AS cnt FROM " + table
                + " WHERE " + colProcKey + " = ? AND " + colNode + " IS NOT NULL GROUP BY " + colNode;
        Map<String, Long> execCounts = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sqlNodes)) {
            ps.setString(1, fact.getProcessKey());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String node = getStringCI(rs, colNode);
                    long cnt = rs.getLong("cnt");
                    if (node != null) execCounts.put(node, cnt);
                }
            }
        }
        for (var node : fact.getNodes()) {
            Long cnt = execCounts.get(node.getBpmnId());
            if (cnt != null) node.setExecCount(cnt);
        }

        // 2. 路径频次(若日志表有 from/to 字段)
        if (columnExists(conn, table, colFrom) && columnExists(conn, table, colTo)) {
            String sqlFlows = "SELECT " + colFrom + ", " + colTo + ", COUNT(*) AS cnt FROM " + table
                    + " WHERE " + colProcKey + " = ? AND " + colFrom + " IS NOT NULL AND " + colTo + " IS NOT NULL"
                    + " GROUP BY " + colFrom + ", " + colTo;
            Map<String, Long> flowCounts = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(sqlFlows)) {
                ps.setString(1, fact.getProcessKey());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String from = getStringCI(rs, colFrom);
                        String to = getStringCI(rs, colTo);
                        long cnt = rs.getLong("cnt");
                        if (from != null && to != null) {
                            flowCounts.put(from + "->" + to, cnt);
                        }
                    }
                }
            }
            for (var sf : fact.getFlows()) {
                Long cnt = flowCounts.get(sf.getSourceBpmnId() + "->" + sf.getTargetBpmnId());
                if (cnt != null) sf.setFlowCount(cnt);
            }
        }
    }

    /** nodeType 值映射: 配置中约定 1=UserTask, 2=ServiceTask, 3=Gateway */
    private BpmnProcessFact.FlowNodeType mapCustomNodeType(String raw) {
        if (raw == null) return BpmnProcessFact.FlowNodeType.USER_TASK;
        return switch (raw.trim().toLowerCase()) {
            case "1", "usertask", "user" -> BpmnProcessFact.FlowNodeType.USER_TASK;
            case "2", "servicetask", "service" -> BpmnProcessFact.FlowNodeType.SERVICE_TASK;
            case "3", "gateway", "decision" -> BpmnProcessFact.FlowNodeType.GATEWAY;
            default -> BpmnProcessFact.FlowNodeType.USER_TASK;
        };
    }

    /** 创建 JDBC 连接 */
    private Connection createConnection(ProcessEngineConnectionInfo connInfo)
            throws SQLException, ClassNotFoundException {
        if (connInfo.getDriverClassName() != null && !connInfo.getDriverClassName().isBlank()) {
            Class.forName(connInfo.getDriverClassName());
        }
        return DriverManager.getConnection(
                connInfo.getJdbcUrl(),
                connInfo.getUsername(),
                connInfo.getPassword());
    }

    // ==================== 辅助方法 ====================

    /** 大小写不敏感地从 ResultSet 读取字符串列 */
    private String getStringCI(ResultSet rs, String columnName) throws SQLException {
        int idx = findColumnCI(rs, columnName);
        return idx > 0 ? rs.getString(idx) : null;
    }

    /** 大小写不敏感地查找列索引 */
    private int findColumnCI(ResultSet rs, String columnName) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (meta.getColumnLabel(i).equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        return -1;
    }

    /** 检查表是否存在指定列 */
    private boolean columnExists(Connection conn, String table, String column) {
        try (ResultSet rs = conn.getMetaData().getColumns(
                null, null, table.toUpperCase(), column.toUpperCase())) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("CustomWorkflowDbAdapter")
                .languages(Set.of("sql"))
                .fileTypes(Set.of())
                .frameworks(Set.of("custom-workflow"))
                .aiEnhanced(false)
                .priority(67)
                .build();
    }
}
