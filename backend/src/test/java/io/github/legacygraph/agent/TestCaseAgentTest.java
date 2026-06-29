package io.github.legacygraph.agent;

import io.github.legacygraph.agent.TestCaseAgent.TestGenerationRequest;
import io.github.legacygraph.dto.GeneratedTestCase;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test cases for {@link TestCaseAgent}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Normal scenario — all fields populated, LLM returns a valid GeneratedTestCase</li>
 *   <li>Null scenario — LLM returns null, resulting list should be empty</li>
 *   <li>Partial fields scenario — null-safe handling when request fields are null</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TestCaseAgentTest {

    @Mock
    private LlmGateway llmGateway;

    private TestCaseAgent testCaseAgent;

    @Captor
    private ArgumentCaptor<Map<String, String>> variablesCaptor;

    @BeforeEach
    void setUp() {
        testCaseAgent = new TestCaseAgent();
        ReflectionTestUtils.setField(testCaseAgent, "llmGateway", llmGateway);
    }

    private TestGenerationRequest fullRequest() {
        TestGenerationRequest request = new TestGenerationRequest();
        request.setProjectId("project-1");
        request.setFeatureKey("user-login");
        request.setFeatureName("用户登录");
        request.setApiEndpoint("/api/auth/login");
        request.setHttpMethod("POST");
        request.setRequestSchema("{\"username\":\"string\",\"password\":\"string\"}");
        request.setRelatedTables("t_user,t_login_log");
        request.setBusinessRules("密码错误5次锁定账号");
        return request;
    }

    // ==================== Test Case 1: Normal scenario ====================

    @Test
    void testGenerateTestCases_Success() {
        // Arrange
        TestGenerationRequest request = fullRequest();

        GeneratedTestCase mockCase = new GeneratedTestCase();
        mockCase.setFeatureKey("user-login");
        mockCase.setCaseName("用户登录-正常场景");
        mockCase.setCaseType(GeneratedTestCase.CaseType.API);

        when(llmGateway.callWithTemplate(
                eq("project-1"),
                eq("test-case-generation"),
                anyMap(),
                eq(GeneratedTestCase.class)
        )).thenReturn(mockCase);

        // Act
        List<GeneratedTestCase> results = testCaseAgent.generateTestCases(request);

        // Assert
        assertEquals(1, results.size(), "Should return exactly 1 generated test case");
        assertSame(mockCase, results.getFirst(), "Should return the LLM-generated case");

        verify(llmGateway).callWithTemplate(
                eq("project-1"),
                eq("test-case-generation"),
                variablesCaptor.capture(),
                eq(GeneratedTestCase.class)
        );

        Map<String, String> captured = variablesCaptor.getValue();
        assertEquals("user-login", captured.get("featureKey"));
        assertEquals("用户登录", captured.get("featureName"));
        assertEquals("/api/auth/login", captured.get("apiEndpoint"));
        assertEquals("POST", captured.get("httpMethod"));
        assertEquals("{\"username\":\"string\",\"password\":\"string\"}", captured.get("requestSchema"));
        assertEquals("t_user,t_login_log", captured.get("relatedTables"));
        assertEquals("密码错误5次锁定账号", captured.get("businessRules"));
    }

    // ==================== Test Case 2: LLM returns null ====================

    @Test
    void testGenerateTestCases_NullResponse() {
        // Arrange
        TestGenerationRequest request = fullRequest();

        when(llmGateway.callWithTemplate(
                anyString(),
                anyString(),
                anyMap(),
                eq(GeneratedTestCase.class)
        )).thenReturn(null);

        // Act
        List<GeneratedTestCase> results = testCaseAgent.generateTestCases(request);

        // Assert
        assertNotNull(results, "Result list should never be null");
        assertTrue(results.isEmpty(), "Should return empty list when LLM returns null");
        verify(llmGateway, times(1)).callWithTemplate(
                anyString(), anyString(), anyMap(), eq(GeneratedTestCase.class)
        );
    }

    // ==================== Test Case 3: Partial / null fields ====================

    @Test
    void testGenerateTestCases_PartialFields() {
        // Arrange
        TestGenerationRequest request = new TestGenerationRequest();
        // Only set projectId and featureKey, leave everything else null
        request.setProjectId("project-2");
        request.setFeatureKey("data-export");

        GeneratedTestCase mockCase = new GeneratedTestCase();
        mockCase.setFeatureKey("data-export");
        mockCase.setCaseName("数据导出-正常场景");
        mockCase.setCaseType(GeneratedTestCase.CaseType.E2E);

        when(llmGateway.callWithTemplate(
                eq("project-2"),
                eq("test-case-generation"),
                anyMap(),
                eq(GeneratedTestCase.class)
        )).thenReturn(mockCase);

        // Act
        List<GeneratedTestCase> results = testCaseAgent.generateTestCases(request);

        // Assert
        assertEquals(1, results.size(), "Should still generate 1 case even with partial fields");

        verify(llmGateway).callWithTemplate(
                eq("project-2"),
                eq("test-case-generation"),
                variablesCaptor.capture(),
                eq(GeneratedTestCase.class)
        );

        Map<String, String> captured = variablesCaptor.getValue();
        assertEquals("data-export", captured.get("featureKey"));
        // Null fields should be mapped to empty strings (null-safe)
        assertEquals("", captured.get("featureName"));
        assertEquals("", captured.get("apiEndpoint"));
        assertEquals("", captured.get("httpMethod"));
        assertEquals("", captured.get("requestSchema"));
        assertEquals("", captured.get("relatedTables"));
        assertEquals("", captured.get("businessRules"));
    }
}
