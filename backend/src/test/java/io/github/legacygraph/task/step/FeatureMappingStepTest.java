package io.github.legacygraph.task.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.FeatureMappingAgent;
import io.github.legacygraph.builder.BusinessGraphBuilder;
import io.github.legacygraph.builder.EvidenceGraphWriter;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.repository.ReviewRecordRepository;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * FeatureMappingStep 单元测试。
 * 验证 batch size 降至 40、空映射重试逻辑。
 */
@ExtendWith(MockitoExtension.class)
class FeatureMappingStepTest {

    @Mock private FeatureMappingAgent featureMappingAgent;
    @Mock private Counter agentCallCounter;
    @Mock private Neo4jGraphDao neo4jGraphDao;
    @Mock private AiScanStepSupport support;
    @Mock private EvidenceGraphWriter evidenceGraphWriter;
    @Mock private ReviewRecordRepository reviewRecordRepository;
    @Mock private BusinessGraphBuilder businessGraphBuilder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FeatureMappingStep createStep() {
        return new FeatureMappingStep(support, neo4jGraphDao, featureMappingAgent,
                evidenceGraphWriter, reviewRecordRepository, objectMapper, agentCallCounter,
                businessGraphBuilder);
    }

    private static GraphNode node(String key, String name, String type) {
        GraphNode n = new GraphNode();
        n.setNodeKey(key);
        n.setNodeName(name);
        n.setNodeType(type);
        return n;
    }

    /**
     * 验证 FEATURE_MAPPING_BATCH_SIZE 为 40（从 80 降至 40 降低 LLM 上下文负担）。
     */
    @Test
    void batchSizeShouldBe40() throws Exception {
        Field field = FeatureMappingStep.class.getDeclaredField("FEATURE_MAPPING_BATCH_SIZE");
        field.setAccessible(true);
        int batchSize = (int) field.get(null);
        assertEquals(40, batchSize, "batch size 应为 40（从 80 降至 40 降低 LLM 上下文负担）");
    }

    /**
     * 首次返回空映射时应自动重试一次，重试后返回非空结果。
     */
    @Test
    void shouldRetryOnceWhenEmptyMapping() {
        FeatureMappingAgent.MappingRequest request = new FeatureMappingAgent.MappingRequest();
        request.setProductDoc("已有功能点:\n- [Feature] 用户登录 (feature:user-login) — 用户登录");

        // 首次返回空映射，重试后返回非空
        FeatureMappingAgent.MappingResult emptyResult = new FeatureMappingAgent.MappingResult();
        FeatureMappingAgent.MappingResult nonEmptyResult = new FeatureMappingAgent.MappingResult();
        FeatureMappingAgent.Mapping mapping = new FeatureMappingAgent.Mapping();
        mapping.setBusinessAction("user-login");
        mapping.setApiKey("POST /api/auth/login");
        mapping.setConfidence(0.9);
        nonEmptyResult.setMappings(List.of(mapping));

        when(featureMappingAgent.mapFeatures(request))
                .thenReturn(emptyResult)
                .thenReturn(nonEmptyResult);

        FeatureMappingAgent.MappingResult result =
                FeatureMappingStep.mapFeaturesWithRetry(request, featureMappingAgent, agentCallCounter);

        // 验证重试：mapFeatures 被调用 2 次
        verify(featureMappingAgent, times(2)).mapFeatures(request);
        // 验证计数器递增 2 次（首次 + 重试）
        verify(agentCallCounter, times(2)).increment();
        // 验证返回重试后的非空结果
        assertNotNull(result);
        assertEquals(1, result.getMappings().size());
        assertEquals("POST /api/auth/login", result.getMappings().get(0).getApiKey());
        // 验证重试时追加了提示
        assertTrue(request.getProductDoc().contains("[重试提示]"));
    }

    /**
     * 首次返回非空映射时不应重试。
     */
    @Test
    void shouldNotRetryWhenNonEmptyMapping() {
        FeatureMappingAgent.MappingRequest request = new FeatureMappingAgent.MappingRequest();
        request.setProductDoc("已有功能点:\n- [Feature] 用户登录 (feature:user-login)");

        FeatureMappingAgent.MappingResult nonEmptyResult = new FeatureMappingAgent.MappingResult();
        FeatureMappingAgent.Mapping mapping = new FeatureMappingAgent.Mapping();
        mapping.setBusinessAction("user-login");
        mapping.setApiKey("POST /api/auth/login");
        nonEmptyResult.setMappings(List.of(mapping));

        when(featureMappingAgent.mapFeatures(request)).thenReturn(nonEmptyResult);

        FeatureMappingAgent.MappingResult result =
                FeatureMappingStep.mapFeaturesWithRetry(request, featureMappingAgent, agentCallCounter);

        // 验证未重试：只调用 1 次
        verify(featureMappingAgent, times(1)).mapFeatures(request);
        verify(agentCallCounter, times(1)).increment();
        assertNotNull(result);
        assertEquals(1, result.getMappings().size());
        // 验证未追加重试提示
        assertFalse(request.getProductDoc().contains("[重试提示]"));
    }

    /**
     * 重试后仍为空映射时，应返回空结果而非抛异常。
     */
    @Test
    void shouldReturnEmptyWhenRetryAlsoEmpty() {
        FeatureMappingAgent.MappingRequest request = new FeatureMappingAgent.MappingRequest();
        request.setProductDoc("已有功能点:\n- [Feature] 未知功能 (feature:unknown)");

        FeatureMappingAgent.MappingResult emptyResult = new FeatureMappingAgent.MappingResult();
        when(featureMappingAgent.mapFeatures(any())).thenReturn(emptyResult);

        FeatureMappingAgent.MappingResult result =
                FeatureMappingStep.mapFeaturesWithRetry(request, featureMappingAgent, agentCallCounter);

        // 重试一次，共调用 2 次
        verify(featureMappingAgent, times(2)).mapFeatures(any());
        verify(agentCallCounter, times(2)).increment();
        assertNotNull(result);
        assertTrue(result.getMappings() == null || result.getMappings().isEmpty());
    }

    // ──────────────────────────────────────────────
    // Task 5：增量 Feature 映射
    // ──────────────────────────────────────────────

    /**
     * 首次扫描（incremental=false）应全量查询所有节点，不调用 queryAffectedNodes。
     */
    @Test
    void fullScan_shouldQueryAllNodes() {
        StepExecutionContext ctx = StepExecutionContext.builder()
                .projectId("p1").versionId("v1").incremental(false).build();
        when(support.createTask(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ScanTask());
        when(neo4jGraphDao.queryNodes(anyString(), anyString(), eq(NodeType.Feature.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(neo4jGraphDao.queryNodes(anyString(), anyString(), eq(NodeType.Page.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(node("page:home", "首页", "Page")));
        when(neo4jGraphDao.queryNodes(anyString(), anyString(), eq(NodeType.ApiEndpoint.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(node("api:getUser", "GET /api/user", "ApiEndpoint")));

        StepExecutionResult result = createStep().execute(ctx);

        assertTrue(result.isSuccess(), "全量扫描应成功");
        // 全量模式应调用 queryNodes 3 次（Feature/Page/ApiEndpoint）
        verify(neo4jGraphDao, times(3)).queryNodes(anyString(), anyString(), anyString(),
                isNull(), isNull(), isNull(), anyInt());
        // 全量模式不应调用 queryAffectedNodes
        verify(neo4jGraphDao, never()).queryAffectedNodes(anyString(), anyString(), anyString());
    }

    /**
     * 增量扫描（incremental=true）应仅查询 affected 节点，不调用全量 queryNodes。
     */
    @Test
    void incrementalScan_shouldQueryOnlyAffectedNodes() {
        StepExecutionContext ctx = StepExecutionContext.builder()
                .projectId("p1").versionId("v1").incremental(true).build();
        when(support.createTask(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ScanTask());
        when(neo4jGraphDao.queryAffectedNodes(anyString(), anyString(), eq(NodeType.Feature.name())))
                .thenReturn(Collections.emptyList());
        when(neo4jGraphDao.queryAffectedNodes(anyString(), anyString(), eq(NodeType.Page.name())))
                .thenReturn(List.of(node("page:home", "首页", "Page")));
        when(neo4jGraphDao.queryAffectedNodes(anyString(), anyString(), eq(NodeType.ApiEndpoint.name())))
                .thenReturn(List.of(node("api:getUser", "GET /api/user", "ApiEndpoint")));

        StepExecutionResult result = createStep().execute(ctx);

        assertTrue(result.isSuccess(), "增量扫描应成功");
        // 增量模式应调用 queryAffectedNodes 3 次（Feature/Page/ApiEndpoint）
        verify(neo4jGraphDao, times(3)).queryAffectedNodes(anyString(), anyString(), anyString());
        // 增量模式不应调用全量 queryNodes
        verify(neo4jGraphDao, never()).queryNodes(anyString(), anyString(), anyString(),
                isNull(), isNull(), isNull(), anyInt());
    }

    /**
     * 增量模式下 affected 节点为空（没有变更）时应直接返回成功，不调用 LLM 做映射。
     */
    @Test
    void incrementalScan_noAffectedNodes_shouldSkipMapping() {
        StepExecutionContext ctx = StepExecutionContext.builder()
                .projectId("p1").versionId("v1").incremental(true).build();
        when(support.createTask(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ScanTask());
        when(neo4jGraphDao.queryAffectedNodes(anyString(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        StepExecutionResult result = createStep().execute(ctx);

        assertTrue(result.isSuccess(), "affected 为空时应返回成功");
        assertTrue(result.getMessage().contains("跳过"), "应提示跳过映射");
        // 不应调用 LLM
        verify(featureMappingAgent, never()).mapFeatures(any());
        // 不应删除/写入边
        verify(evidenceGraphWriter, never()).upsertEdge(any());
    }
}
