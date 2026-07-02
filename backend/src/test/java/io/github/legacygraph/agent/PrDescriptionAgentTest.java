package io.github.legacygraph.agent;

import io.github.legacygraph.dto.PrDescription;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PrDescriptionAgent 单元测试。
 * 验证 PR 描述生成委托 LLM 与参数处理。
 */
@ExtendWith(MockitoExtension.class)
class PrDescriptionAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private PrDescriptionAgent agent;

    /**
     * 测试正常 PR 描述生成。
     */
    @Test
    void generate_delegatesToLlmGateway() {
        PrDescription expected = new PrDescription();
        expected.setPrTitle("fix: 修复订单创建 bug");
        expected.setPrBody("修复 OrderService.createOrder 中的 NPE");
        when(llmGateway.callWithTemplate(eq("project-1"), eq("pr-description"),
                anyMap(), eq(PrDescription.class)))
                .thenReturn(expected);

        PrDescription result = agent.generate(
                "project-1", "fix/order-npe", "#123",
                "diff --git a/OrderService.java b/OrderService.java");

        assertNotNull(result);
        assertEquals("fix: 修复订单创建 bug", result.getPrTitle());
        verify(llmGateway).callWithTemplate(eq("project-1"), eq("pr-description"),
                anyMap(), eq(PrDescription.class));
    }

    /**
     * 测试空 issue 时的兜底值。
     */
    @Test
    void generateWithNullIssue_usesFallbackValue() {
        when(llmGateway.callWithTemplate(eq("project-1"), eq("pr-description"),
                anyMap(), eq(PrDescription.class)))
                .thenReturn(new PrDescription());

        PrDescription result = agent.generate("project-1", "feature/x", null, "diff");

        assertNotNull(result);
        verify(llmGateway).callWithTemplate(eq("project-1"), eq("pr-description"),
                anyMap(), eq(PrDescription.class));
    }

    /**
     * 测试空 diff 时的处理。
     */
    @Test
    void generateWithNullDiff_doesNotThrow() {
        when(llmGateway.callWithTemplate(anyString(), anyString(), anyMap(),
                eq(PrDescription.class)))
                .thenReturn(new PrDescription());

        assertDoesNotThrow(() -> agent.generate("project-1", "branch", "issue", null));
        verify(llmGateway).callWithTemplate(anyString(), eq("pr-description"),
                anyMap(), eq(PrDescription.class));
    }
}
