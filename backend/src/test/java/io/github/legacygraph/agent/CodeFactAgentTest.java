package io.github.legacygraph.agent;

import io.github.legacygraph.dto.FactExtractionResult;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CodeFactAgent 测试
 */
@ExtendWith(MockitoExtension.class)
class CodeFactAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private CodeFactAgent codeFactAgent;

    private static final String TEST_PROJECT_ID = "proj-001";
    private static final String TEST_SOURCE_PATH = "src/main/java/com/example/OrderService.java";

    private FactExtractionResult buildSampleResult() {
        FactExtractionResult result = new FactExtractionResult();
        result.setFactType("method");
        result.setProjectId(TEST_PROJECT_ID);

        FactExtractionResult.FactItem item = new FactExtractionResult.FactItem();
        item.setKey("method:createOrder");
        item.setName("createOrder");
        item.setConfidence(new BigDecimal("0.95"));

        FactExtractionResult.EvidenceRef evidence = new FactExtractionResult.EvidenceRef();
        evidence.setSourceType("code");
        evidence.setSourceUri(TEST_SOURCE_PATH);
        evidence.setLineStart(42);
        evidence.setLineEnd(58);
        evidence.setExcerpt("public Order createOrder(CreateOrderRequest request) { ... }");
        item.setEvidence(List.of(evidence));

        result.setItems(List.of(item));
        return result;
    }

    @Test
    void testExtractFacts_HappyPath_ReturnsStructuredResult() {
        // given
        String codeContent = "public Order createOrder(CreateOrderRequest request) { ... }";
        FactExtractionResult expectedResult = buildSampleResult();

        when(llmGateway.callWithTemplate(
                eq(TEST_PROJECT_ID),
                eq("code-fact-extraction"),
                anyMap(),
                eq(FactExtractionResult.class)))
                .thenReturn(expectedResult);

        // when
        FactExtractionResult actualResult = codeFactAgent.extractFacts(
                TEST_PROJECT_ID, codeContent, TEST_SOURCE_PATH);

        // then
        assertNotNull(actualResult);
        assertEquals("method", actualResult.getFactType());
        assertEquals(TEST_PROJECT_ID, actualResult.getProjectId());
        assertNotNull(actualResult.getItems());
        assertEquals(1, actualResult.getItems().size());

        FactExtractionResult.FactItem item = actualResult.getItems().get(0);
        assertEquals("method:createOrder", item.getKey());
        assertEquals("createOrder", item.getName());
        assertEquals(0, new BigDecimal("0.95").compareTo(item.getConfidence()));

        // 验证 LlmGateway 被正确调用 — 变量应包含 codeContent 和 sourcePath
        verify(llmGateway).callWithTemplate(
                eq(TEST_PROJECT_ID),
                eq("code-fact-extraction"),
                argThat((Map<String, String> vars) ->
                        TEST_SOURCE_PATH.equals(vars.get("sourcePath"))
                                && codeContent.equals(vars.get("codeContent"))
                                && TEST_PROJECT_ID.equals(vars.get("projectId"))
                                && vars.size() == 3),
                eq(FactExtractionResult.class));
    }

    @Test
    void testExtractFacts_LlmFailure_ThrowsRuntimeException() {
        // given
        String codeContent = "some invalid code";
        when(llmGateway.callWithTemplate(
                eq(TEST_PROJECT_ID),
                eq("code-fact-extraction"),
                anyMap(),
                eq(FactExtractionResult.class)))
                .thenThrow(new RuntimeException("OpenAI API timeout"));

        // when & then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> codeFactAgent.extractFacts(TEST_PROJECT_ID, codeContent, TEST_SOURCE_PATH));

        assertTrue(exception.getMessage().contains("OpenAI API timeout"));

        // 验证调用仍然发生了
        verify(llmGateway).callWithTemplate(
                eq(TEST_PROJECT_ID),
                eq("code-fact-extraction"),
                anyMap(),
                eq(FactExtractionResult.class));
    }

    @Test
    void testExtractFacts_EmptyCodeContent_PassesThrough() {
        // given
        String codeContent = "";
        FactExtractionResult expectedResult = new FactExtractionResult();
        expectedResult.setFactType("empty");
        expectedResult.setProjectId(TEST_PROJECT_ID);
        expectedResult.setItems(List.of());

        when(llmGateway.callWithTemplate(
                eq(TEST_PROJECT_ID),
                eq("code-fact-extraction"),
                argThat(vars -> "".equals(vars.get("codeContent"))),
                eq(FactExtractionResult.class)))
                .thenReturn(expectedResult);

        // when
        FactExtractionResult actualResult = codeFactAgent.extractFacts(
                TEST_PROJECT_ID, codeContent, TEST_SOURCE_PATH);

        // then
        assertNotNull(actualResult);
        assertEquals("empty", actualResult.getFactType());
        assertNotNull(actualResult.getItems());
        assertTrue(actualResult.getItems().isEmpty());

        // 验证 LlmGateway 的 variables 中包含空字符串
        verify(llmGateway).callWithTemplate(
                eq(TEST_PROJECT_ID),
                eq("code-fact-extraction"),
                argThat((Map<String, String> vars) ->
                        "".equals(vars.get("codeContent"))
                                && TEST_SOURCE_PATH.equals(vars.get("sourcePath"))),
                eq(FactExtractionResult.class));
    }
}
