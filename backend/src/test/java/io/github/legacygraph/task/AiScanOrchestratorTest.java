package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.agent.FeatureMappingAgent;
import io.github.legacygraph.agent.TestCaseAgent;
import io.github.legacygraph.agent.CodeFactAgent;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.ScanStep;
import io.github.legacygraph.builder.BusinessGraphBuilder;
import io.github.legacygraph.builder.EvidenceGraphWriter;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.dto.FactExtractionResult;
import io.github.legacygraph.dto.GeneratedTestCase;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.entity.AiScanJob;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.AiScanJobRepository;
import io.github.legacygraph.repository.ReviewRecordRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.service.graph.GapFinderService;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.service.qa.VectorizationService;
import io.github.legacygraph.task.step.AiScanStepExecutor;
import io.github.legacygraph.task.step.StepExecutionResult;
import io.github.legacygraph.task.step.StepExecutionContext;
import io.github.legacygraph.understanding.ScanUnderstandingEnhancer;
import io.github.legacygraph.util.IdUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AiScanOrchestrator 测试 — 验证开关门控、四个 AI 子任务编排、低置信审核生成。
 */
@ExtendWith(MockitoExtension.class)
class AiScanOrchestratorTest {

    @Mock private ScanTaskRepository scanTaskRepository;
    @Mock private ScanTaskRecorder scanTaskRecorder;
    @Mock private AiScanJobRepository aiScanJobRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private FactRepository factRepository;
    @Mock private ReviewRecordRepository reviewRecordRepository;
    @Mock private TestCaseRepository testCaseRepository;
    @Mock private Neo4jGraphDao neo4jGraphDao;
    @Mock private DocUnderstandingAgent docUnderstandingAgent;
    @Mock private FeatureMappingAgent featureMappingAgent;
    @Mock private TestCaseAgent testCaseAgent;
    @Mock private CodeFactAgent codeFactAgent;
    @Mock private BusinessGraphBuilder businessGraphBuilder;
    @Mock private KnowledgeClaimService knowledgeClaimService;
    @Mock private GapFinderService gapFinderService;
    @Mock private ScanUnderstandingEnhancer scanUnderstandingEnhancer;
    @Mock private EvidenceGraphWriter evidenceGraphWriter;
    @Mock private VectorizationService vectorizationService;
    @Mock private io.micrometer.core.instrument.Timer scanDurationTimer;
    @Mock private io.micrometer.core.instrument.Counter agentCallCounter;
    @Mock private io.micrometer.core.instrument.Counter graphNodeCounter;
    @Mock private io.micrometer.core.instrument.Counter graphEdgeCounter;

    private AiScanOrchestrator orchestrator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        // 重构后：手动构造共享支撑组件 + 各真实步骤执行器，把 mock 依赖注入进去，
        // 再组成 List 传给 AiScanOrchestrator（步骤执行器模式）。
        io.github.legacygraph.task.step.AiScanStepSupport support =
                new io.github.legacygraph.task.step.AiScanStepSupport(
                        scanTaskRecorder, scanTaskRepository, factRepository,
                        knowledgeClaimService, vectorizationService, objectMapper);
        List<io.github.legacygraph.task.step.AiScanStepExecutor> stepExecutors = List.of(
                new io.github.legacygraph.task.step.DocExtractStep(support, documentRepository,
                        docUnderstandingAgent, businessGraphBuilder, neo4jGraphDao,
                        new io.github.legacygraph.service.document.DefaultDocumentPartitionService(),
                        new io.github.legacygraph.service.document.StructureAwareChunkService(2500),
                        agentCallCounter, graphNodeCounter, graphEdgeCounter),
                new io.github.legacygraph.task.step.CodeExtractStep(support, neo4jGraphDao,
                        codeFactAgent, businessGraphBuilder),
                new io.github.legacygraph.task.step.FeatureMappingStep(support, neo4jGraphDao,
                        featureMappingAgent, evidenceGraphWriter, reviewRecordRepository,
                        objectMapper, agentCallCounter, businessGraphBuilder),
                new io.github.legacygraph.task.step.TestGenerateStep(support, neo4jGraphDao,
                        testCaseAgent, testCaseRepository, objectMapper),
                new io.github.legacygraph.task.step.ReviewPrepareStep(support, neo4jGraphDao,
                        reviewRecordRepository),
                new io.github.legacygraph.task.step.KnowledgeGapStep(support, gapFinderService),
                new io.github.legacygraph.task.step.UnderstandingEnhancementStep(support, neo4jGraphDao,
                        scanUnderstandingEnhancer));
        orchestrator = new AiScanOrchestrator(scanTaskRepository, scanTaskRecorder, aiScanJobRepository,
                objectMapper, stepExecutors, gapFinderService);
        lenient().when(gapFinderService.scanGaps(anyString(), anyString()))
                .thenReturn(GapFinderService.GapScanResult.builder()
                        .created(0)
                        .reopened(0)
                        .unchanged(0)
                        .byType(Map.of())
                        .build());
        lenient().when(knowledgeClaimService.upsertDrafts(anyList())).thenReturn(List.of());
        lenient().when(docUnderstandingAgent.toClaimDrafts(anyString(), anyString(), any(), anyString()))
                .thenCallRealMethod();
        lenient().when(codeFactAgent.toClaimDrafts(anyString(), anyString(), any(), anyString()))
                .thenCallRealMethod();
        lenient().when(featureMappingAgent.toClaimDrafts(anyString(), anyString(), any()))
                .thenCallRealMethod();
        lenient().when(scanUnderstandingEnhancer.enhance(anyString(), anyString(), anyList()))
                .thenReturn(ScanUnderstandingEnhancer.EnhancementResult.builder()
                        .enabled(false)
                        .message("扫描后增强未启用")
                        .build());
        // 配置 scanTaskRecorder mock 正确创建和完成任务（委托给 scanTaskRepository）
        lenient().when(scanTaskRecorder.createTask(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    ScanTask task = new ScanTask();
                    task.setId(IdUtil.fastUUID());
                    task.setProjectId(inv.getArgument(0));
                    task.setVersionId(inv.getArgument(1));
                    task.setTaskType(inv.getArgument(2));
                    task.setTaskName(inv.getArgument(3));
                    task.setTaskStatus("RUNNING");
                    task.setStartedAt(java.time.LocalDateTime.now());
                    task.setCreatedAt(java.time.LocalDateTime.now());
                    task.setUpdatedAt(java.time.LocalDateTime.now());
                    scanTaskRepository.insert(task);
                    return task;
                });
        lenient().doAnswer(inv -> {
                    ScanTask task = inv.getArgument(0);
                    String summary = inv.getArgument(1);
                    String error = inv.getArgument(2);
                    if (task != null) {
                        if (summary != null) task.setOutputSummary(summary);
                        task.setErrorMessage(error);
                        task.setTaskStatus(error == null ? "SUCCESS" : "FAILED");
                        task.setFinishedAt(java.time.LocalDateTime.now());
                        task.setUpdatedAt(java.time.LocalDateTime.now());
                        scanTaskRepository.updateById(task);
                    }
                    return null;
                }).when(scanTaskRecorder).completeTask(any(ScanTask.class), anyString(), nullable(String.class));
        lenient().doAnswer(inv -> {
                    ScanTask task = inv.getArgument(0);
                    String summary = inv.getArgument(1);
                    String error = inv.getArgument(2);
                    String terminalStatus = inv.getArgument(3);
                    if (task != null) {
                        if (summary != null) task.setOutputSummary(summary);
                        task.setErrorMessage(error);
                        task.setTaskStatus(terminalStatus != null ? terminalStatus : (error == null ? "SUCCESS" : "FAILED"));
                        task.setFinishedAt(java.time.LocalDateTime.now());
                        task.setUpdatedAt(java.time.LocalDateTime.now());
                        scanTaskRepository.updateById(task);
                    }
                    return null;
                }).when(scanTaskRecorder).completeTask(any(ScanTask.class), anyString(), nullable(String.class), nullable(String.class));
    }

    private GraphNode node(String type, String key, String name, double conf) {
        GraphNode n = new GraphNode();
        n.setId("id-" + key);
        n.setNodeType(type);
        n.setNodeKey(key);
        n.setNodeName(name);
        n.setConfidence(BigDecimal.valueOf(conf));
        return n;
    }

    @Test
    void testOrchestrate_AiDisabled_DoesNothing() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(false);

        orchestrator.orchestrate("proj-1", "v1", config, null);

        // P1-B：enableAi=false 时创建结构化跳过任务，确保在 scan_task 列表中可见
        verify(scanTaskRepository).insert(any(ScanTask.class));
        verify(scanTaskRepository).updateById(any(ScanTask.class));
        verify(gapFinderService).scanGaps("proj-1", "v1");
        verifyNoInteractions(documentRepository);
        verifyNoInteractions(neo4jGraphDao);
    }

    @Test
    void testOrchestrate_NullConfig_DoesNothing() {
        orchestrator.orchestrate("proj-1", "v1", null, null);

        // null config 同 enableAi=false：创建结构化跳过任务
        verify(scanTaskRepository).insert(any(ScanTask.class));
        verify(scanTaskRepository).updateById(any(ScanTask.class));
        verify(gapFinderService).scanGaps("proj-1", "v1");
    }

    @Test
    void testCodeExtract_NoServiceOrController_MarksWarning() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);
        config.setAutoGenerateTestCase(false);
        config.setMinConfidence(0.6);

        when(documentRepository.selectList(any())).thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        orchestrator.orchestrate("proj-1", "v1", config, null);

        ArgumentCaptor<ScanTask> updateCaptor = ArgumentCaptor.forClass(ScanTask.class);
        verify(scanTaskRepository, atLeastOnce()).updateById(updateCaptor.capture());
        ScanTask codeExtractTask = updateCaptor.getAllValues().stream()
                .filter(t -> "AI_CODE_EXTRACT".equals(t.getTaskType()))
                .findFirst()
                .orElseThrow();
        assertEquals("WARNING", codeExtractTask.getTaskStatus());
        assertTrue(codeExtractTask.getOutputSummary().contains("无 Service/Controller"));
    }

    @Test
    @Disabled("FeatureCodeMappingStep 已移除，该步骤不再独立编排")
    void testFeatureCodeMapping_NoEdges_MarksWarning() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);
        config.setAutoGenerateTestCase(false);
        config.setMinConfidence(0.6);

        when(documentRepository.selectList(any())).thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        orchestrator.orchestrate("proj-1", "v1", config, null);

        ArgumentCaptor<ScanTask> updateCaptor = ArgumentCaptor.forClass(ScanTask.class);
        verify(scanTaskRepository, atLeastOnce()).updateById(updateCaptor.capture());
        ScanTask mappingTask = updateCaptor.getAllValues().stream()
                .filter(t -> "AI_FEATURE_CODE_MAPPING".equals(t.getTaskType()))
                .findFirst()
                .orElseThrow();
        assertEquals("WARNING", mappingTask.getTaskStatus());
        assertTrue(mappingTask.getOutputSummary().contains("未建立 Feature/业务对象"));
    }

    @Test
    void testOrchestrate_Enabled_RunsFourSubTasks_WithoutTestGen() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);
        config.setAutoGenerateTestCase(false);
        config.setMinConfidence(0.6);

        // 无文档
        when(documentRepository.selectList(any())).thenReturn(List.of());
        lenient().when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        // 无页面/接口节点（feature mapping 跳过），低置信节点用于审核
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Page.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.ApiEndpoint.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), isNull(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        node(NodeType.ApiEndpoint.name(), "api:/a", "低置信A", 0.3),
                        node(NodeType.ApiEndpoint.name(), "api:/b", "高置信B", 0.9)));
        when(reviewRecordRepository.selectCount(any())).thenReturn(0L);

        orchestrator.orchestrate("proj-1", "v1", config, null);

        // 创建了 6 个子任务（含 AI_GAP_FINDING / AI_CODE_UNDERSTANDING），未创建 TEST_GENERATE
        ArgumentCaptor<ScanTask> taskCaptor = ArgumentCaptor.forClass(ScanTask.class);
        verify(scanTaskRepository, times(6)).insert(taskCaptor.capture());
        List<String> types = taskCaptor.getAllValues().stream().map(ScanTask::getTaskType).toList();
        assertTrue(types.contains("AI_DOC_EXTRACT"));
        assertTrue(types.contains("AI_CODE_EXTRACT"));
        assertTrue(types.contains("AI_FEATURE_MAPPING"));
        assertTrue(types.contains("AI_REVIEW_PREPARE"));
        assertTrue(types.contains("AI_GAP_FINDING"));
        assertTrue(types.contains("AI_CODE_UNDERSTANDING"));
        assertFalse(types.contains("AI_TEST_GENERATE"));

        // 仅低置信节点(0.3<0.6)生成审核记录，高置信(0.9)跳过
        verify(reviewRecordRepository, times(1)).insert(any(io.github.legacygraph.entity.ReviewRecord.class));
        // 测试用例生成未触发
        verifyNoInteractions(testCaseAgent);
    }

    @Test
    void testOrchestrate_WithTestGen_GeneratesAndPersistsCases() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);
        config.setAutoGenerateTestCase(true);
        config.setMinConfidence(0.5);

        when(documentRepository.selectList(any())).thenReturn(List.of());
        lenient().when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Page.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        // API 节点用于 feature mapping 与 test gen
        List<GraphNode> apis = List.of(node(NodeType.ApiEndpoint.name(), "api:/order", "下单接口", 0.8));
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.ApiEndpoint.name()), any(), any(), any(), anyInt()))
                .thenReturn(apis);
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), isNull(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        FeatureMappingAgent.MappingResult mapping = new FeatureMappingAgent.MappingResult();
        lenient().when(featureMappingAgent.mapFeatures(any())).thenReturn(mapping);

        GeneratedTestCase gc = new GeneratedTestCase();
        gc.setCaseName("下单-正常");
        gc.setCaseType(GeneratedTestCase.CaseType.API);
        gc.setPreconditions(List.of("用户已登录"));
        gc.setSteps(List.of("调用下单接口"));
        gc.setRequest(Map.of("method", "POST", "path", "/api/order", "body", Map.of("skuId", "SKU-1")));
        GeneratedTestCase.TestCaseAssertion assertion = new GeneratedTestCase.TestCaseAssertion();
        assertion.setType(GeneratedTestCase.AssertionType.JSON_PATH);
        assertion.setExpression("$.code == 0");
        gc.setAssertions(List.of(assertion));
        when(testCaseAgent.generateTestCases(any())).thenReturn(List.of(gc));

        orchestrator.orchestrate("proj-1", "v1", config, null);

        // 创建了 7 个子任务（含 AI_GAP_FINDING / AI_CODE_UNDERSTANDING）
        ArgumentCaptor<ScanTask> taskCaptor = ArgumentCaptor.forClass(ScanTask.class);
        verify(scanTaskRepository, times(7)).insert(taskCaptor.capture());
        assertTrue(taskCaptor.getAllValues().stream()
                .anyMatch(t -> "AI_TEST_GENERATE".equals(t.getTaskType())));
        assertTrue(taskCaptor.getAllValues().stream()
                .anyMatch(t -> "AI_GAP_FINDING".equals(t.getTaskType())));
        assertTrue(taskCaptor.getAllValues().stream()
                .anyMatch(t -> "AI_CODE_UNDERSTANDING".equals(t.getTaskType())));

        // 生成的测试用例被持久化
        verify(testCaseAgent, atLeastOnce()).generateTestCases(any());
        // 批量持久化（insertBatch 替代逐条 insert）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestCase>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(testCaseRepository, times(1)).insertBatch(batchCaptor.capture());
        List<TestCase> persisted = batchCaptor.getValue();
        assertEquals(1, persisted.size());
        TestCase tc = persisted.get(0);
        // preconditions 现在是 JSON 数组
        assertEquals("[\"用户已登录\"]", tc.getPreconditions());
        assertTrue(tc.getExpectedResult().contains("$.code == 0"));
        // steps 是 JSON 数组，包含 request 的 method 字段
        assertTrue(tc.getSteps().contains("\"method\":\"POST\""));
    }

    @Test
    void testOrchestrate_FeatureMapping_UsesRealNodeLabelsAndPersistsPendingEdgeAndReview() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);
        config.setAutoGenerateTestCase(false);
        config.setMinConfidence(0.5);

        GraphNode page = node(NodeType.Page.name(), "page:/order", "订单页", 0.9);
        GraphNode api = node(NodeType.ApiEndpoint.name(), "api:/order", "下单接口", 0.9);
        GraphNode feature = node(NodeType.Feature.name(), "feature:订单提交", "订单提交", 0.9);
        when(documentRepository.selectList(any())).thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Page.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of(page));
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.ApiEndpoint.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of(api));
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), isNull(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        // 新增步骤：runCodeExtract 查询 Service/Controller（返回空，跳过代码抽取）
        lenient().when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Service.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Controller.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of());
        // Feature 节点作为功能映射的边起点（businessAction=订单提交 按 nodeName 解析到该节点）
        lenient().when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Feature.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of(feature));
        lenient().when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.BusinessObject.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Table.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Mapper.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(reviewRecordRepository.selectCount(any())).thenReturn(0L);

        FeatureMappingAgent.Mapping mapping = new FeatureMappingAgent.Mapping();
        mapping.setPageKey("page:/order");
        mapping.setApiKey("api:/order");
        mapping.setBusinessAction("订单提交");
        mapping.setConfidence(0.82);
        FeatureMappingAgent.MappingResult result = new FeatureMappingAgent.MappingResult();
        result.setMappings(List.of(mapping));
        when(featureMappingAgent.mapFeatures(any())).thenReturn(result);
        GraphEdge persistedEdge = new GraphEdge();
        persistedEdge.setId("edge-1");
        when(evidenceGraphWriter.upsertEdge(any(GraphEdgeClaim.class))).thenReturn(persistedEdge);

        orchestrator.orchestrate("proj-1", "v1", config, null);

        // 一条映射（同时含 pageKey+apiKey）落地为 2 条边：
        // Feature IMPLEMENTED_BY ApiEndpoint + Feature EXPOSED_BY Page，起点均为 Feature
        ArgumentCaptor<GraphEdgeClaim> claimCaptor = ArgumentCaptor.forClass(GraphEdgeClaim.class);
        verify(evidenceGraphWriter, times(2)).upsertEdge(claimCaptor.capture());
        List<GraphEdgeClaim> claims = claimCaptor.getAllValues();
        assertTrue(claims.stream().allMatch(c -> feature.getId().equals(c.getFromNodeId())));
        assertTrue(claims.stream().allMatch(c -> "AI_FEATURE_MAPPING".equals(c.getSourceType())));
        assertTrue(claims.stream().allMatch(c -> "PENDING_CONFIRM".equals(c.getStatus())));
        // Feature → ApiEndpoint（IMPLEMENTED_BY）
        assertTrue(claims.stream().anyMatch(c ->
                EdgeType.IMPLEMENTED_BY.name().equals(c.getEdgeType())
                        && api.getId().equals(c.getToNodeId())));
        // Feature → Page（EXPOSED_BY）
        assertTrue(claims.stream().anyMatch(c ->
                EdgeType.EXPOSED_BY.name().equals(c.getEdgeType())
                        && page.getId().equals(c.getToNodeId())));

        ArgumentCaptor<ReviewRecord> reviewCaptor = ArgumentCaptor.forClass(ReviewRecord.class);
        verify(reviewRecordRepository).insert(reviewCaptor.capture());
        assertTrue(reviewCaptor.getValue().getComment().contains("订单提交"));
        verify(knowledgeClaimService, atLeastOnce()).upsertDrafts(argThat(drafts ->
                containsDraft(drafts, "Feature", "feature:订单提交", "EXPOSED_BY", "Page", "page:/order")
                        && containsDraft(drafts, "Feature", "feature:订单提交", "IMPLEMENTS", "ApiEndpoint", "api:/order")));
    }

    @Test
    void testOrchestrate_DocExtract_PersistsAllBusinessFactTypes() throws Exception {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);
        config.setMinConfidence(0.6);

        Path docPath = tempDir.resolve("product.md");
        Files.writeString(docPath, "订单提交后校验库存，状态从待支付流转为已支付。");
        io.github.legacygraph.entity.Document doc = new io.github.legacygraph.entity.Document();
        doc.setId("d1");
        doc.setFilePath(docPath.toString());
        when(documentRepository.selectList(any())).thenReturn(List.of(doc));
        when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        DocUnderstandingAgent.BusinessFactExtraction extraction = new DocUnderstandingAgent.BusinessFactExtraction();
        DocUnderstandingAgent.BusinessDomain domain = new DocUnderstandingAgent.BusinessDomain();
        domain.setName("订单域");
        domain.setDescription("订单业务");
        domain.setConfidence(0.9);
        DocUnderstandingAgent.BusinessProcess process = new DocUnderstandingAgent.BusinessProcess();
        process.setKey("order-submit");
        process.setName("订单提交");
        process.setConfidence(0.8);
        DocUnderstandingAgent.BusinessObject object = new DocUnderstandingAgent.BusinessObject();
        object.setName("订单");
        object.setConfidence(0.8);
        DocUnderstandingAgent.BusinessRule rule = new DocUnderstandingAgent.BusinessRule();
        rule.setName("库存校验");
        rule.setExpression("提交前库存必须充足");
        rule.setConfidence(0.7);
        DocUnderstandingAgent.StatusTransition transition = new DocUnderstandingAgent.StatusTransition();
        transition.setBusinessObject("订单");
        transition.setFromStatus("待支付");
        transition.setToStatus("已支付");
        transition.setTrigger("支付成功");
        transition.setConfidence(0.75);
        extraction.setBusinessDomains(List.of(domain));
        extraction.setBusinessProcesses(List.of(process));
        extraction.setBusinessObjects(List.of(object));
        extraction.setBusinessRules(List.of(rule));
        extraction.setRoles(List.of("买家"));
        extraction.setStatusTransitions(List.of(transition));
        extraction.setFeatures(List.of("提交订单"));
        when(docUnderstandingAgent.extractBusinessFacts(eq("proj-1"), anyString(), eq(docPath.toString())))
                .thenReturn(extraction);

        orchestrator.orchestrate("proj-1", "v1", config, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Fact>> factsCaptor = ArgumentCaptor.forClass(List.class);
        verify(factRepository, atLeastOnce()).batchUpsert(factsCaptor.capture());
        List<String> factTypes = factsCaptor.getAllValues().stream()
                .flatMap(List::stream).map(Fact::getFactType).toList();
        assertTrue(factTypes.contains("BUSINESS_DOMAIN"));
        assertTrue(factTypes.contains("BUSINESS_PROCESS"));
        assertTrue(factTypes.contains("BUSINESS_OBJECT"));
        assertTrue(factTypes.contains("BUSINESS_RULE"));
        assertTrue(factTypes.contains("BUSINESS_ROLE"));
        assertTrue(factTypes.contains("STATUS_TRANSITION"));
        assertTrue(factTypes.contains("FEATURE"));
        verify(businessGraphBuilder).buildBusinessGraph(eq("proj-1"), eq("v1"), eq(extraction), anyString());
        verify(knowledgeClaimService, atLeastOnce()).upsertDrafts(argThat(drafts ->
                containsDraft(drafts, "Feature", "feature:提交订单", "DESCRIBED_BY", "Evidence", docPath.toString())
                        && containsDraft(drafts, "BusinessObject", "object:订单", "MENTIONED_IN", "Evidence", docPath.toString())
                        && containsDraft(drafts, "BusinessRule", "rule:库存校验", "MENTIONED_IN", "Evidence", docPath.toString())));
    }

    /**
     * 5.2-4：代码侧端到端——存在 Service/Controller 图节点 + 可读源码 + CodeFactAgent 返回 FactItem 时，
     * 应保存 CODE_FEATURE 事实（sourceType=CODE_AI）并创建 Feature 图节点。
     */
    @Test
    void testCodeExtract_SavesCodeFactWithCodeAiSourceType() throws Exception {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);
        config.setMinConfidence(0.6);

        // 创建临时 Java 源文件
        Path codePath = tempDir.resolve("OrderService.java");
        Files.writeString(codePath, "public class OrderService { public void createOrder() {} }");

        GraphNode serviceNode = new GraphNode();
        serviceNode.setId("svc-1");
        serviceNode.setNodeType(NodeType.Service.name());
        serviceNode.setNodeKey("service:OrderService");
        serviceNode.setNodeName("OrderService");
        serviceNode.setSourcePath(codePath.toString());

        // 无文档
        when(documentRepository.selectList(any())).thenReturn(List.of());
        // Service 节点存在
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Service.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of(serviceNode));
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Controller.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of());
        // page/api 查询 + feature 查询 + review 查询均返回空
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Page.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.ApiEndpoint.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Feature.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), isNull(),
                any(), any(), any(), anyInt())).thenReturn(List.of());

        // CodeFactAgent 返回事实
        FactExtractionResult codeResult = new FactExtractionResult();
        FactExtractionResult.FactItem item = new FactExtractionResult.FactItem();
        item.setKey("code-feature:创建订单");
        item.setName("创建订单");
        item.setConfidence(BigDecimal.valueOf(0.8));
        codeResult.setItems(List.of(item));
        when(codeFactAgent.extractFacts(eq("proj-1"), anyString(), eq(codePath.toString())))
                .thenReturn(codeResult);

        lenient().when(reviewRecordRepository.selectCount(any())).thenReturn(0L);

        orchestrator.orchestrate("proj-1", "v1", config, null);

        // 验证：CODE_FEATURE 事实已保存，sourceType=CODE_AI
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Fact>> factsCaptor = ArgumentCaptor.forClass(List.class);
        verify(factRepository, atLeastOnce()).batchUpsert(factsCaptor.capture());
        List<Fact> codeFacts = factsCaptor.getAllValues().stream().flatMap(List::stream)
                .filter(f -> "CODE_FEATURE".equals(f.getFactType()))
                .toList();
        assertFalse(codeFacts.isEmpty(), "Should persist at least one CODE_FEATURE fact");
        // 关键断言：来源类型为 CODE_AI，不是 DOC_AI
        assertTrue(codeFacts.stream().allMatch(f -> "CODE_AI".equals(f.getSourceType())),
                "Code-extracted facts should have sourceType=CODE_AI, got: "
                        + codeFacts.stream().map(Fact::getSourceType).toList());

        // 验证：businessGraphBuilder 被调用（创建 Feature 图节点）
        verify(businessGraphBuilder, atLeastOnce()).buildBusinessGraph(
                eq("proj-1"), eq("v1"), any(), eq(codePath.toString()), eq("CODE_AI"));
        verify(knowledgeClaimService, atLeastOnce()).upsertDrafts(argThat(drafts ->
                containsDraft(drafts, "Feature", "feature:创建订单", "IMPLEMENTS", "SourceFile", codePath.toString())));
    }

    /**
     * 5.2-5：自动路径 Feature 对齐——自动编排产生的 Feature 应触发 Feature→Page/API 映射，
     * 而不是只依赖手动 extractDocFacts()。
     */
    @Test
    void testAutoOrchestration_MapsFeaturesToCode() throws Exception {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);
        config.setMinConfidence(0.6);

        // 无文档
        when(documentRepository.selectList(any())).thenReturn(List.of());
        // 无 Service/Controller
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Service.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Controller.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of());
        // Feature 节点存在（模拟之前抽取的）
        GraphNode featureNode = node(NodeType.Feature.name(), "feature:订单管理", "订单管理", 0.8);
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Feature.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of(featureNode));
        // Page 节点存在
        GraphNode pageNode = node(NodeType.Page.name(), "page:/order", "订单页", 0.9);
        pageNode.setDisplayName("订单管理页面");
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Page.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of(pageNode));
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.ApiEndpoint.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), isNull(),
                any(), any(), any(), anyInt())).thenReturn(List.of());

        // mock mapFeaturesToCode 的Table/Service/Mapper 查询（lenient：无 BusinessObject 时提前返回）
        lenient().when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Table.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.BusinessObject.name()),
                any(), any(), any(), anyInt())).thenReturn(List.of());

        lenient().when(reviewRecordRepository.selectCount(any())).thenReturn(0L);

        orchestrator.orchestrate("proj-1", "v1", config, null);

        // 验证：AI_FEATURE_MAPPING 任务已创建（自动路径执行了 Feature 映射）
        ArgumentCaptor<ScanTask> taskCaptor = ArgumentCaptor.forClass(ScanTask.class);
        verify(scanTaskRepository, atLeastOnce()).insert(taskCaptor.capture());
        List<String> taskTypes = taskCaptor.getAllValues().stream()
                .map(ScanTask::getTaskType).toList();
        // AI_FEATURE_MAPPING 应在编排中（以 Feature 为锚点）
        assertTrue(taskTypes.contains("AI_FEATURE_MAPPING"),
                "Auto orchestration should include AI_FEATURE_MAPPING task, got: " + taskTypes);
    }

    // ==================== Task 2: 增量上下文传递测试 ====================

    @Test
    void testEnqueue_StoresIncrementalContext() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);

        Set<String> changedPaths = Set.of("src/main/java/Foo.java", "src/main/java/Bar.java");
        Set<String> affectedNodes = Set.of("node-1", "node-2", "node-3");

        orchestrator.enqueue("proj-inc", "v-inc", config, changedPaths, affectedNodes);

        ArgumentCaptor<AiScanJob> jobCaptor = ArgumentCaptor.forClass(AiScanJob.class);
        verify(aiScanJobRepository).insert(jobCaptor.capture());
        AiScanJob job = jobCaptor.getValue();

        assertEquals("proj-inc", job.getProjectId());
        assertEquals("v-inc", job.getVersionId());
        assertEquals("PENDING", job.getStatus());
        assertNotNull(job.getChangedFilePathsJson(), "changedFilePathsJson should be populated");
        assertNotNull(job.getAffectedNodeIdsJson(), "affectedNodeIdsJson should be populated");
        assertTrue(job.getChangedFilePathsJson().contains("Foo.java"));
        assertTrue(job.getChangedFilePathsJson().contains("Bar.java"));
        assertTrue(job.getAffectedNodeIdsJson().contains("node-1"));
        assertTrue(job.getAffectedNodeIdsJson().contains("node-2"));
        assertTrue(job.getAffectedNodeIdsJson().contains("node-3"));
    }

    @Test
    void testEnqueue_BackwardCompat_NoIncrementalContext() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);

        // 旧 3 参数 enqueue（向后兼容），不应携带增量上下文
        orchestrator.enqueue("proj-bc", "v-bc", config);

        ArgumentCaptor<AiScanJob> jobCaptor = ArgumentCaptor.forClass(AiScanJob.class);
        verify(aiScanJobRepository).insert(jobCaptor.capture());
        AiScanJob job = jobCaptor.getValue();

        assertNull(job.getChangedFilePathsJson(), "3-arg enqueue should not set changedFilePathsJson");
        assertNull(job.getAffectedNodeIdsJson(), "3-arg enqueue should not set affectedNodeIdsJson");
    }

    @Test
    void testEnqueue_EmptySets_NotStored() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);

        // 空集合不应序列化到 AiScanJob（首次扫描场景）
        orchestrator.enqueue("proj-empty", "v-empty", config, Set.of(), Set.of());

        ArgumentCaptor<AiScanJob> jobCaptor = ArgumentCaptor.forClass(AiScanJob.class);
        verify(aiScanJobRepository).insert(jobCaptor.capture());
        AiScanJob job = jobCaptor.getValue();

        assertNull(job.getChangedFilePathsJson(), "empty changedFilePaths should not be stored");
        assertNull(job.getAffectedNodeIdsJson(), "empty affectedNodeIds should not be stored");
    }

    @Test
    void testOrchestrate_ReadsIncrementalContextFromJob() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);
        config.setAutoGenerateTestCase(false);
        config.setMinConfidence(0.6);

        // 模拟 AiScanJob 携带增量上下文
        AiScanJob job = new AiScanJob();
        job.setId("job-inc");
        job.setProjectId("proj-1");
        job.setVersionId("v1");
        job.setChangedFilePathsJson("[\"src/Foo.java\",\"src/Bar.java\"]");
        job.setAffectedNodeIdsJson("[\"node-a\",\"node-b\"]");
        when(aiScanJobRepository.selectById("job-inc")).thenReturn(job);

        when(documentRepository.selectList(any())).thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(reviewRecordRepository.selectCount(any())).thenReturn(0L);

        orchestrator.orchestrate("proj-1", "v1", config, null, "job-inc");

        // 验证 orchestrate 通过 jobId 读取了 AiScanJob 以获取增量上下文
        verify(aiScanJobRepository).selectById("job-inc");
    }

    @Test
    void testOrchestrate_NullJobId_DoesNotReadJob() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);
        config.setAutoGenerateTestCase(false);
        config.setMinConfidence(0.6);

        when(documentRepository.selectList(any())).thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(reviewRecordRepository.selectCount(any())).thenReturn(0L);

        // jobId=null（向后兼容调用），不应查询 AiScanJob
        orchestrator.orchestrate("proj-1", "v1", config, null, null);

        verify(aiScanJobRepository, never()).selectById(any());
    }

    private boolean containsDraft(List<KnowledgeClaimDraft> drafts, String subjectType, String subjectKey,
                                  String predicate, String objectType, String objectKey) {
        return drafts != null && drafts.stream().anyMatch(d ->
                subjectType.equals(d.getSubjectType())
                        && subjectKey.equals(d.getSubjectKey())
                        && predicate.equals(d.getPredicate())
                        && objectType.equals(d.getObjectType())
                        && objectKey.equals(d.getObjectKey()));
    }

    // ==================== Task 5: 并行编排测试 ====================

    /** 可控的测试步骤执行器：记录执行时间、支持自定义行为（sleep / 抛异常等）。 */
    private static class TestStepExecutor implements AiScanStepExecutor {
        private final int order;
        private final String name;
        private final ScanStep scanStep;
        private final java.util.function.Consumer<TestStepExecutor> action;

        volatile long startNanos;
        volatile long endNanos;
        volatile boolean executed;

        TestStepExecutor(int order, String name, ScanStep scanStep,
                         java.util.function.Consumer<TestStepExecutor> action) {
            this.order = order;
            this.name = name;
            this.scanStep = scanStep;
            this.action = action;
        }

        @Override
        public StepExecutionResult execute(StepExecutionContext ctx) {
            startNanos = System.nanoTime();
            try {
                action.accept(this);
            } finally {
                endNanos = System.nanoTime();
                executed = true;
            }
            return null;
        }

        @Override
        public String getStepName() { return name; }

        @Override
        public int getOrder() { return order; }

        @Override
        public ScanStep getScanStep() { return scanStep; }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 验证 order<=2 的两个步骤并行执行（执行时间有重叠）。
     * 使用 CyclicBarrier(2)：只有两个步骤同时运行才能通过，串行执行会超时。
     */
    @Test
    void testParallelSteps_OverlapInExecution() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicBoolean bothReached = new AtomicBoolean(false);

        TestStepExecutor step1 = new TestStepExecutor(1, "AI_STEP_1", ScanStep.EXTRACT_FACTS, s -> {
            try {
                barrier.await(2, TimeUnit.SECONDS);
                bothReached.set(true);
            } catch (Exception e) {
                // 超时说明不是并行
            }
        });
        TestStepExecutor step2 = new TestStepExecutor(2, "AI_STEP_2", ScanStep.BUILD_GRAPH, s -> {
            try {
                barrier.await(2, TimeUnit.SECONDS);
                bothReached.set(true);
            } catch (Exception e) {
                // 超时说明不是并行
            }
        });
        TestStepExecutor step3 = new TestStepExecutor(3, "AI_STEP_3", ScanStep.MERGE_ENTITIES, s -> {});

        AiScanOrchestrator testOrchestrator = new AiScanOrchestrator(
                scanTaskRepository, scanTaskRecorder, aiScanJobRepository, new ObjectMapper(),
                List.of(step1, step2, step3), gapFinderService);

        testOrchestrator.orchestrate("proj-1", "v1", config, null);

        assertTrue(step1.executed, "step1 应已执行");
        assertTrue(step2.executed, "step2 应已执行");
        assertTrue(step3.executed, "step3 应已执行");
        assertTrue(bothReached.get(), "step1 和 step2 应并行执行（同时到达 barrier）");
    }

    /**
     * 验证并行组内一个步骤抛异常时，另一个步骤仍正常完成（异常隔离）。
     */
    @Test
    void testParallelStep_ExceptionDoesNotBlockOther() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);

        TestStepExecutor step1 = new TestStepExecutor(1, "AI_STEP_1", ScanStep.EXTRACT_FACTS, s -> {
            throw new RuntimeException("step1 模拟失败");
        });
        AtomicBoolean step2Completed = new AtomicBoolean(false);
        TestStepExecutor step2 = new TestStepExecutor(2, "AI_STEP_2", ScanStep.BUILD_GRAPH, s -> {
            step2Completed.set(true);
        });
        TestStepExecutor step3 = new TestStepExecutor(3, "AI_STEP_3", ScanStep.MERGE_ENTITIES, s -> {});

        AiScanOrchestrator testOrchestrator = new AiScanOrchestrator(
                scanTaskRepository, scanTaskRecorder, aiScanJobRepository, new ObjectMapper(),
                List.of(step1, step2, step3), gapFinderService);

        testOrchestrator.orchestrate("proj-1", "v1", config, null);

        // step2 仍完成（异常隔离）
        assertTrue(step2Completed.get(), "step2 应在 step1 抛异常后仍正常完成");
        // step3 也执行了（编排继续到串行组）
        assertTrue(step3.executed, "step3 应在并行组完成后执行");
    }

    /**
     * 验证 order>=3 的步骤在并行组全部完成后才开始执行。
     */
    @Test
    void testSerialSteps_RunAfterParallelGroupCompletes() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);

        TestStepExecutor step1 = new TestStepExecutor(1, "AI_STEP_1", ScanStep.EXTRACT_FACTS,
                s -> sleepQuietly(50));
        TestStepExecutor step2 = new TestStepExecutor(2, "AI_STEP_2", ScanStep.BUILD_GRAPH,
                s -> sleepQuietly(50));
        AtomicLong serialStartNanos = new AtomicLong(0);
        TestStepExecutor step3 = new TestStepExecutor(3, "AI_STEP_3", ScanStep.MERGE_ENTITIES, s -> {
            serialStartNanos.set(System.nanoTime());
        });

        AiScanOrchestrator testOrchestrator = new AiScanOrchestrator(
                scanTaskRepository, scanTaskRecorder, aiScanJobRepository, new ObjectMapper(),
                List.of(step1, step2, step3), gapFinderService);

        testOrchestrator.orchestrate("proj-1", "v1", config, null);

        assertTrue(step3.executed, "step3 应已执行");
        // step3 的开始时间应 >= 并行组最晚结束时间
        long parallelEndMax = Math.max(step1.endNanos, step2.endNanos);
        assertTrue(serialStartNanos.get() >= parallelEndMax,
                "step3 应在并行组全部完成后才开始，实际 step3 开始于 " + serialStartNanos.get()
                        + "，并行组最晚结束于 " + parallelEndMax);
    }
}
