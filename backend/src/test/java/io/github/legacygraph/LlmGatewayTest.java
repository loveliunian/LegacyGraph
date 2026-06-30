package io.github.legacygraph;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.entity.PromptRun;
import io.github.legacygraph.llm.LlmCallException;
import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.llm.PiiMaskingService;
import io.github.legacygraph.llm.PromptTemplateLoader;
import io.github.legacygraph.repository.PromptRunRepository;
import io.github.legacygraph.service.LlmProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
    private LlmProviderService llmProviderService;

    private LlmGateway llmGateway;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        // 使用真实的模板加载器与脱敏服务，更贴近生产路径
        PromptTemplateLoader templateLoader = new PromptTemplateLoader();
        PiiMaskingService piiMaskingService = new PiiMaskingService();
        llmGateway = new LlmGateway(objectMapper, promptRunRepository, templateLoader,
                piiMaskingService, llmProviderService);
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
}
