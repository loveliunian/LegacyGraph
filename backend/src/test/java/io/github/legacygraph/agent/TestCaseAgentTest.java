package io.github.legacygraph.agent;

import io.github.legacygraph.agent.TestCaseAgent.TestGenerationRequest;
import io.github.legacygraph.dto.GeneratedTestCase;
import io.github.legacygraph.dto.TestCaseGenerationResult;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test cases for {@link TestCaseAgent}.
 *
 * <p>Phase 0 契约对齐后：模板单次返回 {@link TestCaseGenerationResult}（testCases 数组），
 * Agent 解析为多个 {@link GeneratedTestCase}。
 *
 * <p>Covers:
 * <ul>
 *   <li>Normal scenario — LLM 返回多个场景的测试用例</li>
 *   <li>Null scenario — LLM 返回 null，结果列表为空</li>
 *   <li>Partial fields scenario — 请求字段为 null 时 null-safe 传参</li>
 *   <li>featureKey 缺失时由 Agent 补全</li>
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
        testCaseAgent = new TestCaseAgent(llmGateway);
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

    private TestCaseGenerationResult resultWith(GeneratedTestCase... cases) {
        TestCaseGenerationResult result = new TestCaseGenerationResult();
        result.setTestCases(List.of(cases));
        return result;
    }

    // ==================== Test Case 1: Normal scenario ====================

    @Test
    void testGenerateTestCases_Success() {
        // Arrange
        TestGenerationRequest request = fullRequest();

        GeneratedTestCase normal = new GeneratedTestCase();
        normal.setFeatureKey("user-login");
        normal.setCaseName("用户登录-正常场景");
        normal.setCaseType(GeneratedTestCase.CaseType.API);

        GeneratedTestCase boundary = new GeneratedTestCase();
        boundary.setFeatureKey("user-login");
        boundary.setCaseName("用户登录-边界场景");
        boundary.setCaseType(GeneratedTestCase.CaseType.API);

        when(llmGateway.callWithTemplate(
                eq("project-1"),
                eq("test-case-generation"),
                anyMap(),
                eq(TestCaseGenerationResult.class)
        )).thenReturn(resultWith(normal, boundary));

        // Act
        List<GeneratedTestCase> results = testCaseAgent.generateTestCases(request);

        // Assert
        assertEquals(2, results.size(), "Should return all generated scenarios");
        assertSame(normal, results.get(0));
        assertSame(boundary, results.get(1));

        verify(llmGateway).callWithTemplate(
                eq("project-1"),
                eq("test-case-generation"),
                variablesCaptor.capture(),
                eq(TestCaseGenerationResult.class)
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
                eq(TestCaseGenerationResult.class)
        )).thenReturn(null);

        // Act
        List<GeneratedTestCase> results = testCaseAgent.generateTestCases(request);

        // Assert
        assertNotNull(results, "Result list should never be null");
        assertTrue(results.isEmpty(), "Should return empty list when LLM returns null");
        verify(llmGateway, times(1)).callWithTemplate(
                anyString(), anyString(), anyMap(), eq(TestCaseGenerationResult.class)
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
                eq(TestCaseGenerationResult.class)
        )).thenReturn(resultWith(mockCase));

        // Act
        List<GeneratedTestCase> results = testCaseAgent.generateTestCases(request);

        // Assert
        assertEquals(1, results.size(), "Should still generate 1 case even with partial fields");

        verify(llmGateway).callWithTemplate(
                eq("project-2"),
                eq("test-case-generation"),
                variablesCaptor.capture(),
                eq(TestCaseGenerationResult.class)
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

    // ==================== Test Case 4: featureKey 补全 ====================

    @Test
    void testGenerateTestCases_FillsMissingFeatureKey() {
        // Arrange
        TestGenerationRequest request = fullRequest();

        GeneratedTestCase caseWithoutKey = new GeneratedTestCase();
        caseWithoutKey.setCaseName("缺失 featureKey 的用例");
        caseWithoutKey.setCaseType(GeneratedTestCase.CaseType.API);
        // featureKey 故意留空

        when(llmGateway.callWithTemplate(
                anyString(), anyString(), anyMap(), eq(TestCaseGenerationResult.class)
        )).thenReturn(resultWith(caseWithoutKey));

        // Act
        List<GeneratedTestCase> results = testCaseAgent.generateTestCases(request);

        // Assert
        assertEquals(1, results.size());
        assertEquals("user-login", results.get(0).getFeatureKey(),
                "Agent 应在模板遗漏时补全 featureKey");
    }
}
