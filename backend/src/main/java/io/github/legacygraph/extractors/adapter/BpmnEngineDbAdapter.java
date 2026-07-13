package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.bpmn.BpmnModelParser;
import io.github.legacygraph.extractors.bpmn.BpmnProcessFact;
import io.github.legacygraph.extractors.bpmn.EngineType;
import io.github.legacygraph.extractors.bpmn.ProcessEngineConnectionInfo;
import io.github.legacygraph.extractors.bpmn.ProcessRuntimeAnalyzer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 标准 BPMN 引擎数据库适配器 — 连接 Flowable/Activiti/Camunda 引擎库(act_ 前缀),
 * 读取已部署的流程定义(BPMN XML) + 运行时数据(节点频次/时长/路径)。
 * <p>
 * 不通过 {@link #supports(ScanContext, SourceAsset)} 触发(DB 源无对应文件),
 * 而是由 ProjectScanner 在 ADAPTER_SCAN 完成后主动调用 {@link #scanFromDatabase(ScanContext)}。
 * </p>
 * <p>
 * 失败隔离:DB 扫描失败仅 log.warn,不阻塞后续 AI_ORCHESTRATION 阶段
 * (遵循项目 memory 中 "External verification failures must not block subsequent scanning steps" 原则)。
 * </p>
 */
@Slf4j
@Component
public class BpmnEngineDbAdapter implements ExtractionAdapter {

    private final BpmnModelParser parser;
    private final ProcessRuntimeAnalyzer runtimeAnalyzer;
    private final GraphBuilder graphBuilder;

    public BpmnEngineDbAdapter(BpmnModelParser parser,
                                ProcessRuntimeAnalyzer runtimeAnalyzer,
                                GraphBuilder graphBuilder) {
        this.parser = parser;
        this.runtimeAnalyzer = runtimeAnalyzer;
        this.graphBuilder = graphBuilder;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        return false; // 不通过 SourceAsset 触发,由 ProjectScanner 主动调用 scanFromDatabase
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        return ExtractionResult.builder().processedAssets(0).build();
    }

    /**
     * 从流程引擎数据库扫描已部署的流程定义 + 运行时数据。
     * 由 ProjectScanner 在 ADAPTER_SCAN 完成后主动调用。
     * 失败不阻塞,仅 log.warn。
     *
     * @param context 扫描上下文,config 中需含 key="processEngine.db" 的连接信息
     * @return 扫描结果
     */
    public ExtractionResult scanFromDatabase(ScanContext context) {
        ProcessEngineConnectionInfo connInfo =
                (ProcessEngineConnectionInfo) context.getConfig().get("processEngine.db");
        if (connInfo == null || !connInfo.isConnectable()
                || connInfo.getEngineType() == EngineType.CUSTOM) {
            return ExtractionResult.builder().processedAssets(0).summary("BPMN DB scan skipped").build();
        }

        int totalNodes = 0, totalEdges = 0, processCount = 0;
        try (Connection conn = createConnection(connInfo)) {
            // 1. 读取已部署的流程定义 + BPMN XML
            List<BpmnProcessFact> facts = readDeployedProcessDefinitions(conn, connInfo.getTablePrefix());
            processCount = facts.size();
            log.info("BPMN DB scan: read {} deployed process definitions", processCount);

            // 2. 运行时数据增强(节点频次/时长/驳回率/路径频次)
            runtimeAnalyzer.enrichWithRuntimeData(facts, conn, connInfo.getTablePrefix());

            // 3. 构建图谱
            for (BpmnProcessFact fact : facts) {
                graphBuilder.buildBpmnProcessGraph(
                        context.getProjectId(), context.getVersionId(), fact);
                int nodeSize = fact.getNodes() != null ? fact.getNodes().size() : 0;
                int flowSize = fact.getFlows() != null ? fact.getFlows().size() : 0;
                totalNodes += nodeSize + 1; // +1 for ProcessDefinition
                totalEdges += flowSize + nodeSize; // FLOW_TO + HAS_FLOW_NODE
            }

            // 4. 构建运行时流转边 RUNTIME_FLOW_TO (动态流转/驳回路径)
            int runtimeEdges = runtimeAnalyzer.buildRuntimeFlowEdges(
                    context.getProjectId(), context.getVersionId(),
                    conn, connInfo.getTablePrefix(), graphBuilder);
            totalEdges += runtimeEdges;

            log.info("BPMN DB scan completed: {} processes, {} nodes, {} edges (runtime edges: {})",
                    processCount, totalNodes, totalEdges, runtimeEdges);
            return ExtractionResult.builder()
                    .processedAssets(processCount)
                    .nodeCount(totalNodes)
                    .edgeCount(totalEdges)
                    .summary("Scanned " + processCount + " BPMN processes from DB")
                    .build();
        } catch (Exception e) {
            log.warn("BPMN DB scan failed (non-blocking): {}", e.getMessage());
            return ExtractionResult.builder()
                    .processedAssets(0)
                    .summary("BPMN DB scan failed: " + e.getMessage())
                    .build();
        }
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

    /**
     * 读取已部署的流程定义及对应的 BPMN XML 二进制。
     * <p>
     * 表结构(Flowable/Activiti/Camunda 共用,act_ 前缀):
     * <ul>
     *   <li>{@code act_re_procdef}:流程定义表(ID_, KEY_, NAME_, VERSION_, DEPLOYMENT_ID_)</li>
     *   <li>{@code act_ge_bytearray}:通用字节表(DEPLOYMENT_ID_, NAME_, BYTES_)</li>
     * </ul>
     * </p>
     */
    private List<BpmnProcessFact> readDeployedProcessDefinitions(Connection conn, String prefix) throws SQLException {
        String sql = "SELECT p.ID_, p.KEY_, p.NAME_, p.VERSION_, p.DEPLOYMENT_ID_, b.BYTES_ " +
                     "FROM " + prefix + "RE_PROCDEF p " +
                     "JOIN " + prefix + "GE_BYTEARRAY b ON p.DEPLOYMENT_ID_ = b.DEPLOYMENT_ID_ " +
                     "WHERE b.NAME_ LIKE '%.bpmn' OR b.NAME_ LIKE '%.bpmn20.xml'";

        List<BpmnProcessFact> facts = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String procDefId = getStringCI(rs, "ID_");
                String procKey = getStringCI(rs, "KEY_");
                String procName = getStringCI(rs, "NAME_");
                int version = getIntCI(rs, "VERSION_");
                String deploymentId = getStringCI(rs, "DEPLOYMENT_ID_");
                byte[] bytes = getBytesCI(rs, "BYTES_");

                if (bytes == null || bytes.length == 0) continue;
                BpmnProcessFact fact = parser.parseFromStream(
                        new ByteArrayInputStream(bytes), "db:" + procDefId);
                if (fact == null) continue;
                fact.setVersion(version);
                fact.setDeploymentId(deploymentId);
                if (procKey != null && !procKey.isBlank()) fact.setProcessKey(procKey);
                if (procName != null && !procName.isBlank()) fact.setProcessName(procName);
                facts.add(fact);
            }
        }
        return facts;
    }

    // ==================== ResultSet 大小写不敏感读取辅助 ====================

    /** 大小写不敏感地从 ResultSet 读取字符串列 */
    private String getStringCI(ResultSet rs, String columnName) throws SQLException {
        int idx = findColumnCI(rs, columnName);
        return idx > 0 ? rs.getString(idx) : null;
    }

    /** 大小写不敏感地从 ResultSet 读取 int 列 */
    private int getIntCI(ResultSet rs, String columnName) throws SQLException {
        int idx = findColumnCI(rs, columnName);
        return idx > 0 ? rs.getInt(idx) : 0;
    }

    /** 大小写不敏感地从 ResultSet 读取 byte[] 列 */
    private byte[] getBytesCI(ResultSet rs, String columnName) throws SQLException {
        int idx = findColumnCI(rs, columnName);
        return idx > 0 ? rs.getBytes(idx) : null;
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

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("BpmnEngineDbAdapter")
                .languages(Set.of("sql"))
                .fileTypes(Set.of())
                .frameworks(Set.of("flowable", "activiti", "camunda"))
                .aiEnhanced(false)
                .priority(66)
                .build();
    }
}
