package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.agent.FeatureMappingAgent;
import io.github.legacygraph.agent.TestCaseAgent;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.builder.BusinessGraphBuilder;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.dto.GeneratedTestCase;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.ReviewRecordRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AiScanOrchestrator 测试 — 验证开关门控、四个 AI 子任务编排、低置信审核生成。
 */
@ExtendWith(MockitoExtension.class)
class AiScanOrchestratorTest {

    @Mock private ScanTaskRepository scanTaskRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private FactRepository factRepository;
    @Mock private ReviewRecordRepository reviewRecordRepository;
    @Mock private TestCaseRepository testCaseRepository;
    @Mock private Neo4jGraphDao neo4jGraphDao;
    @Mock private DocUnderstandingAgent docUnderstandingAgent;
    @Mock private FeatureMappingAgent featureMappingAgent;
    @Mock private TestCaseAgent testCaseAgent;
    @Mock private BusinessGraphBuilder businessGraphBuilder;

    private AiScanOrchestrator orchestrator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        orchestrator = new AiScanOrchestrator(scanTaskRepository, documentRepository, factRepository,
                reviewRecordRepository, testCaseRepository, neo4jGraphDao, docUnderstandingAgent,
                featureMappingAgent, testCaseAgent, businessGraphBuilder, new ObjectMapper());
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

        orchestrator.orchestrate("proj-1", "v1", config);

        verifyNoInteractions(scanTaskRepository);
        verifyNoInteractions(documentRepository);
        verifyNoInteractions(neo4jGraphDao);
    }

    @Test
    void testOrchestrate_NullConfig_DoesNothing() {
        orchestrator.orchestrate("proj-1", "v1", null);
        verifyNoInteractions(scanTaskRepository);
    }

    @Test
    void testOrchestrate_Enabled_RunsFourSubTasks_WithoutTestGen() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);
        config.setAutoGenerateTestCase(false);
        config.setMinConfidence(0.6);

        // 无文档
        when(documentRepository.selectList(any())).thenReturn(List.of());
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

        orchestrator.orchestrate("proj-1", "v1", config);

        // 创建了 3 个子任务（DOC_EXTRACT, FEATURE_MAPPING, REVIEW_PREPARE），未创建 TEST_GENERATE
        ArgumentCaptor<ScanTask> taskCaptor = ArgumentCaptor.forClass(ScanTask.class);
        verify(scanTaskRepository, times(3)).insert(taskCaptor.capture());
        List<String> types = taskCaptor.getAllValues().stream().map(ScanTask::getTaskType).toList();
        assertTrue(types.contains("AI_DOC_EXTRACT"));
        assertTrue(types.contains("AI_FEATURE_MAPPING"));
        assertTrue(types.contains("AI_REVIEW_PREPARE"));
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
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Page.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        // API 节点用于 feature mapping 与 test gen
        List<GraphNode> apis = List.of(node(NodeType.ApiEndpoint.name(), "api:/order", "下单接口", 0.8));
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.ApiEndpoint.name()), any(), any(), any(), anyInt()))
                .thenReturn(apis);
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), isNull(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        FeatureMappingAgent.MappingResult mapping = new FeatureMappingAgent.MappingResult();
        when(featureMappingAgent.mapFeatures(any())).thenReturn(mapping);

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

        orchestrator.orchestrate("proj-1", "v1", config);

        // 创建了 4 个子任务
        ArgumentCaptor<ScanTask> taskCaptor = ArgumentCaptor.forClass(ScanTask.class);
        verify(scanTaskRepository, times(4)).insert(taskCaptor.capture());
        assertTrue(taskCaptor.getAllValues().stream()
                .anyMatch(t -> "AI_TEST_GENERATE".equals(t.getTaskType())));

        // 生成的测试用例被持久化
        verify(testCaseAgent, atLeastOnce()).generateTestCases(any());
        ArgumentCaptor<TestCase> testCaseCaptor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository, times(1)).insert(testCaseCaptor.capture());
        TestCase persisted = testCaseCaptor.getValue();
        assertEquals("用户已登录", persisted.getPreconditions());
        assertTrue(persisted.getExpectedResult().contains("$.code == 0"));
        assertTrue(persisted.getSteps().contains("\"method\":\"POST\""));
    }

    @Test
    void testOrchestrate_FeatureMapping_UsesRealNodeLabelsAndPersistsPendingEdgeAndReview() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);
        config.setAutoGenerateTestCase(false);
        config.setMinConfidence(0.5);

        GraphNode page = node(NodeType.Page.name(), "page:/order", "订单页", 0.9);
        GraphNode api = node(NodeType.ApiEndpoint.name(), "api:/order", "下单接口", 0.9);
        when(documentRepository.selectList(any())).thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Page.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of(page));
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.ApiEndpoint.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of(api));
        when(neo4jGraphDao.findNode(eq("proj-1"), eq("v1"), eq(NodeType.Page.name()), eq("page:/order")))
                .thenReturn(java.util.Optional.of(page));
        when(neo4jGraphDao.findNode(eq("proj-1"), eq("v1"), eq(NodeType.ApiEndpoint.name()), eq("api:/order")))
                .thenReturn(java.util.Optional.of(api));
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), isNull(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(reviewRecordRepository.selectCount(any())).thenReturn(0L);

        FeatureMappingAgent.Mapping mapping = new FeatureMappingAgent.Mapping();
        mapping.setPageKey("page:/order");
        mapping.setApiKey("api:/order");
        mapping.setBusinessAction("订单提交");
        mapping.setConfidence(0.82);
        FeatureMappingAgent.MappingResult result = new FeatureMappingAgent.MappingResult();
        result.setMappings(List.of(mapping));
        when(featureMappingAgent.mapFeatures(any())).thenReturn(result);

        orchestrator.orchestrate("proj-1", "v1", config);

        ArgumentCaptor<GraphEdge> edgeCaptor = ArgumentCaptor.forClass(GraphEdge.class);
        verify(neo4jGraphDao).createEdge(edgeCaptor.capture());
        GraphEdge edge = edgeCaptor.getValue();
        assertEquals(page.getId(), edge.getFromNodeId());
        assertEquals(api.getId(), edge.getToNodeId());
        assertEquals(EdgeType.CALLS.name(), edge.getEdgeType());
        assertEquals("AI_FEATURE_MAPPING", edge.getSourceType());
        assertEquals("PENDING_CONFIRM", edge.getStatus());

        ArgumentCaptor<ReviewRecord> reviewCaptor = ArgumentCaptor.forClass(ReviewRecord.class);
        verify(reviewRecordRepository).insert(reviewCaptor.capture());
        assertTrue(reviewCaptor.getValue().getComment().contains("订单提交"));
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
        when(factRepository.selectCount(any())).thenReturn(0L);

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

        orchestrator.orchestrate("proj-1", "v1", config);

        ArgumentCaptor<Fact> factCaptor = ArgumentCaptor.forClass(Fact.class);
        verify(factRepository, times(7)).insert(factCaptor.capture());
        List<String> factTypes = factCaptor.getAllValues().stream().map(Fact::getFactType).toList();
        assertTrue(factTypes.contains("BUSINESS_DOMAIN"));
        assertTrue(factTypes.contains("BUSINESS_PROCESS"));
        assertTrue(factTypes.contains("BUSINESS_OBJECT"));
        assertTrue(factTypes.contains("BUSINESS_RULE"));
        assertTrue(factTypes.contains("BUSINESS_ROLE"));
        assertTrue(factTypes.contains("STATUS_TRANSITION"));
        assertTrue(factTypes.contains("FEATURE"));
        verify(businessGraphBuilder).buildBusinessGraph(eq("proj-1"), eq("v1"), eq(extraction), anyString());
    }
}
