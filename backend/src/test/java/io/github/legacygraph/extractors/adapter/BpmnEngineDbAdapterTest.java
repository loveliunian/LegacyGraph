package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.extractors.bpmn.BpmnProcessFact;
import io.github.legacygraph.extractors.bpmn.EngineType;
import io.github.legacygraph.extractors.bpmn.ProcessEngineConnectionInfo;
import io.github.legacygraph.extractors.bpmn.ProcessRuntimeAnalyzer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * BpmnEngineDbAdapter 单元测试。
 * 使用 H2 内存库初始化 act_ 表,Mock GraphBuilder/Neo4jGraphDao,验证 DB 扫描逻辑。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BpmnEngineDbAdapterTest {

    private Connection h2Conn;
    private String h2Url;

    @Mock
    private GraphBuilder graphBuilder;

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    private ProcessRuntimeAnalyzer runtimeAnalyzer;
    private BpmnEngineDbAdapter adapter;

    /** 最小化请假流程 BPMN XML,含 UserTask + ServiceTask + Gateway + SequenceFlow */
    private static final String BPMN_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                         targetNamespace="http://test">
              <process id="leaveProcess" name="请假流程" isExecutable="true">
                <startEvent id="start"/>
                <userTask id="approve" name="审批" camunda:assignee="${manager}"/>
                <serviceTask id="auto" name="自动审批"
                             camunda:expression="${leaveService.approve(task)}"/>
                <exclusiveGateway id="gw" name="判断"/>
                <endEvent id="end"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                <sequenceFlow id="f2" sourceRef="approve" targetRef="gw"/>
                <sequenceFlow id="f3" sourceRef="gw" targetRef="auto">
                  <conditionExpression>${approved}</conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="f4" sourceRef="gw" targetRef="end"/>
                <sequenceFlow id="f5" sourceRef="auto" targetRef="end"/>
              </process>
            </definitions>
            """;

    @BeforeEach
    void setUp() throws Exception {
        // 初始化 H2 内存库(每个测试方法独立数据库,避免跨方法表残留)
        h2Url = "jdbc:h2:mem:bpmn_test_" + System.nanoTime() + ";MODE=MySQL";
        h2Conn = DriverManager.getConnection(h2Url, "sa", "");
        try (Statement st = h2Conn.createStatement()) {
            st.execute("CREATE TABLE act_re_procdef (" +
                    "ID_ VARCHAR(64) PRIMARY KEY," +
                    "KEY_ VARCHAR(255)," +
                    "NAME_ VARCHAR(255)," +
                    "VERSION_ INT," +
                    "DEPLOYMENT_ID_ VARCHAR(64))");
            st.execute("CREATE TABLE act_ge_bytearray (" +
                    "ID_ VARCHAR(64) PRIMARY KEY," +
                    "DEPLOYMENT_ID_ VARCHAR(64)," +
                    "NAME_ VARCHAR(255)," +
                    "BYTES_ BLOB)");
            st.execute("CREATE TABLE act_hi_taskinst (" +
                    "ID_ VARCHAR(64) PRIMARY KEY," +
                    "PROC_DEF_ID_ VARCHAR(64)," +
                    "PROC_INST_ID_ VARCHAR(64)," +
                    "TASK_DEF_KEY_ VARCHAR(255)," +
                    "NAME_ VARCHAR(255)," +
                    "START_TIME_ TIMESTAMP," +
                    "END_TIME_ TIMESTAMP)");
            st.execute("CREATE TABLE act_hi_procinst (" +
                    "ID_ VARCHAR(64) PRIMARY KEY," +
                    "PROC_DEF_ID_ VARCHAR(64)," +
                    "START_TIME_ TIMESTAMP," +
                    "END_TIME_ TIMESTAMP)");
        }
        runtimeAnalyzer = new ProcessRuntimeAnalyzer(neo4jGraphDao);
        adapter = new BpmnEngineDbAdapter(new io.github.legacygraph.extractors.bpmn.BpmnModelParser(),
                runtimeAnalyzer, graphBuilder);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (h2Conn != null) h2Conn.close();
    }

    /** 构造 ScanContext,带流程引擎连接信息 */
    private ScanContext buildContext(ProcessEngineConnectionInfo connInfo) {
        Map<String, Object> config = new HashMap<>();
        if (connInfo != null) config.put("processEngine.db", connInfo);
        return ScanContext.builder()
                .projectId("test-proj")
                .versionId("test-ver")
                .config(config)
                .build();
    }

    private ProcessEngineConnectionInfo buildConnInfo(String jdbcUrl) {
        return ProcessEngineConnectionInfo.builder()
                .engineType(EngineType.FLOWABLE)
                .jdbcUrl(jdbcUrl)
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .tablePrefix("act_")
                .build();
    }

    @Test
    void scanFromDatabase_skipsWhenNoConnInfo() {
        ScanContext ctx = ScanContext.builder()
                .projectId("p").versionId("v").config(new HashMap<>()).build();
        ExtractionResult result = adapter.scanFromDatabase(ctx);
        assertEquals(0, result.getProcessedAssets());
        verifyNoInteractions(graphBuilder);
    }

    @Test
    void scanFromDatabase_skipsEncryptedPassword() {
        ProcessEngineConnectionInfo connInfo = ProcessEngineConnectionInfo.builder()
                .engineType(EngineType.FLOWABLE)
                .jdbcUrl("jdbc:h2:mem:test")
                .username("sa")
                .password("ENC(secret)")
                .encryptedSkipped(true)
                .tablePrefix("act_")
                .build();
        ExtractionResult result = adapter.scanFromDatabase(buildContext(connInfo));
        assertEquals(0, result.getProcessedAssets());
        assertFalse(connInfo.isConnectable());
    }

    @Test
    void scanFromDatabase_skipsCustomEngine() {
        ProcessEngineConnectionInfo connInfo = ProcessEngineConnectionInfo.builder()
                .engineType(EngineType.CUSTOM)
                .jdbcUrl("jdbc:h2:mem:test")
                .username("sa")
                .password("")
                .tablePrefix(null)
                .build();
        ExtractionResult result = adapter.scanFromDatabase(buildContext(connInfo));
        assertEquals(0, result.getProcessedAssets());
    }

    @Test
    void scanFromDatabase_readsDeployedProcessDefinitions() throws Exception {
        // 插入 1 条流程定义 + 对应 BPMN XML
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "INSERT INTO act_re_procdef (ID_, KEY_, NAME_, VERSION_, DEPLOYMENT_ID_) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, "leaveProcess:1:10001");
            ps.setString(2, "leaveProcess");
            ps.setString(3, "请假流程");
            ps.setInt(4, 1);
            ps.setString(5, "dep1");
            ps.executeUpdate();
        }
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "INSERT INTO act_ge_bytearray (ID_, DEPLOYMENT_ID_, NAME_, BYTES_) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, "ba1");
            ps.setString(2, "dep1");
            ps.setString(3, "leave.bpmn");
            ps.setBytes(4, BPMN_XML.getBytes());
            ps.executeUpdate();
        }

        // Mock: graphBuilder.buildBpmnProcessGraph 被调用时不做任何事
        // Mock: Neo4j 查询返回空列表(避免 NPE)
        when(neo4jGraphDao.queryNodes(anyString(), anyString(), anyString(), any(), any(), any(), anyInt()))
                .thenReturn(java.util.List.of());
        when(neo4jGraphDao.queryEdges(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(java.util.List.of());

        ExtractionResult result = adapter.scanFromDatabase(buildContext(buildConnInfo(h2Url)));
        assertEquals(1, result.getProcessedAssets());
        assertTrue(result.getNodeCount() > 0);
        verify(graphBuilder, atLeastOnce()).buildBpmnProcessGraph(anyString(), anyString(), any());
    }

    @Test
    void scanFromDatabase_enrichesRuntimeData() throws Exception {
        // 插入流程定义
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "INSERT INTO act_re_procdef (ID_, KEY_, NAME_, VERSION_, DEPLOYMENT_ID_) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, "leaveProcess:1:10001");
            ps.setString(2, "leaveProcess");
            ps.setString(3, "请假流程");
            ps.setInt(4, 1);
            ps.setString(5, "dep1");
            ps.executeUpdate();
        }
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "INSERT INTO act_ge_bytearray (ID_, DEPLOYMENT_ID_, NAME_, BYTES_) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, "ba1");
            ps.setString(2, "dep1");
            ps.setString(3, "leave.bpmn");
            ps.setBytes(4, BPMN_XML.getBytes());
            ps.executeUpdate();
        }
        // 插入 3 条历史任务,approve 节点执行 3 次
        try (PreparedStatement ps = h2Conn.prepareStatement(
                "INSERT INTO act_hi_taskinst (ID_, PROC_DEF_ID_, PROC_INST_ID_, TASK_DEF_KEY_, NAME_, START_TIME_, END_TIME_) " +
                        "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            for (int i = 1; i <= 3; i++) {
                ps.setString(1, "task" + i);
                ps.setString(2, "leaveProcess:1:10001");
                ps.setString(3, "procInst" + i);
                ps.setString(4, "approve");
                ps.setString(5, "审批");
                ps.addBatch();
            }
            ps.executeBatch();
        }

        when(neo4jGraphDao.queryNodes(anyString(), anyString(), anyString(), any(), any(), any(), anyInt()))
                .thenReturn(java.util.List.of());
        when(neo4jGraphDao.queryEdges(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(java.util.List.of());

        // 使用 ArgumentCaptor 捕获传给 graphBuilder 的 fact
        org.mockito.ArgumentCaptor<BpmnProcessFact> captor =
                org.mockito.ArgumentCaptor.forClass(BpmnProcessFact.class);
        adapter.scanFromDatabase(buildContext(buildConnInfo(h2Url)));
        verify(graphBuilder, atLeastOnce()).buildBpmnProcessGraph(anyString(), anyString(), captor.capture());

        BpmnProcessFact fact = captor.getValue();
        // approve 节点应有 execCount >= 3
        BpmnProcessFact.FlowNodeFact approveNode = fact.getNodes().stream()
                .filter(n -> "approve".equals(n.getBpmnId()))
                .findFirst().orElse(null);
        assertNotNull(approveNode);
        assertTrue(approveNode.getExecCount() >= 3, "approve node execCount should be >= 3");
    }

    @Test
    void scanFromDatabase_failsGracefully() {
        // 故意给错误 jdbcUrl
        ProcessEngineConnectionInfo connInfo = buildConnInfo("jdbc:h2:mem:nonexistent_db_xyz");
        ExtractionResult result = adapter.scanFromDatabase(buildContext(connInfo));
        assertEquals(0, result.getProcessedAssets());
        assertNotNull(result.getSummary());
        // 不抛异常
    }

    @Test
    void supports_alwaysReturnsFalse() {
        ScanContext ctx = ScanContext.builder().config(new HashMap<>()).build();
        SourceAsset asset = SourceAsset.builder().relativePath("test.bpmn").build();
        assertFalse(adapter.supports(ctx, asset));
    }

    @Test
    void capability_returnsCorrectInfo() {
        AdapterCapability cap = adapter.capability();
        assertEquals("BpmnEngineDbAdapter", cap.getName());
        assertEquals(Set.of("sql"), cap.getLanguages());
        assertTrue(cap.getFrameworks().contains("flowable"));
        assertTrue(cap.getFrameworks().contains("activiti"));
        assertTrue(cap.getFrameworks().contains("camunda"));
        assertFalse(cap.isAiEnhanced());
        assertEquals(66, cap.getPriority());
    }
}
