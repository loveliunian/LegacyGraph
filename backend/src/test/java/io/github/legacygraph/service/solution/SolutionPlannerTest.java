package io.github.legacygraph.service.solution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.dto.RequirementItemDTO;
import io.github.legacygraph.dto.requirement.ImpactNode;
import io.github.legacygraph.dto.requirement.ImpactResult;
import io.github.legacygraph.dto.solution.SolutionPlan;
import io.github.legacygraph.dto.solution.SolutionPlanStep;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
 * {@link SolutionPlanner} 单元测试（mock LlmGateway / Neo4jGraphDao）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SolutionPlannerTest {

    @Mock
    private LlmGateway llmGateway;

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SolutionPlanner planner;

    private static final String PROJECT_ID = "proj-001";
    private static final String VERSION_ID = "ver-001";
    private static final String TEMPLATE = "solution-planning";

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        planner = new SolutionPlanner(llmGateway, neo4jGraphDao, objectMapper);
        // 默认：图谱查询返回空列表
        when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
    }

    private RequirementAnalysis buildAnalysis() {
        RequirementAnalysis analysis = new RequirementAnalysis();
        analysis.setGoal("支持按最近30天导出t_order订单数据为Excel");
        RequirementItemDTO item = new RequirementItemDTO();
        item.setCode("R1");
        item.setText("系统将t_order表最近30天的订单导出为Excel");
        item.setAcceptanceCriteria(List.of("导出文件为Excel格式"));
        analysis.setItems(List.of(item));
        analysis.setOpenQuestions(List.of());
        return analysis;
    }

    private ImpactResult buildImpact() {
        ImpactResult result = new ImpactResult();
        result.setImpactedNodes(List.of(
                new ImpactNode("n1", "public.t_order", "t_order", "Table", 0, "DIRECT")));
        return result;
    }

    private SolutionPlan buildPlan() {
        SolutionPlan plan = new SolutionPlan();
        plan.setSummary("在 OrderExportService 新增 exportLast30Days 方法");
        SolutionPlanStep step = new SolutionPlanStep();
        step.setTitle("新增 Mapper 查询方法");
        step.setDescription("在 OrderMapper 新增 selectRecentOrders 方法");
        step.setFilePath("backend/src/main/java/io/github/legacygraph/mapper/OrderMapper.java");
        step.setSymbolName("OrderMapper#selectRecentOrders");
        step.setEvidenceIds(List.of("ev-001"));
        step.setActionType("MODIFY");
        step.setTestDescription("Mapper 单测：调用 selectRecentOrders 返回近 30 天订单");
        step.setRollbackDescription("删除新增方法");
        plan.setSteps(List.of(step));
        return plan;
    }

    @Test
    void plan_happyPath_returnsPlan() {
        RequirementAnalysis analysis = buildAnalysis();
        ImpactResult impact = buildImpact();
        SolutionPlan expected = buildPlan();
        when(llmGateway.callWithTemplate(eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(SolutionPlan.class)))
                .thenReturn(expected);

        SolutionPlan actual = planner.plan(PROJECT_ID, VERSION_ID, analysis, impact);

        assertNotNull(actual);
        assertEquals("在 OrderExportService 新增 exportLast30Days 方法", actual.getSummary());
        assertEquals(1, actual.getSteps().size());
        SolutionPlanStep step = actual.getSteps().get(0);
        assertEquals("OrderMapper#selectRecentOrders", step.getSymbolName());
        assertEquals("MODIFY", step.getActionType());
        assertEquals(1, step.getEvidenceIds().size());

        // 验证 LlmGateway 调用参数：模板名 + 5 个变量
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(llmGateway).callWithTemplate(
                eq(PROJECT_ID), eq(TEMPLATE), captor.capture(), eq(SolutionPlan.class));
        Map<String, String> vars = captor.getValue();
        assertEquals(5, vars.size());
        assertTrue(vars.containsKey("requirementAnalysis"));
        assertTrue(vars.containsKey("impactResult"));
        assertTrue(vars.containsKey("impactSignatures"));
        assertTrue(vars.containsKey("conventions"));
        assertTrue(vars.containsKey("reusableComponents"));
        assertTrue(vars.get("requirementAnalysis").contains("支持按最近30天导出"));
        assertTrue(vars.get("impactResult").contains("public.t_order"));
    }

    @Test
    void plan_nullAnalysis_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(PROJECT_ID, VERSION_ID, null, buildImpact()));
        verifyNoInteractions(llmGateway);
    }

    @Test
    void plan_nullImpactResult_usesEmptyImpact() {
        RequirementAnalysis analysis = buildAnalysis();
        SolutionPlan expected = buildPlan();
        when(llmGateway.callWithTemplate(eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(SolutionPlan.class)))
                .thenReturn(expected);

        SolutionPlan actual = planner.plan(PROJECT_ID, VERSION_ID, analysis, null);

        assertNotNull(actual);
        verify(llmGateway).callWithTemplate(
                eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(SolutionPlan.class));
    }

    @Test
    void plan_llmReturnsNull_fillsEmptyDefaults() {
        when(llmGateway.callWithTemplate(eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(SolutionPlan.class)))
                .thenReturn(null);

        SolutionPlan actual = planner.plan(PROJECT_ID, VERSION_ID, buildAnalysis(), buildImpact());

        assertNotNull(actual);
        assertNotNull(actual.getSteps());
        assertTrue(actual.getSteps().isEmpty());
    }

    @Test
    void plan_llmReturnsNullSteps_fillsEmptySteps() {
        SolutionPlan partial = new SolutionPlan();
        partial.setSummary("summary");
        // steps 为 null
        when(llmGateway.callWithTemplate(eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(SolutionPlan.class)))
                .thenReturn(partial);

        SolutionPlan actual = planner.plan(PROJECT_ID, VERSION_ID, buildAnalysis(), buildImpact());

        assertNotNull(actual.getSteps());
        assertTrue(actual.getSteps().isEmpty());
    }

    @Test
    void plan_llmReturnsNullEvidenceIds_fillsEmptyList() {
        SolutionPlan partial = newPlanWithNullEvidenceIds();
        when(llmGateway.callWithTemplate(eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(SolutionPlan.class)))
                .thenReturn(partial);

        SolutionPlan actual = planner.plan(PROJECT_ID, VERSION_ID, buildAnalysis(), buildImpact());

        assertEquals(1, actual.getSteps().size());
        assertNotNull(actual.getSteps().get(0).getEvidenceIds());
        assertTrue(actual.getSteps().get(0).getEvidenceIds().isEmpty());
    }

    private SolutionPlan newPlanWithNullEvidenceIds() {
        SolutionPlan plan = new SolutionPlan();
        plan.setSummary("s");
        SolutionPlanStep step = new SolutionPlanStep();
        step.setTitle("t");
        // evidenceIds 为 null
        plan.setSteps(List.of(step));
        return plan;
    }

    @Test
    void plan_llmFailure_propagatesException() {
        when(llmGateway.callWithTemplate(eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(SolutionPlan.class)))
                .thenThrow(new RuntimeException("LLM 调用失败"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> planner.plan(PROJECT_ID, VERSION_ID, buildAnalysis(), buildImpact()));
        assertTrue(ex.getMessage().contains("LLM 调用失败"));
    }

    @Test
    void plan_loadsConventionsFromGraph() {
        GraphNode controller = new GraphNode();
        controller.setNodeName("OrderController");
        GraphNode service = new GraphNode();
        service.setNodeName("OrderService");
        // 第一次调用 loadConventions：Controller
        when(neo4jGraphDao.queryNodes(eq(PROJECT_ID), eq(VERSION_ID), eq("Controller"),
                any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(controller));
        when(neo4jGraphDao.queryNodes(eq(PROJECT_ID), eq(VERSION_ID), eq("Service"),
                any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(service));

        when(llmGateway.callWithTemplate(eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(SolutionPlan.class)))
                .thenReturn(buildPlan());

        planner.plan(PROJECT_ID, VERSION_ID, buildAnalysis(), buildImpact());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(llmGateway).callWithTemplate(
                eq(PROJECT_ID), eq(TEMPLATE), captor.capture(), eq(SolutionPlan.class));
        String conventions = captor.getValue().get("conventions");
        assertTrue(conventions.contains("OrderController"));
        assertTrue(conventions.contains("OrderService"));
    }

    @Test
    void plan_loadsReusableComponents() {
        GraphNode reusable = new GraphNode();
        reusable.setNodeName("FileDownloader");
        reusable.setSourcePath("util/FileDownloader.java");
        reusable.setProperties("{\"reusable\":true,\"reuseType\":\"UTIL\",\"usageCount\":5}");
        when(neo4jGraphDao.queryNodes(eq(PROJECT_ID), eq(VERSION_ID), isNull(),
                any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(reusable));

        when(llmGateway.callWithTemplate(eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(SolutionPlan.class)))
                .thenReturn(buildPlan());

        planner.plan(PROJECT_ID, VERSION_ID, buildAnalysis(), buildImpact());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(llmGateway).callWithTemplate(
                eq(PROJECT_ID), eq(TEMPLATE), captor.capture(), eq(SolutionPlan.class));
        String reusableJson = captor.getValue().get("reusableComponents");
        assertTrue(reusableJson.contains("FileDownloader"));
        assertTrue(reusableJson.contains("util/FileDownloader.java"));
        assertTrue(reusableJson.contains("reuseType"));
    }

    @Test
    void plan_graphQueryThrows_returnsEmptyConventionsNotCrash() {
        when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("Neo4j 不可用"));

        when(llmGateway.callWithTemplate(eq(PROJECT_ID), eq(TEMPLATE), anyMap(), eq(SolutionPlan.class)))
                .thenReturn(buildPlan());

        SolutionPlan actual = planner.plan(PROJECT_ID, VERSION_ID, buildAnalysis(), buildImpact());

        assertNotNull(actual);
        // conventions / reusableComponents 退化为空对象/空数组
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(llmGateway).callWithTemplate(
                eq(PROJECT_ID), eq(TEMPLATE), captor.capture(), eq(SolutionPlan.class));
        assertEquals("{}", captor.getValue().get("conventions"));
        assertEquals("[]", captor.getValue().get("reusableComponents"));
    }

    // ==================== loadImpactSignatures 测试 ====================

    @Test
    void testLoadImpactSignatures_HappyPath() {
        ImpactResult impact = new ImpactResult();
        impact.setImpactedNodes(List.of(
                new ImpactNode("n1", "public.t_order", "t_order", "Table", 0, "DIRECT")));
        GraphNode node = new GraphNode();
        node.setNodeKey("public.t_order");
        node.setNodeName("t_order");
        node.setNodeType("Table");
        node.setProperties("{\"signature\":\"CREATE TABLE t_order (...)\"}");
        when(neo4jGraphDao.findNodeById("n1")).thenReturn(Optional.of(node));

        String result = planner.loadImpactSignatures(impact);

        assertTrue(result.contains("public.t_order"));
        assertTrue(result.contains("t_order"));
        assertTrue(result.contains("Table"));
        assertTrue(result.contains("CREATE TABLE t_order"));
        assertTrue(result.contains("signature"));
    }

    @Test
    void testLoadImpactSignatures_EmptyImpactResult() {
        ImpactResult impact = new ImpactResult();
        impact.setImpactedNodes(List.of());

        String result = planner.loadImpactSignatures(impact);

        assertEquals("[]", result);
    }

    @Test
    void testLoadImpactSignatures_Neo4jException() {
        ImpactResult impact = new ImpactResult();
        impact.setImpactedNodes(List.of(
                new ImpactNode("n1", "public.t_order", "t_order", "Table", 0, "DIRECT")));
        when(neo4jGraphDao.findNodeById("n1")).thenThrow(new RuntimeException("Neo4j 不可用"));

        String result = planner.loadImpactSignatures(impact);

        assertEquals("[]", result);
    }
}
