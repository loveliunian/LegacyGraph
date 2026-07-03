package io.github.legacygraph.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entity 层序列化/反序列化测试。
 * <p>
 * 验证所有核心实体类的 Jackson JSON 序列化/反序列化、
 * {@code @TableName} 注解映射、getter/setter 方法正常工作。
 * 重点覆盖 15 个核心 Entity（与前端 REST API 直接交互）。
 * </p>
 */
class EntitySerializationTest {

    private static ObjectMapper objectMapper;

    @BeforeAll
    static void setUp() {
        objectMapper = new ObjectMapper();
        // 注册 Java 8 时间模块以支持 LocalDateTime 序列化
        objectMapper.registerModule(new JavaTimeModule());
        // 空值不出现在 JSON 中（与项目 Jackson 配置一致）
        // objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    // ========== 通用辅助 ==========

    /** 序列化 → 反序列化 → 断言字段相等 */
    private <T> T roundTrip(T original, Class<T> clazz) throws JsonProcessingException {
        String json = objectMapper.writeValueAsString(original);
        assertThat(json).isNotBlank();
        T restored = objectMapper.readValue(json, clazz);
        assertThat(restored).isNotNull();
        return restored;
    }

    // ========== 1. Project ==========

    @Test
    void test_project_serialization() throws Exception {
        Project entity = new Project();
        entity.setId("proj-uuid-001");
        entity.setProjectCode("LEGACY-APP");
        entity.setProjectName("遗留系统迁移");
        entity.setProjectType("LEGACY");
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(LocalDateTime.of(2026, 1, 15, 10, 30));
        entity.setUpdatedAt(LocalDateTime.of(2026, 1, 15, 10, 30));

        Project restored = roundTrip(entity, Project.class);
        assertThat(restored.getId()).isEqualTo("proj-uuid-001");
        assertThat(restored.getProjectCode()).isEqualTo("LEGACY-APP");
        assertThat(restored.getProjectName()).isEqualTo("遗留系统迁移");

        // @TableName 注解验证
        TableName tableName = Project.class.getAnnotation(TableName.class);
        assertThat(tableName).isNotNull();
        assertThat(tableName.value()).isEqualTo("lg_project");
    }

    // ========== 2. CodeRepo ==========

    @Test
    void test_codeRepo_serialization() throws Exception {
        CodeRepo entity = new CodeRepo();
        entity.setId("repo-uuid-001");
        entity.setProjectId("proj-uuid-001");
        entity.setRepoName("Backend Core");
        entity.setRepoType("GIT");
        entity.setGitUrl("https://git.example.com/backend.git");
        entity.setBranchName("main");
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        CodeRepo restored = roundTrip(entity, CodeRepo.class);
        assertThat(restored.getRepoName()).isEqualTo("Backend Core");
        assertThat(restored.getGitUrl()).isEqualTo("https://git.example.com/backend.git");

        TableName tableName = CodeRepo.class.getAnnotation(TableName.class);
        assertThat(tableName).isNotNull();
        assertThat(tableName.value()).isEqualTo("lg_code_repo");
    }

    // ========== 3. DbConnection ==========

    @Test
    void test_dbConnection_serialization() throws Exception {
        DbConnection entity = new DbConnection();
        entity.setId("conn-uuid-001");
        entity.setProjectId("proj-uuid-001");
        entity.setConnectionName("生产数据库");
        entity.setDbType("MYSQL");
        entity.setHost("192.168.1.50");
        entity.setPort(3306);
        entity.setDatabaseName("legacy_erp");
        entity.setStatus("CONNECTED");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        DbConnection restored = roundTrip(entity, DbConnection.class);
        assertThat(restored.getDbType()).isEqualTo("MYSQL");
        assertThat(restored.getPort()).isEqualTo(3306);

        TableName tableName = DbConnection.class.getAnnotation(TableName.class);
        assertThat(tableName.value()).isEqualTo("lg_db_connection");
    }

    // ========== 4. Document ==========

    @Test
    void test_document_serialization() throws Exception {
        Document entity = new Document();
        entity.setId("doc-uuid-001");
        entity.setProjectId("proj-uuid-001");
        entity.setDocName("架构设计文档.pdf");
        entity.setDocType("设计文档");
        entity.setFileType("PDF");
        entity.setFileSize(2048000L);
        entity.setParseStatus("COMPLETED");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        Document restored = roundTrip(entity, Document.class);
        assertThat(restored.getDocName()).isEqualTo("架构设计文档.pdf");
        assertThat(restored.getFileSize()).isEqualTo(2048000L);

        TableName tableName = Document.class.getAnnotation(TableName.class);
        assertThat(tableName.value()).isEqualTo("lg_document");
    }

    // ========== 5. ScanVersion ==========

    @Test
    void test_scanVersion_serialization() throws Exception {
        ScanVersion entity = new ScanVersion();
        entity.setId("sv-uuid-001");
        entity.setProjectId("proj-uuid-001");
        entity.setVersionNo("v3.0.0");
        entity.setBranchName("release");
        entity.setCommitId("abc123def456");
        entity.setScanStatus("COMPLETED");
        entity.setNodeCount(5000L);
        entity.setEdgeCount(12000L);
        entity.setFactCount(800L);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        ScanVersion restored = roundTrip(entity, ScanVersion.class);
        assertThat(restored.getVersionNo()).isEqualTo("v3.0.0");
        assertThat(restored.getCommitId()).isEqualTo("abc123def456");

        TableName tableName = ScanVersion.class.getAnnotation(TableName.class);
        assertThat(tableName.value()).isEqualTo("lg_scan_version");
    }

    // ========== 6. ScanTask ==========

    @Test
    void test_scanTask_serialization() throws Exception {
        ScanTask entity = new ScanTask();
        entity.setId("st-uuid-001");
        entity.setProjectId("proj-uuid-001");
        entity.setVersionId("sv-uuid-001");
        entity.setTaskType("DOC_PARSE");
        entity.setTaskName("文档解析任务");
        entity.setTaskStatus("COMPLETED");
        entity.setRetryCount(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        ScanTask restored = roundTrip(entity, ScanTask.class);
        assertThat(restored.getTaskType()).isEqualTo("DOC_PARSE");
        assertThat(restored.getTaskName()).isEqualTo("文档解析任务");

        TableName tableName = ScanTask.class.getAnnotation(TableName.class);
        assertThat(tableName.value()).isEqualTo("lg_scan_task");
    }

    // ========== 7. GraphNode ==========

    @Test
    void test_graphNode_serialization() throws Exception {
        GraphNode entity = new GraphNode();
        entity.setId("node-uuid-001");
        entity.setProjectId("proj-uuid-001");
        entity.setVersionId("sv-uuid-001");
        entity.setNodeType("METHOD");
        entity.setNodeKey("com.example.OrderService.calculateTotal");
        entity.setNodeName("calculateTotal");
        entity.setSourceType("CODE");
        entity.setSourcePath("/src/main/java/com/example/OrderService.java");
        entity.setStartLine(45);
        entity.setEndLine(78);
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        GraphNode restored = roundTrip(entity, GraphNode.class);
        assertThat(restored.getNodeType()).isEqualTo("METHOD");
        assertThat(restored.getNodeName()).isEqualTo("calculateTotal");
        assertThat(restored.getStartLine()).isEqualTo(45);

        TableName tableName = GraphNode.class.getAnnotation(TableName.class);
        assertThat(tableName.value()).isEqualTo("lg_graph_node");
    }

    // ========== 8. GraphEdge ==========

    @Test
    void test_graphEdge_serialization() throws Exception {
        GraphEdge entity = new GraphEdge();
        entity.setId("edge-uuid-001");
        entity.setProjectId("proj-uuid-001");
        entity.setVersionId("sv-uuid-001");
        entity.setFromNodeId("node-src-001");
        entity.setToNodeId("node-dst-002");
        entity.setEdgeType("INVOKES");
        entity.setEdgeKey("invokes-service-method");
        entity.setSourceType("CODE");
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        GraphEdge restored = roundTrip(entity, GraphEdge.class);
        assertThat(restored.getEdgeType()).isEqualTo("INVOKES");
        assertThat(restored.getFromNodeId()).isEqualTo("node-src-001");
        assertThat(restored.getToNodeId()).isEqualTo("node-dst-002");

        TableName tableName = GraphEdge.class.getAnnotation(TableName.class);
        assertThat(tableName.value()).isEqualTo("lg_graph_edge");
    }

    // ========== 9. Fact ==========

    @Test
    void test_fact_serialization() throws Exception {
        Fact entity = new Fact();
        entity.setId("fact-uuid-001");
        entity.setProjectId("proj-uuid-001");
        entity.setVersionId("sv-uuid-001");
        entity.setFactType("BUSINESS_RULE");
        entity.setFactKey("rule-tax-calc");
        entity.setFactName("税费计算规则");
        entity.setContentSummary("根据订单金额和地区计算税费");
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        Fact restored = roundTrip(entity, Fact.class);
        assertThat(restored.getFactType()).isEqualTo("BUSINESS_RULE");
        assertThat(restored.getFactName()).isEqualTo("税费计算规则");

        TableName tableName = Fact.class.getAnnotation(TableName.class);
        assertThat(tableName.value()).isEqualTo("lg_fact");
    }

    // ========== 10. Evidence ==========

    @Test
    void test_evidence_serialization() throws Exception {
        Evidence entity = new Evidence();
        entity.setId("ev-uuid-001");
        entity.setProjectId("proj-uuid-001");
        entity.setVersionId("sv-uuid-001");
        entity.setEvidenceType("SQL");
        entity.setSourcePath("database/procedures/sp_calc_tax.sql");
        entity.setSourceName("sp_calc_tax.sql");
        entity.setStartLine(1);
        entity.setEndLine(120);
        entity.setContentHash("md5-hash-example");
        entity.setPrivacyLevel("INTERNAL");
        entity.setCreatedAt(LocalDateTime.now());

        Evidence restored = roundTrip(entity, Evidence.class);
        assertThat(restored.getEvidenceType()).isEqualTo("SQL");
        assertThat(restored.getSourceName()).isEqualTo("sp_calc_tax.sql");

        TableName tableName = Evidence.class.getAnnotation(TableName.class);
        assertThat(tableName.value()).isEqualTo("lg_evidence");
    }

    // ========== 11. TestCase ==========

    @Test
    void test_testCase_serialization() throws Exception {
        TestCase entity = new TestCase();
        entity.setId("tc-uuid-001");
        entity.setProjectId("proj-uuid-001");
        entity.setVersionId("sv-uuid-001");
        entity.setCaseCode("TC-ORDER-001");
        entity.setCaseName("订单创建流程测试");
        entity.setCaseType("API");
        entity.setPriority("HIGH");
        entity.setStatus("DRAFT");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        TestCase restored = roundTrip(entity, TestCase.class);
        assertThat(restored.getCaseCode()).isEqualTo("TC-ORDER-001");
        assertThat(restored.getPriority()).isEqualTo("HIGH");

        TableName tableName = TestCase.class.getAnnotation(TableName.class);
        assertThat(tableName.value()).isEqualTo("lg_test_case");
    }

    // ========== 12. TestResult ==========

    @Test
    void test_testResult_serialization() throws Exception {
        TestResult entity = new TestResult();
        entity.setId("tr-uuid-001");
        entity.setProjectId("proj-uuid-001");
        entity.setVersionId("sv-uuid-001");
        entity.setTestCaseId("tc-uuid-001");
        entity.setExecutionId("exec-001");
        entity.setResultStatus("PASSED");
        entity.setDurationMs(230L);
        entity.setExecutedAt(LocalDateTime.now());

        TestResult restored = roundTrip(entity, TestResult.class);
        assertThat(restored.getResultStatus()).isEqualTo("PASSED");
        assertThat(restored.getDurationMs()).isEqualTo(230L);

        TableName tableName = TestResult.class.getAnnotation(TableName.class);
        assertThat(tableName.value()).isEqualTo("lg_test_result");
    }

    // ========== 13. TestRun ==========

    @Test
    void test_testRun_serialization() throws Exception {
        TestRun entity = new TestRun();
        entity.setId("trun-uuid-001");
        entity.setProjectId("proj-uuid-001");
        entity.setVersionId("sv-uuid-001");
        entity.setEnvironment("STAGING");
        entity.setStatus("FINISHED");
        entity.setTotalCases(120);
        entity.setPassedCases(115);
        entity.setFailedCases(5);
        entity.setStartedAt(LocalDateTime.now().minusHours(1));
        entity.setFinishedAt(LocalDateTime.now());

        TestRun restored = roundTrip(entity, TestRun.class);
        assertThat(restored.getEnvironment()).isEqualTo("STAGING");
        assertThat(restored.getTotalCases()).isEqualTo(120);

        TableName tableName = TestRun.class.getAnnotation(TableName.class);
        assertThat(tableName.value()).isEqualTo("lg_test_run");
    }

    // ========== 14. ReviewRecord ==========

    @Test
    void test_reviewRecord_serialization() throws Exception {
        ReviewRecord entity = new ReviewRecord();
        entity.setId("rr-uuid-001");
        entity.setProjectId("proj-uuid-001");
        entity.setVersionId("sv-uuid-001");
        entity.setTargetType("FACT");
        entity.setTargetId("fact-uuid-001");
        entity.setTargetName("税费计算规则");
        entity.setPriority("HIGH");
        entity.setStatus("PENDING");
        entity.setCreatedAt(LocalDateTime.now());

        ReviewRecord restored = roundTrip(entity, ReviewRecord.class);
        assertThat(restored.getTargetType()).isEqualTo("FACT");
        assertThat(restored.getTargetName()).isEqualTo("税费计算规则");

        TableName tableName = ReviewRecord.class.getAnnotation(TableName.class);
        assertThat(tableName.value()).isEqualTo("lg_review_record");
    }

    // ========== 15. AuditLog ==========

    @Test
    void test_auditLog_serialization() throws Exception {
        AuditLog entity = new AuditLog();
        entity.setId(1L);
        entity.setTraceId("trace-xyz-789");
        entity.setOperation("DELETE_PROJECT");
        entity.setMethod("ProjectController.delete");
        entity.setRequestUri("/api/lg/projects/proj-001");
        entity.setRequestMethod("DELETE");
        entity.setClientIp("10.0.0.55");
        entity.setOperatorId("user-admin");
        entity.setOperatorName("系统管理员");
        entity.setStatus("SUCCESS");
        entity.setDurationMs(45L);
        entity.setCreatedAt(LocalDateTime.now());

        AuditLog restored = roundTrip(entity, AuditLog.class);
        assertThat(restored.getOperation()).isEqualTo("DELETE_PROJECT");
        assertThat(restored.getStatus()).isEqualTo("SUCCESS");

        TableName tableName = AuditLog.class.getAnnotation(TableName.class);
        assertThat(tableName.value()).isEqualTo("lg_sys_operation_log");
    }

    // ========== 补充：验证 getter/setter 字段完整性 ==========

    @Test
    void test_project_getter_setter_complete() {
        Project entity = new Project();
        // 验证 setter 可用（Lombok @Data 生成）
        entity.setId("id-123");
        entity.setProjectCode("CODEX");
        entity.setDeleted(0);

        // 验证 getter 返回值一致
        assertThat(entity.getId()).isEqualTo("id-123");
        assertThat(entity.getProjectCode()).isEqualTo("CODEX");
        assertThat(entity.getDeleted()).isEqualTo(0);
    }

    @Test
    void test_auditLog_getter_setter_complete() {
        AuditLog entity = new AuditLog();
        entity.setId(999L);
        entity.setTraceId("trace-test");
        entity.setOperation("TEST");
        entity.setCreatedAt(LocalDateTime.now());

        assertThat(entity.getId()).isEqualTo(999L);
        assertThat(entity.getTraceId()).isEqualTo("trace-test");
    }

    @Test
    void test_sysUser_getter_setter_complete() {
        SysUser entity = new SysUser();
        entity.setUsername("zhangsan");
        entity.setNickname("张三");
        entity.setEmail("zhangsan@example.com");

        assertThat(entity.getUsername()).isEqualTo("zhangsan");
        assertThat(entity.getNickname()).isEqualTo("张三");
    }

    @Test
    void test_promptTemplate_getter_setter_complete() {
        PromptTemplate entity = new PromptTemplate();
        entity.setId(1L);
        entity.setTemplateCode("TEST_TEMPLATE");
        entity.setIsActive(true);

        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getTemplateCode()).isEqualTo("TEST_TEMPLATE");
        assertThat(entity.getIsActive()).isTrue();
    }
}
