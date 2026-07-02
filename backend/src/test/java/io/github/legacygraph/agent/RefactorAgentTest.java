package io.github.legacygraph.agent;

import io.github.legacygraph.dto.RefactorSuggestion;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RefactorAgent 单元测试。
 * 验证代码异味重构建议的 LLM 委托。
 */
@ExtendWith(MockitoExtension.class)
class RefactorAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private RefactorAgent agent;

    /**
     * 测试正常重构建议生成。
     */
    @Test
    void suggest_delegatesToLlmGateway() {
        RefactorSuggestion expected = new RefactorSuggestion();
        expected.setSummary("拆分为 OrderValidator 和 OrderPersister");
        expected.setRisk("MEDIUM");
        expected.setRefactoredSkeleton("class OrderValidator { ... }");
        RefactorSuggestion.SplitSuggestion split = new RefactorSuggestion.SplitSuggestion();
        split.setNewUnit("OrderValidator");
        split.setResponsibility("订单校验");
        split.setMovedMethods(List.of("validateOrder"));
        expected.setSplitSuggestions(List.of(split));
        when(llmGateway.callWithTemplate(eq("project-1"), eq("refactor-suggestion"),
                anyMap(), eq(RefactorSuggestion.class)))
                .thenReturn(expected);

        RefactorSuggestion result = agent.suggest(
                "project-1", "OrderService",
                "GOD_CLASS", "public class OrderService { ... }");

        assertNotNull(result);
        assertEquals("MEDIUM", result.getRisk());
        assertEquals(1, result.getSplitSuggestions().size());
        assertEquals("OrderValidator", result.getSplitSuggestions().get(0).getNewUnit());
        verify(llmGateway).callWithTemplate(eq("project-1"), eq("refactor-suggestion"),
                anyMap(), eq(RefactorSuggestion.class));
    }

    /**
     * 测试空代码输入时的兜底处理。
     */
    @Test
    void suggestWithNullCode_usesEmptyString() {
        when(llmGateway.callWithTemplate(eq("project-1"), eq("refactor-suggestion"),
                anyMap(), eq(RefactorSuggestion.class)))
                .thenReturn(new RefactorSuggestion());

        RefactorSuggestion result = agent.suggest("project-1", "TargetClass", "LONG_METHOD", null);

        assertNotNull(result);
        verify(llmGateway).callWithTemplate(eq("project-1"), eq("refactor-suggestion"),
                anyMap(), eq(RefactorSuggestion.class));
    }

    /**
     * 测试 LLM 调用失败时异常传播。
     */
    @Test
    void suggestWhenLlmFails_propagatesException() {
        when(llmGateway.callWithTemplate(anyString(), anyString(), anyMap(),
                eq(RefactorSuggestion.class)))
                .thenThrow(new io.github.legacygraph.llm.LlmCallException(
                        "LLM 失败", new RuntimeException("超时")));

        assertThrows(io.github.legacygraph.llm.LlmCallException.class,
                () -> agent.suggest("project-1", "Target", "GOD_CLASS", "code"));
    }
}
