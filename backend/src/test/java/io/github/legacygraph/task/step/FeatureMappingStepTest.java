package io.github.legacygraph.task.step;

import io.github.legacygraph.agent.FeatureMappingAgent;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FeatureMappingStep 单元测试。
 * 验证 batch size 降至 40、空映射重试逻辑。
 */
@ExtendWith(MockitoExtension.class)
class FeatureMappingStepTest {

    @Mock private FeatureMappingAgent featureMappingAgent;
    @Mock private Counter agentCallCounter;

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
}
