package io.github.legacygraph.extractors.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.controller.RequirementController;
import io.github.legacygraph.controller.SolutionController;
import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.dto.RequirementItemDTO;
import io.github.legacygraph.dto.requirement.ImpactResult;
import io.github.legacygraph.dto.requirement.LinkedTarget;
import io.github.legacygraph.dto.solution.SolutionPlan;
import io.github.legacygraph.dto.solution.SolutionPlanStep;
import io.github.legacygraph.dto.solution.SolutionVerificationResult;
import io.github.legacygraph.entity.AcceptanceCriterion;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.entity.Requirement;
import io.github.legacygraph.entity.RequirementItem;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.entity.Solution;
import io.github.legacygraph.entity.SolutionStep;
import io.github.legacygraph.repository.AcceptanceCriterionRepository;
import io.github.legacygraph.repository.RequirementItemRepository;
import io.github.legacygraph.repository.RequirementRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import io.github.legacygraph.repository.SolutionRepository;
import io.github.legacygraph.repository.SolutionStepRepository;
import io.github.legacygraph.service.change.ChangeTaskService;
import io.github.legacygraph.service.requirement.AcceptanceVerificationService;
import io.github.legacygraph.service.requirement.ContractGeneratorService;
import io.github.legacygraph.service.requirement.ImpactSubgraphService;
import io.github.legacygraph.service.requirement.RequirementDataLineageService;
import io.github.legacygraph.service.requirement.RequirementExtractionService;
import io.github.legacygraph.service.requirement.RequirementGraphBuilder;
import io.github.legacygraph.service.requirement.RequirementLinkingService;
import io.github.legacygraph.service.requirement.RequirementPatchService;
import io.github.legacygraph.service.solution.SolutionPlanner;
import io.github.legacygraph.service.solution.SolutionRepairAdvisor;
import io.github.legacygraph.service.solution.SolutionReviewService;
import io.github.legacygraph.service.solution.SolutionToChangeTaskBridge;
import io.github.legacygraph.service.solution.SolutionVerifier;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeast;

/**
 * 需求→方案→验证链路集成测试（Task 13.1 补充）。
 *
 * <p>覆盖 FullPipelineIntegrationTest 未覆盖的需求→方案→验证链路：
 * <ol>
 *   <li>需求保存：RequirementController.save → LLM 抽取 → 持久化 → 图谱构建</li>
 *   <li>需求澄清：RequirementController.clarify → 合并回答 → 重新抽取 → 更新</li>
 *   <li>方案生成+验证：SolutionController.generate → 规划 → 持久化 → verify → 状态变更</li>
 *   <li>方案桥接：SolutionController.bridge → SolutionToChangeTaskBridge → ChangeTask</li>
 * </ol>
 *
 * <p>使用纯 Mockito，不加载 Spring 上下文，mock 所有外部依赖（LLM、Neo4j、Repository）。</p>
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("需求→方案→验证链路集成测试")
class RequirementToSolutionIntegrationTest {

    // ===== RequirementController 依赖 =====
    @Mock private RequirementExtractionService extractionService;
    @Mock private RequirementGraphBuilder graphBuilder;
    @Mock private RequirementLinkingService linkingService;
    @Mock private ImpactSubgraphService impactService;
    @Mock private RequirementRepository requirementRepository;
    @Mock private RequirementItemRepository itemRepository;
    @Mock private AcceptanceCriterionRepository criterionRepository;
    @Mock private ScanVersionRepository scanVersionRepository;

    // ===== SolutionController 依赖 =====
    @Mock private SolutionPlanner planner;
    @Mock private SolutionVerifier verifier;
    @Mock private SolutionRepairAdvisor repairAdvisor;
    @Mock private SolutionRepository solutionRepository;
    @Mock private SolutionStepRepository stepRepository;
    @Mock private SolutionToChangeTaskBridge bridgeService;
    @Mock private SolutionReviewService reviewService;

    private ObjectMapper objectMapper;
    private RequirementController requirementController;
    private SolutionController solutionController;

    private static final String PROJECT_ID = "project-test";
    private static final String VERSION_ID = "version-001";

    @BeforeAll
    static void initLambdaCache() {
        // 纯 Mockito 测试中需手动注册 TableInfo，否则 LambdaQueryWrapper 解析方法引用会失败
        Configuration config = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, ""), Requirement.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, ""), RequirementItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, ""), AcceptanceCriterion.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, ""), ScanVersion.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, ""), Solution.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, ""), SolutionStep.class);
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        requirementController = new RequirementController(
                extractionService, graphBuilder, linkingService, impactService,
                mock(AcceptanceVerificationService.class),
                mock(RequirementPatchService.class),
                requirementRepository, itemRepository, criterionRepository,
                scanVersionRepository, objectMapper,
                mock(RequirementDataLineageService.class),
                mock(ContractGeneratorService.class));
        solutionController = new SolutionController(
                planner, verifier, repairAdvisor, linkingService, impactService,
                solutionRepository, stepRepository, requirementRepository,
                itemRepository, criterionRepository, scanVersionRepository,
                bridgeService, reviewService, objectMapper);

        // 通用 mock：insert 时自动设置 ID（模拟 MyBatis-Plus 的 ASSIGN_UUID 行为）
        lenient().when(requirementRepository.insert(any(Requirement.class))).thenAnswer(inv -> {
            Requirement r = inv.getArgument(0);
            if (r.getId() == null) r.setId("req-generated-id");
            return 1;
        });
        lenient().when(itemRepository.insert(any(RequirementItem.class))).thenAnswer(inv -> {
            RequirementItem i = inv.getArgument(0);
            if (i.getId() == null) i.setId("item-generated-id");
            return 1;
        });
        lenient().when(solutionRepository.insert(any(Solution.class))).thenAnswer(inv -> {
            Solution s = inv.getArgument(0);
            if (s.getId() == null) s.setId("sol-generated-id");
            return 1;
        });
        lenient().when(stepRepository.insert(any(SolutionStep.class))).thenReturn(1);
        lenient().when(criterionRepository.insert(any(AcceptanceCriterion.class))).thenReturn(1);
    }

    // ============================================================
    // 测试用例
    // ============================================================

    /**
     * 测试 1：需求保存链路 — analyze → extract → persist → graph build
     */
    @Test
    @DisplayName("1. 需求保存 — LLM 抽取 → 持久化 Requirement+Item+Criterion → 图谱构建")
    void test01_requirementSave_flow() {
        // 准备 mock
        RequirementAnalysis analysis = buildSampleAnalysis();
        when(extractionService.extract(eq(PROJECT_ID), anyString())).thenReturn(analysis);

        ScanVersion version = new ScanVersion();
        version.setId(VERSION_ID);
        when(scanVersionRepository.selectOne(any())).thenReturn(version);

        RequirementGraphBuilder.BuildResult buildResult =
                new RequirementGraphBuilder.BuildResult(List.of("node-1", "node-2"), 2);
        when(graphBuilder.build(eq(PROJECT_ID), eq(VERSION_ID), anyString(), any())).thenReturn(buildResult);

        // 调用
        RequirementController.RequirementRequest request = new RequirementController.RequirementRequest();
        request.setText("实现订单创建功能，包含库存校验");

        Result<RequirementController.RequirementResponse> result =
                requirementController.save(PROJECT_ID, request);

        // 验证
        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getData().getRequirementId()).isNotNull();
        assertThat(result.getData().getAnalysis().getGoal()).isEqualTo("实现订单创建功能");
        assertThat(result.getData().getItemCount()).isEqualTo(2);
        assertThat(result.getData().getCreatedNodeIds()).hasSize(2);

        // 验证持久化调用
        verify(requirementRepository).insert(any(Requirement.class));
        verify(itemRepository, atLeast(1)).insert(any(RequirementItem.class));
        verify(criterionRepository, atLeast(1)).insert(any(AcceptanceCriterion.class));
        verify(graphBuilder).build(eq(PROJECT_ID), eq(VERSION_ID), anyString(), eq(analysis));
    }

    /**
     * 测试 2：需求澄清链路 — clarify → 合并回答 → 重新抽取 → 更新需求
     */
    @Test
    @DisplayName("2. 需求澄清 — 合并 openQuestion 回答 → 重新 LLM 抽取 → 更新需求")
    void test02_requirementClarify_flow() {
        // 准备已保存的原需求
        Requirement req = new Requirement();
        req.setId("req-existing");
        req.setProjectId(PROJECT_ID);
        req.setText("原始需求文本");
        req.setGoal("原始目标");
        when(requirementRepository.selectById("req-existing")).thenReturn(req);

        // 重新抽取后的分析结果（openQuestions 已清空）
        RequirementAnalysis updatedAnalysis = new RequirementAnalysis();
        updatedAnalysis.setGoal("更新后的目标");
        RequirementItemDTO item = new RequirementItemDTO();
        item.setCode("R1");
        item.setText("更新后的条目");
        item.setAcceptanceCriteria(List.of("验收标准1"));
        updatedAnalysis.setItems(new ArrayList<>(List.of(item)));
        updatedAnalysis.setOpenQuestions(new ArrayList<>());
        when(extractionService.extract(eq(PROJECT_ID), anyString())).thenReturn(updatedAnalysis);

        ScanVersion version = new ScanVersion();
        version.setId(VERSION_ID);
        when(scanVersionRepository.selectOne(any())).thenReturn(version);

        // 无旧条目
        when(itemRepository.selectList(any())).thenReturn(List.of());

        RequirementGraphBuilder.BuildResult buildResult =
                new RequirementGraphBuilder.BuildResult(List.of("node-updated"), 1);
        when(graphBuilder.build(eq(PROJECT_ID), eq(VERSION_ID), eq("req-existing"), any())).thenReturn(buildResult);

        // 调用
        RequirementController.ClarifyRequest clarifyRequest = new RequirementController.ClarifyRequest();
        clarifyRequest.setAnswers(Map.of("订单状态有哪些？", "待支付、已支付、已发货、已完成"));

        Result<RequirementController.RequirementResponse> result =
                requirementController.clarify(PROJECT_ID, "req-existing", clarifyRequest);

        // 验证
        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getData().getRequirementId()).isEqualTo("req-existing");
        assertThat(result.getData().getAnalysis().getGoal()).isEqualTo("更新后的目标");
        assertThat(result.getData().getAnalysis().getOpenQuestions()).isEmpty();

        // 验证需求被更新
        verify(requirementRepository).updateById(any(Requirement.class));
        // 验证旧条目被删除
        verify(itemRepository).delete(any());
    }

    /**
     * 测试 3：方案生成 + 验证链路 — generate → plan → persist → verify → status 变更
     */
    @Test
    @DisplayName("3. 方案生成+验证 — LLM 规划 → 持久化 → 8类校验 → 状态变更")
    void test03_solutionGenerateAndVerify_flow() {
        // 准备已保存的需求
        Requirement req = new Requirement();
        req.setId("req-for-solution");
        req.setProjectId(PROJECT_ID);
        req.setText("实现订单创建功能");
        req.setGoal("实现订单创建");
        when(requirementRepository.selectById("req-for-solution")).thenReturn(req);

        // 需求条目（rebuildAnalysis 用）
        RequirementItem item = new RequirementItem();
        item.setId("item-1");
        item.setRequirementId("req-for-solution");
        item.setCode("R1");
        item.setText("订单创建");
        when(itemRepository.selectList(any())).thenReturn(List.of(item));
        when(criterionRepository.selectList(any())).thenReturn(List.of());

        // 扫描版本
        ScanVersion version = new ScanVersion();
        version.setId(VERSION_ID);
        when(scanVersionRepository.selectOne(any())).thenReturn(version);

        // 链接 + 影响子图
        when(linkingService.link(eq(PROJECT_ID), eq(VERSION_ID), eq("req-for-solution"), any()))
                .thenReturn(List.of());
        ImpactResult impactResult = new ImpactResult();
        when(impactService.extract(eq(PROJECT_ID), eq(VERSION_ID), any())).thenReturn(impactResult);

        // LLM 生成方案（含成本/风险）
        SolutionPlan plan = buildSamplePlan();
        when(planner.plan(eq(PROJECT_ID), eq(VERSION_ID), any(), any())).thenReturn(plan);

        // === 步骤 A: 调用 generate ===
        SolutionController.GenerateRequest genRequest = new SolutionController.GenerateRequest();
        genRequest.setRequirementId("req-for-solution");

        Result<SolutionController.SolutionDetailResponse> genResult =
                solutionController.generate(PROJECT_ID, genRequest);

        // 验证生成结果
        assertThat(genResult.getCode()).isEqualTo(0);
        assertThat(genResult.getData().getSolutionId()).isNotNull();
        assertThat(genResult.getData().getStatus()).isEqualTo("DRAFT");
        assertThat(genResult.getData().getSteps()).hasSize(2);
        assertThat(genResult.getData().getSummary()).isEqualTo("实现订单创建功能");

        // 验证持久化
        verify(solutionRepository).insert(any(Solution.class));
        verify(stepRepository, atLeast(1)).insert(any(SolutionStep.class));

        // === 步骤 B: 调用 verify ===
        // 准备 verify 用的 mock
        Solution generatedSolution = new Solution();
        generatedSolution.setId(genResult.getData().getSolutionId());
        generatedSolution.setProjectId(PROJECT_ID);
        generatedSolution.setRequirementId("req-for-solution");
        generatedSolution.setStatus("DRAFT");
        generatedSolution.setSummary("实现订单创建功能");
        when(solutionRepository.selectById(genResult.getData().getSolutionId()))
                .thenReturn(generatedSolution);

        // 步骤列表（verify 时从 DB 读取）
        SolutionStep step1 = new SolutionStep();
        step1.setSolutionId(genResult.getData().getSolutionId());
        step1.setStepIndex(0);
        step1.setTitle("创建 OrderService");
        step1.setFilePath("src/OrderService.java");
        step1.setActionType("CREATE");
        step1.setTestDescription("验证订单创建");
        when(stepRepository.selectList(any())).thenReturn(List.of(step1));

        // verifier 返回通过
        SolutionVerificationResult verifyResult = new SolutionVerificationResult();
        verifyResult.setPassed(true);
        verifyResult.setStatus("READY_FOR_REVIEW");
        when(verifier.verify(anyString(), anyString(), any(), any(), any())).thenReturn(verifyResult);

        Result<SolutionVerificationResult> verifyResponse =
                solutionController.verify(genResult.getData().getSolutionId());

        // 验证校验结果
        assertThat(verifyResponse.getData().isPassed()).isTrue();
        assertThat(verifyResponse.getData().getStatus()).isEqualTo("READY_FOR_REVIEW");

        // 验证 Solution 状态被更新为 READY_FOR_REVIEW
        verify(solutionRepository).updateById(any(Solution.class));
    }

    /**
     * 测试 4：方案桥接链路 — bridge → APPROVED Solution → ChangeTask
     */
    @Test
    @DisplayName("4. 方案桥接 — APPROVED 方案 → ChangeTask 创建 → changeTaskId 回写")
    void test04_solutionBridge_flow() {
        // 扫描版本
        ScanVersion version = new ScanVersion();
        version.setId(VERSION_ID);
        when(scanVersionRepository.selectOne(any())).thenReturn(version);

        // 桥接服务返回 ChangeTask
        ChangeTask mockTask = new ChangeTask();
        mockTask.setId("ct-bridge-001");
        mockTask.setProjectId(PROJECT_ID);
        mockTask.setVersionId(VERSION_ID);
        mockTask.setTaskType("REFACTOR");
        when(bridgeService.bridge("sol-approved", PROJECT_ID, VERSION_ID)).thenReturn(mockTask);

        // 调用
        Result<ChangeTask> result = solutionController.bridge(PROJECT_ID, "sol-approved");

        // 验证
        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getData().getId()).isEqualTo("ct-bridge-001");
        assertThat(result.getData().getProjectId()).isEqualTo(PROJECT_ID);

        // 验证桥接服务被调用
        verify(bridgeService).bridge("sol-approved", PROJECT_ID, VERSION_ID);
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private RequirementAnalysis buildSampleAnalysis() {
        RequirementAnalysis analysis = new RequirementAnalysis();
        analysis.setGoal("实现订单创建功能");

        RequirementItemDTO item1 = new RequirementItemDTO();
        item1.setCode("R1");
        item1.setText("订单创建");
        item1.setAcceptanceCriteria(List.of("创建订单后返回订单号"));
        item1.setConstraints(List.of("订单号格式: ORD-YYYYMMDD-NNNN"));

        RequirementItemDTO item2 = new RequirementItemDTO();
        item2.setCode("R2");
        item2.setText("库存校验");
        item2.setAcceptanceCriteria(List.of("库存不足时返回错误"));

        analysis.setItems(new ArrayList<>(List.of(item1, item2)));
        analysis.setOpenQuestions(new ArrayList<>());
        return analysis;
    }

    private SolutionPlan buildSamplePlan() {
        SolutionPlan plan = new SolutionPlan();
        plan.setSummary("实现订单创建功能");

        SolutionPlanStep step1 = new SolutionPlanStep();
        step1.setTitle("创建 OrderService");
        step1.setDescription("新增 OrderService 处理订单创建逻辑");
        step1.setFilePath("src/main/java/com/demo/service/OrderService.java");
        step1.setSymbolName("OrderService");
        step1.setActionType("CREATE");
        step1.setTestDescription("验证 OrderService.createOrder 返回订单号");
        step1.setRollbackDescription("删除 OrderService.java");

        SolutionPlanStep step2 = new SolutionPlanStep();
        step2.setTitle("修改 OrderController");
        step2.setDescription("在 OrderController 中新增 createOrder 端点");
        step2.setFilePath("src/main/java/com/demo/controller/OrderController.java");
        step2.setSymbolName("OrderController.createOrder");
        step2.setActionType("MODIFY");
        step2.setTestDescription("验证 POST /api/orders 返回 200");
        step2.setRollbackDescription("恢复 OrderController 原始代码");

        plan.setSteps(List.of(step1, step2));

        // 成本估算
        SolutionPlan.CostEstimate cost = new SolutionPlan.CostEstimate();
        cost.setPersonDays(3.0);
        cost.setAffectedFiles(2);
        cost.setComplexity("MEDIUM");
        plan.setEstimatedCost(cost);

        // 风险评估
        SolutionPlan.RiskAssessment risk = new SolutionPlan.RiskAssessment();
        risk.setRiskLevel("LOW");
        risk.setHighRiskAreas(List.of("库存并发扣减"));
        risk.setMitigations(List.of("使用乐观锁"));
        plan.setRiskAssessment(risk);

        return plan;
    }
}
