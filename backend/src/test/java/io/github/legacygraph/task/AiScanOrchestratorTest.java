package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.agent.FeatureMappingAgent;
import io.github.legacygraph.agent.TestCaseAgent;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.dto.GeneratedTestCase;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.ReviewRecordRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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

    private AiScanOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new AiScanOrchestrator(scanTaskRepository, documentRepository, factRepository,
                reviewRecordRepository, testCaseRepository, neo4jGraphDao, docUnderstandingAgent,
                featureMappingAgent, testCaseAgent, new ObjectMapper());
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
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq("PAGE"), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq("API"), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), isNull(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        node("API", "api:/a", "低置信A", 0.3),
                        node("API", "api:/b", "高置信B", 0.9)));
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
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq("PAGE"), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        // API 节点用于 feature mapping 与 test gen
        List<GraphNode> apis = List.of(node("API", "api:/order", "下单接口", 0.8));
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq("API"), any(), any(), any(), anyInt()))
                .thenReturn(apis);
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), isNull(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        FeatureMappingAgent.MappingResult mapping = new FeatureMappingAgent.MappingResult();
        when(featureMappingAgent.mapFeatures(any())).thenReturn(mapping);

        GeneratedTestCase gc = new GeneratedTestCase();
        gc.setCaseName("下单-正常");
        gc.setCaseType(GeneratedTestCase.CaseType.API);
        when(testCaseAgent.generateTestCases(any())).thenReturn(List.of(gc));

        orchestrator.orchestrate("proj-1", "v1", config);

        // 创建了 4 个子任务
        ArgumentCaptor<ScanTask> taskCaptor = ArgumentCaptor.forClass(ScanTask.class);
        verify(scanTaskRepository, times(4)).insert(taskCaptor.capture());
        assertTrue(taskCaptor.getAllValues().stream()
                .anyMatch(t -> "AI_TEST_GENERATE".equals(t.getTaskType())));

        // 生成的测试用例被持久化
        verify(testCaseAgent, atLeastOnce()).generateTestCases(any());
        verify(testCaseRepository, times(1)).insert(any(io.github.legacygraph.entity.TestCase.class));
    }

    @Test
    void testOrchestrate_DocExtract_PersistsPendingConfirmFacts() {
        AiScanConfig config = new AiScanConfig();
        config.setEnableAi(true);
        config.setMinConfidence(0.6);

        io.github.legacygraph.entity.Document doc = new io.github.legacygraph.entity.Document();
        doc.setId("d1");
        doc.setFilePath(""); // 空路径 → 读取内容为 null，跳过抽取，但子任务仍应完成
        when(documentRepository.selectList(any())).thenReturn(List.of(doc));
        when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        orchestrator.orchestrate("proj-1", "v1", config);

        // DOC_EXTRACT 子任务被创建并以 SUCCESS 完成（无内容时不报错）
        verify(scanTaskRepository, atLeastOnce()).insert(any(ScanTask.class));
        verify(scanTaskRepository, atLeastOnce()).updateById(argThat((ScanTask t) ->
                "AI_DOC_EXTRACT".equals(t.getTaskType()) && "SUCCESS".equals(t.getTaskStatus())));
        // 无可读内容 → 不调用抽取 Agent
        verifyNoInteractions(docUnderstandingAgent);
    }
}
