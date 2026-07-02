package io.github.legacygraph.agent;

import io.github.legacygraph.dto.DbSchemaAnalysis;
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
 * DbSchemaAnalysisAgent 单元测试。
 * 验证 LLM 调用的委托与 Schema 语义分析。
 */
@ExtendWith(MockitoExtension.class)
class DbSchemaAnalysisAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private DbSchemaAnalysisAgent agent;

    /**
     * 测试正常 Schema 分析委托给 LlmGateway。
     */
    @Test
    void analyze_delegatesToLlmGateway() {
        DbSchemaAnalysis expected = new DbSchemaAnalysis();
        expected.setDomains(List.of(new DbSchemaAnalysis.BusinessDomain()));
        when(llmGateway.callWithTemplate(eq("project-1"), eq("db-schema-analysis"),
                anyMap(), eq(DbSchemaAnalysis.class)))
                .thenReturn(expected);

        String schemaText = "CREATE TABLE orders (id INT, amount DECIMAL)";
        DbSchemaAnalysis result = agent.analyze("project-1", schemaText);

        assertNotNull(result);
        assertNotNull(result.getDomains());
        assertEquals(1, result.getDomains().size());
        verify(llmGateway).callWithTemplate(eq("project-1"), eq("db-schema-analysis"),
                anyMap(), eq(DbSchemaAnalysis.class));
    }

    /**
     * 测试空 Schema 输入时仍正常调用 LLM。
     */
    @Test
    void analyzeWithNullSchema_delegatesWithFallbackValue() {
        when(llmGateway.callWithTemplate(eq("project-1"), eq("db-schema-analysis"),
                anyMap(), eq(DbSchemaAnalysis.class)))
                .thenReturn(new DbSchemaAnalysis());

        DbSchemaAnalysis result = agent.analyze("project-1", null);

        assertNotNull(result);
        verify(llmGateway).callWithTemplate(eq("project-1"), eq("db-schema-analysis"),
                anyMap(), eq(DbSchemaAnalysis.class));
    }

    /**
     * 测试 LLM 调用失败时异常传播。
     */
    @Test
    void analyzeWhenLlmFails_propagatesException() {
        when(llmGateway.callWithTemplate(anyString(), anyString(), anyMap(),
                eq(DbSchemaAnalysis.class)))
                .thenThrow(new io.github.legacygraph.llm.LlmCallException(
                        "LLM 调用失败", new RuntimeException("超时")));

        assertThrows(io.github.legacygraph.llm.LlmCallException.class,
                () -> agent.analyze("project-1", "schema"));
    }
}
