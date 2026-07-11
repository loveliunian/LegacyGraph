package io.github.legacygraph.service.solution;

import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.dto.requirement.ImpactNode;
import io.github.legacygraph.dto.requirement.ImpactResult;
import io.github.legacygraph.dto.solution.SolutionPlan;
import io.github.legacygraph.dto.solution.SolutionPlanStep;
import io.github.legacygraph.dto.solution.SolutionVerificationResult;
import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link SolutionVerifier} 单元测试（mock Neo4jGraphDao）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SolutionVerifierTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    private SolutionVerifier verifier;

    private static final String PROJECT_ID = "proj-001";
    private static final String VERSION_ID = "ver-001";

    @BeforeEach
    void setUp() {
        verifier = new SolutionVerifier(neo4jGraphDao);
        // 默认：executeReadQuery 返回非空（即文件/符号存在），避免误判
        when(neo4jGraphDao.executeReadQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("id", "node-id")));
        // 默认：queryNodes 返回空列表
        when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(),
                any(), any(), anyInt()))
                .thenReturn(List.of());
        // 默认：findNodeById 返回空（节点不存在）
        when(neo4jGraphDao.findNodeById(anyString()))
                .thenReturn(Optional.empty());
    }

    private SolutionPlan buildPlan() {
        SolutionPlan plan = new SolutionPlan();
        plan.setSummary("方案总览");
        // 成本估算（满足 G7 校验）
        SolutionPlan.CostEstimate cost = new SolutionPlan.CostEstimate();
        cost.setPersonDays(3.0);
        cost.setAffectedFiles(2);
        cost.setComplexity("MEDIUM");
        plan.setEstimatedCost(cost);
        // 风险评估（满足 G7 校验）
        SolutionPlan.RiskAssessment risk = new SolutionPlan.RiskAssessment();
        risk.setRiskLevel("LOW");
        risk.setHighRiskAreas(List.of());
        risk.setMitigations(List.of("充分测试"));
        plan.setRiskAssessment(risk);
        plan.setSteps(List.of(
                buildStep("OrderMapper#selectRecentOrders",
                        "backend/src/main/java/io/github/legacygraph/mapper/OrderMapper.java",
                        "MODIFY", "ev-001")));
        return plan;
    }

    private SolutionPlanStep buildStep(String symbol, String filePath,
                                        String actionType, String... evidenceIds) {
        SolutionPlanStep step = new SolutionPlanStep();
        step.setTitle("步骤");
        step.setDescription("描述");
        step.setSymbolName(symbol);
        step.setFilePath(filePath);
        step.setActionType(actionType);
        step.setTestDescription("测试描述");
        step.setRollbackDescription("回滚描述");
        step.setEvidenceIds(evidenceIds.length == 0
                ? List.of()
                : java.util.Arrays.asList(evidenceIds));
        return step;
    }

    private RequirementAnalysis buildAnalysisNoOpenQuestions() {
        RequirementAnalysis analysis = new RequirementAnalysis();
        analysis.setGoal("goal");
        analysis.setOpenQuestions(List.of());
        return analysis;
    }

    private ImpactResult buildImpactWithDirectNode(String nodeId, String nodeName) {
        ImpactResult result = new ImpactResult();
        result.setImpactedNodes(List.of(
                new ImpactNode(nodeId, "key:" + nodeName, nodeName, "Table", 0, "DIRECT")));
        return result;
    }

    // ==================== happy path ====================

    @Test
    void verify_allChecksPass_returnsPassed() {
        SolutionPlan plan = buildPlan();
        // 添加 codeSnippet（class OrderMapper 匹配 impactedNodes）
        plan.getSteps().get(0).setCodeSnippet("public class OrderMapper { /* fields */ }");
        // 让符号存在（queryNodes 返回非空）+ 高风险节点覆盖（nodeName 匹配）
        GraphNode matched = new GraphNode();
        matched.setNodeName("OrderMapper");
        matched.setSourcePath("backend/src/main/java/io/github/legacygraph/mapper/OrderMapper.java");
        when(neo4jGraphDao.queryNodes(eq(PROJECT_ID), eq(VERSION_ID), any(), any(),
                any(), any(), any(), anyInt()))
                .thenReturn(List.of(matched));
        // 让 Evidence 存在
        GraphNode evidence = new GraphNode();
        evidence.setNodeType(NodeType.Evidence.name());
        when(neo4jGraphDao.findNodeById("ev-001"))
                .thenReturn(Optional.of(evidence));
        // 高风险节点的 sourcePath 匹配步骤 filePath
        GraphNode highRiskNode = new GraphNode();
        highRiskNode.setSourcePath("backend/src/main/java/io/github/legacygraph/mapper/OrderMapper.java");
        highRiskNode.setNodeName("OrderMapper");
        when(neo4jGraphDao.findNodeById("n1"))
                .thenReturn(Optional.of(highRiskNode));

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                buildImpactWithDirectNode("n1", "OrderMapper"),
                plan);

        assertTrue(result.isPassed());
        assertEquals(SolutionVerifier.STATUS_PASSED, result.getStatus());
        assertTrue(result.getErrors().isEmpty());
    }

    // ==================== 空方案 ====================

    @Test
    void verify_nullPlan_returnsFailed() {
        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), null);

        assertFalse(result.isPassed());
        assertEquals(SolutionVerifier.STATUS_FAILED, result.getStatus());
        assertFalse(result.getErrors().isEmpty());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getMessage().contains("方案步骤为空")));
    }

    @Test
    void verify_emptySteps_returnsFailed() {
        SolutionPlan plan = new SolutionPlan();
        plan.setSteps(List.of());

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), plan);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getMessage().contains("方案步骤为空")));
    }

    // ==================== 测试覆盖 ====================

    @Test
    void verify_missingTestDescription_addsError() {
        SolutionPlan plan = buildPlan();
        plan.getSteps().get(0).setTestDescription("");

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), plan);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getMessage().contains("testDescription")));
    }

    @Test
    void verify_nullTestDescription_addsError() {
        SolutionPlan plan = buildPlan();
        plan.getSteps().get(0).setTestDescription(null);

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), plan);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getMessage().contains("testDescription")));
    }

    // ==================== 文件存在 ====================

    @Test
    void verify_modifyAction_fileNotInGraph_addsError() {
        SolutionPlan plan = buildPlan();
        // executeReadQuery 返回空 → 文件不存在
        when(neo4jGraphDao.executeReadQuery(anyString(), anyMap()))
                .thenReturn(List.of());

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), plan);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getMessage().contains("文件路径")
                && e.getMessage().contains("MODIFY/DELETE")));
    }

    @Test
    void verify_createAction_fileNotExist_skipsFileCheck() {
        SolutionPlanStep createStep = buildStep(
                "NewController#newMethod",
                "backend/src/main/java/io/github/legacygraph/controller/NewController.java",
                "CREATE");
        createStep.setEvidenceIds(new java.util.ArrayList<>());
        createStep.setCodeSnippet("public class NewController { }");
        SolutionPlan plan = new SolutionPlan();
        plan.setSummary("s");
        plan.setSteps(List.of(createStep));
        // 满足 G7 校验（成本估算 + 风险评估）
        SolutionPlan.CostEstimate cost = new SolutionPlan.CostEstimate();
        cost.setPersonDays(1.0);
        cost.setAffectedFiles(1);
        cost.setComplexity("LOW");
        plan.setEstimatedCost(cost);
        SolutionPlan.RiskAssessment risk = new SolutionPlan.RiskAssessment();
        risk.setRiskLevel("LOW");
        risk.setHighRiskAreas(List.of());
        risk.setMitigations(List.of("新建文件无破坏性变更"));
        plan.setRiskAssessment(risk);

        // executeReadQuery 返回空 → 文件不存在
        when(neo4jGraphDao.executeReadQuery(anyString(), anyMap()))
                .thenReturn(List.of());

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), plan);

        // CREATE 步骤：文件与符号都不存在也不报错（其它校验全过）
        assertTrue(result.isPassed(), "errors: " + result.getErrors());
    }

    // ==================== 符号存在 ====================

    @Test
    void verify_modifyAction_symbolNotInGraph_addsError() {
        SolutionPlan plan = buildPlan();
        // executeReadQuery 返回空 → 符号不存在
        when(neo4jGraphDao.executeReadQuery(anyString(), anyMap()))
                .thenReturn(List.of());
        // queryNodes 也返回空 → 符号不存在
        when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(),
                any(), any(), anyInt()))
                .thenReturn(List.of());

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), plan);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getMessage().contains("符号")
                && e.getMessage().contains("OrderMapper")));
    }

    @Test
    void verify_modifyAction_symbolExists_passesSymbolCheck() {
        SolutionPlan plan = buildPlan();
        // 文件存在
        when(neo4jGraphDao.executeReadQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("id", "node-1")));
        // 符号存在（queryNodes 返回非空，或 executeReadQuery 返回非空）
        GraphNode symbolNode = new GraphNode();
        symbolNode.setNodeName("OrderMapper");
        when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(),
                any(), any(), anyInt()))
                .thenReturn(List.of(symbolNode));

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), plan);

        // 不应有符号相关的错误
        assertTrue(result.getErrors().stream().noneMatch(e -> e.getMessage().contains("符号")));
    }

    // ==================== 高风险覆盖 ====================

    @Test
    void verify_highRiskNodeNotCovered_addsError() {
        SolutionPlan plan = buildPlan();
        // 高风险节点：nodeName=OrderController（步骤覆盖的是 OrderMapper，不匹配）
        ImpactResult impact = buildImpactWithDirectNode("n1", "OrderController");
        // 节点的 sourcePath 查询返回不匹配步骤 filePath 的路径
        GraphNode highRiskNode = new GraphNode();
        highRiskNode.setSourcePath("other/path/OrderController.java");
        highRiskNode.setNodeName("OrderController");
        when(neo4jGraphDao.findNodeById("n1"))
                .thenReturn(Optional.of(highRiskNode));

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                impact, plan);

        assertFalse(result.isPassed());
        // 实际错误信息为 "高风险节点未被方案步骤覆盖：OrderController（key:OrderController）"
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getMessage().contains("高风险节点未被方案步骤覆盖")
                && e.getMessage().contains("OrderController")));
    }

    @Test
    void verify_highRiskNodeCoveredBySymbol_passes() {
        SolutionPlan plan = buildPlan();
        // 高风险节点 nodeName 与步骤 symbolName 类名相同
        ImpactResult impact = buildImpactWithDirectNode("n1", "OrderMapper");
        GraphNode highRiskNode = new GraphNode();
        highRiskNode.setSourcePath("other/path/OrderMapper.java"); // 不匹配步骤 filePath
        highRiskNode.setNodeName("OrderMapper"); // 但 nodeName 匹配
        when(neo4jGraphDao.findNodeById("n1"))
                .thenReturn(Optional.of(highRiskNode));

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                impact, plan);

        // 不应有高风险覆盖错误（其它校验可能过 / 可能不过，但不应有覆盖错误）
        assertTrue(result.getErrors().stream().noneMatch(e -> e.getMessage().contains("高风险节点未被方案步骤覆盖")));
    }

    @Test
    void verify_highRiskNodeCoveredByFilePath_passes() {
        SolutionPlan plan = buildPlan();
        ImpactResult impact = buildImpactWithDirectNode("n1", "OrderMapper");
        // 节点的 sourcePath 与步骤 filePath 一致
        GraphNode highRiskNode = new GraphNode();
        highRiskNode.setSourcePath("backend/src/main/java/io/github/legacygraph/mapper/OrderMapper.java");
        highRiskNode.setNodeName("OrderMapper");
        when(neo4jGraphDao.findNodeById("n1"))
                .thenReturn(Optional.of(highRiskNode));

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                impact, plan);

        assertTrue(result.getErrors().stream().noneMatch(e -> e.getMessage().contains("高风险节点未被方案步骤覆盖")));
    }

    @Test
    void verify_emptyImpact_skipsHighRiskCheck() {
        SolutionPlan plan = buildPlan();

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), plan);

        // 无影响节点，跳过高风险覆盖检查
        assertTrue(result.getErrors().stream().noneMatch(e -> e.getMessage().contains("高风险节点未被方案步骤覆盖")));
    }

    // ==================== 证据有效 ====================

    @Test
    void verify_evidenceNotInGraph_addsError() {
        SolutionPlan plan = buildPlan();
        // 文件 + 符号都存在
        when(neo4jGraphDao.executeReadQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("id", "n")));
        GraphNode symbolNode = new GraphNode();
        symbolNode.setNodeName("OrderMapper");
        // 符号存在（按 nodeName 匹配）；Evidence 查询返回空
        when(neo4jGraphDao.queryNodes(eq(PROJECT_ID), eq(VERSION_ID), eq(NodeType.Evidence.name()),
                any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq(PROJECT_ID), eq(VERSION_ID), isNull(),
                any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(symbolNode));
        // Evidence 不存在（findNodeById 返回空 / 非 Evidence 类型）
        when(neo4jGraphDao.findNodeById("ev-001"))
                .thenReturn(Optional.empty());

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), plan);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getMessage().contains("证据")
                && e.getMessage().contains("ev-001")));
    }

    @Test
    void verify_emptyEvidenceIds_skipsEvidenceCheck() {
        SolutionPlanStep step = buildStep("OrderMapper#m", "/path", "MODIFY");
        step.setEvidenceIds(new java.util.ArrayList<>());
        SolutionPlan plan = new SolutionPlan();
        plan.setSummary("s");
        plan.setSteps(List.of(step));

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), plan);

        // 无证据引用，跳过证据检查
        assertTrue(result.getErrors().stream().noneMatch(e -> e.getMessage().contains("证据")));
    }

    // ==================== 阻塞问题 ====================

    @Test
    void verify_openQuestionsNonEmpty_addsError() {
        RequirementAnalysis analysis = new RequirementAnalysis();
        analysis.setOpenQuestions(List.of("数据来源未明确"));

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                analysis, new ImpactResult(), buildPlan());

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.getMessage().contains("开放问题")));
    }

    @Test
    void verify_nullOpenQuestions_skipsBlockingCheck() {
        RequirementAnalysis analysis = new RequirementAnalysis();
        analysis.setOpenQuestions(null);

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                analysis, new ImpactResult(), buildPlan());

        // openQuestions 为 null 时，跳过阻塞检查（不报错）
        assertTrue(result.getErrors().stream().noneMatch(e -> e.getMessage().contains("开放问题")));
    }

    // ==================== parseClassName 辅助方法 ====================

    @Test
    void parseClassName_simpleName_returnsAsIs() {
        assertEquals("OrderMapper", verifier.parseClassName("OrderMapper"));
    }

    @Test
    void parseClassName_withMethod_returnsClassName() {
        assertEquals("OrderMapper", verifier.parseClassName("OrderMapper#selectRecentOrders"));
    }

    @Test
    void parseClassName_fqn_returnsLastSegment() {
        assertEquals("OrderMapper",
                verifier.parseClassName("com.example.mapper.OrderMapper#selectRecentOrders"));
    }

    @Test
    void parseClassName_null_returnsNull() {
        assertNull(verifier.parseClassName(null));
    }

    // ==================== 查询失败容错 ====================

    @Test
    void verify_graphQueryThrows_doesNotCrash() {
        SolutionPlan plan = buildPlan();
        // 添加 codeSnippet（空 impactedNodes 时跳过一致性校验）
        plan.getSteps().get(0).setCodeSnippet("public class OrderMapper { /* modified */ }");
        when(neo4jGraphDao.executeReadQuery(anyString(), anyMap()))
                .thenThrow(new RuntimeException("Neo4j 不可用"));
        when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(),
                any(), any(), anyInt()))
                .thenThrow(new RuntimeException("Neo4j 不可用"));
        when(neo4jGraphDao.findNodeById(anyString()))
                .thenThrow(new RuntimeException("Neo4j 不可用"));

        // 查询失败时不应抛异常，且不应误判为失败（按"存在"处理）
        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), plan);

        // 容错：查询失败时认为文件/符号/证据都存在，只剩测试覆盖和阻塞问题校验
        // buildPlan 的 testDescription 非空，openQuestions 为空 → 应通过
        assertTrue(result.isPassed(), "errors: " + result.getErrors());
    }

    // ==================== 代码片段一致性校验 ====================

    @Test
    void testCheckCodeSnippet_Modify_EmptySnippet_AddsError() {
        SolutionPlanStep step = buildStep("OrderMapper#selectRecentOrders",
                "backend/src/main/java/io/github/legacygraph/mapper/OrderMapper.java",
                "MODIFY");
        step.setCodeSnippet("");
        SolutionPlan plan = new SolutionPlan();
        plan.setSummary("s");
        plan.setSteps(List.of(step));

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), plan);

        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "CODE_SNIPPET_EMPTY".equals(e.getCode())));
    }

    @Test
    void testCheckCodeSnippet_Create_EmptySnippet_AddsError() {
        SolutionPlanStep step = buildStep("NewController#newMethod",
                "backend/src/main/java/io/github/legacygraph/controller/NewController.java",
                "CREATE");
        step.setEvidenceIds(new java.util.ArrayList<>());
        step.setCodeSnippet(null);
        SolutionPlan plan = new SolutionPlan();
        plan.setSummary("s");
        plan.setSteps(List.of(step));

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), plan);

        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "CODE_SNIPPET_EMPTY".equals(e.getCode())));
    }

    @Test
    void testCheckCodeSnippet_Delete_NoSnippet_NoError() {
        SolutionPlanStep step = buildStep("OrderMapper#deleteMethod",
                "backend/src/main/java/io/github/legacygraph/mapper/OrderMapper.java",
                "DELETE");
        // DELETE 不设置 codeSnippet
        SolutionPlan plan = new SolutionPlan();
        plan.setSummary("s");
        plan.setSteps(List.of(step));

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                new ImpactResult(), plan);

        assertTrue(result.getErrors().stream()
                .noneMatch(e -> e.getCode() != null && e.getCode().startsWith("CODE_SNIPPET")));
    }

    @Test
    void testCheckCodeSnippet_Modify_ConsistentNames_NoError() {
        SolutionPlanStep step = buildStep("OrderMapper#selectRecentOrders",
                "backend/src/main/java/io/github/legacygraph/mapper/OrderMapper.java",
                "MODIFY");
        step.setCodeSnippet("public class OrderMapper {\n" +
                "    public List<Order> selectRecentOrders() {\n" +
                "        return null;\n" +
                "    }\n" +
                "}");
        SolutionPlan plan = new SolutionPlan();
        plan.setSummary("s");
        plan.setSteps(List.of(step));

        // impactedNodes 包含代码片段中所有提取的名称
        ImpactResult impact = new ImpactResult();
        impact.setImpactedNodes(List.of(
                new ImpactNode("n1", "key:OrderMapper", "OrderMapper", "Class", 0, "DIRECT"),
                new ImpactNode("n2", "key:selectRecentOrders", "selectRecentOrders", "Method", 1, "CALLS")));

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                impact, plan);

        assertTrue(result.getErrors().stream()
                .noneMatch(e -> "CODE_SNIPPET_INCONSISTENT".equals(e.getCode())
                        || "CODE_SNIPPET_EMPTY".equals(e.getCode())),
                "errors: " + result.getErrors());
    }

    @Test
    void testCheckCodeSnippet_Modify_InconsistentNames_AddsError() {
        SolutionPlanStep step = buildStep("OrderMapper#selectRecentOrders",
                "backend/src/main/java/io/github/legacygraph/mapper/OrderMapper.java",
                "MODIFY");
        step.setCodeSnippet("public class UnknownService {\n" +
                "    public void doSomething() { }\n" +
                "}");
        SolutionPlan plan = new SolutionPlan();
        plan.setSummary("s");
        plan.setSteps(List.of(step));

        // impactedNodes 仅包含 OrderMapper，不包含代码片段中的 UnknownService/doSomething
        ImpactResult impact = new ImpactResult();
        impact.setImpactedNodes(List.of(
                new ImpactNode("n1", "key:OrderMapper", "OrderMapper", "Class", 0, "DIRECT")));

        SolutionVerificationResult result = verifier.verify(
                PROJECT_ID, VERSION_ID,
                buildAnalysisNoOpenQuestions(),
                impact, plan);

        assertTrue(result.getErrors().stream()
                .anyMatch(e -> "CODE_SNIPPET_INCONSISTENT".equals(e.getCode())));
    }
}
