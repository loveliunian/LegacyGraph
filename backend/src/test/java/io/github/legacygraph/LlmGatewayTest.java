package io.github.legacygraph;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.entity.PromptRun;
import io.github.legacygraph.llm.LlmCallException;
import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.llm.PiiMaskingService;
import io.github.legacygraph.llm.PromptTemplateLoader;
import io.github.legacygraph.llm.ReasoningModelClient;
import io.github.legacygraph.llm.SecretScanService;
import io.github.legacygraph.repository.AgentRunRepository;
import io.github.legacygraph.repository.PromptRunRepository;
import io.github.legacygraph.service.system.CacheService;
import io.github.legacygraph.service.system.LlmProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.retry.support.RetryTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.system.PromptTemplateService;

/**
 * LlmGateway Phase 0 加固行为测试。
 *
 * <p>核心断言：失败不再返回空对象，而是抛出 {@link LlmCallException}；
 * PromptRun 审计记录写入 inputHash，并在失败时落 FAILED 状态。</p>
 */
@ExtendWith(MockitoExtension.class)
class LlmGatewayTest {

    @Mock
    private PromptRunRepository promptRunRepository;
    @Mock
    private AgentRunRepository agentRunRepository;
    @Mock
    private LlmProviderService llmProviderService;

    private LlmGateway llmGateway;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        // 模板加载器：DB 无模板时回退到 classpath 文件
        io.github.legacygraph.service.system.PromptTemplateService promptTemplateService =
                org.mockito.Mockito.mock(io.github.legacygraph.service.system.PromptTemplateService.class);
        org.mockito.Mockito.lenient().when(promptTemplateService.getActiveByCode(
                org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        PromptTemplateLoader templateLoader = new PromptTemplateLoader(promptTemplateService);
        PiiMaskingService piiMaskingService = new PiiMaskingService();
        SecretScanService secretScanService = new SecretScanService();
        ReasoningModelClient reasoningModelClient =
                org.mockito.Mockito.mock(ReasoningModelClient.class);
        llmGateway = new LlmGateway(objectMapper, promptRunRepository, agentRunRepository, templateLoader,
                piiMaskingService, secretScanService, llmProviderService, new RetryTemplate(),
                reasoningModelClient);
    }

    @Test
    void testConstruction() {
        assertNotNull(llmGateway);
    }

    @Test
    void testCallWithTemplate_NoProvider_ThrowsLlmCallExceptionAndAuditsFailure() {
        // given: 没有可用提供商
        when(llmProviderService.getActiveDefault()).thenReturn(null);
        // 让 insert 模拟 DB 生成自增主键
        doAnswer(inv -> {
            PromptRun run = inv.getArgument(0);
            run.setId(123L);
            return 1;
        }).when(promptRunRepository).insert(any(PromptRun.class));

        Map<String, String> vars = Map.of(
                "candidateAKey", "a", "candidateAInfo", "infoA",
                "candidateBKey", "b", "candidateBInfo", "infoB",
                "nameScore", "0.9", "semanticScore", "0.8", "structScore", "0.7",
                "neighborScore", "0.6", "evidenceScore", "0.5");

        // when & then: 不再静默返回空对象，而是显式抛出
        LlmCallException ex = assertThrows(LlmCallException.class,
                () -> llmGateway.callWithTemplate("proj-1", "graph-merge-decision",
                        vars, GraphMergeDecision.class));
        assertFalse(ex.isNeedsReview(), "提供商缺失属调用失败，非 needs_review");

        // 审计：插入一次 RUNNING，更新一次 FAILED，且写入了 inputHash
        verify(promptRunRepository).insert(any(PromptRun.class));
        ArgumentCaptor<PromptRun> captor = ArgumentCaptor.forClass(PromptRun.class);
        verify(promptRunRepository).updateById(captor.capture());

        PromptRun audited = captor.getValue();
        assertEquals("FAILED", audited.getStatus());
        assertNotNull(audited.getInputHash(), "应写入 inputHash 用于缓存/去重");
        assertEquals(64, audited.getInputHash().length(), "SHA-256 十六进制应为 64 位");
    }

    @Test
    void testCallWithTemplate_CacheHit_SkipsLlmCall() {
        // given: 注入 CacheService 并命中缓存
        CacheService cacheService = org.mockito.Mockito.mock(CacheService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(llmGateway, "cacheService", cacheService);

        Map<String, String> vars = Map.of(
                "candidateAKey", "a", "candidateAInfo", "infoA",
                "candidateBKey", "b", "candidateBInfo", "infoB",
                "nameScore", "0.9", "semanticScore", "0.8", "structScore", "0.7",
                "neighborScore", "0.6", "evidenceScore", "0.5");
        String cachedJson = "{\"candidateA\":\"a\",\"candidateB\":\"b\",\"decision\":\"REVIEW\",\"score\":0.7}";
        when(cacheService.getString(org.mockito.ArgumentMatchers.startsWith("llm:result:")))
                .thenReturn(cachedJson);

        // when: 命中缓存，不应触发提供商获取 / ChatModel 调用 / PromptRun 写入
        GraphMergeDecision result = llmGateway.callWithTemplate("proj-1", "graph-merge-decision",
                vars, GraphMergeDecision.class);

        // then
        assertNotNull(result);
        assertEquals(GraphMergeDecision.Decision.REVIEW, result.getDecision());
        verifyNoInteractions(llmProviderService);
        verifyNoInteractions(promptRunRepository);
    }

    @Test
    void callWithEnvelope_strictPolicyMissingEvidenceRejectsBeforeProviderLookup() {
        AgentEnvelope<Object> envelope = AgentEnvelope.builder()
                .projectId("proj-1")
                .taskId("task-1")
                .agentType("PatchPlanAgent")
                .evidenceCatalog(AgentEnvelope.EvidenceCatalog.builder()
                        .requiredEvidenceTypes(List.of("TEST_RESULT"))
                        .build())
                .policy(AgentEnvelope.RequiredEvidencePolicy.strict())
                .build();

        LlmCallException ex = assertThrows(LlmCallException.class,
                () -> llmGateway.callWithEnvelope(envelope, "patch-plan", Map.of(), GraphMergeDecision.class));

        assertTrue(ex.isNeedsReview());
        assertTrue(ex.getMessage().contains("Required evidence missing"));
        verifyNoInteractions(llmProviderService);
        verifyNoInteractions(promptRunRepository);
        verifyNoInteractions(agentRunRepository);
    }
}
