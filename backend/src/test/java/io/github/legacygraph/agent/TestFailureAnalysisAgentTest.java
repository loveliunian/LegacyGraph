package io.github.legacygraph.agent;

import io.github.legacygraph.dto.TestFailureAnalysis;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TestFailureAnalysisAgent 测试。
 */
@ExtendWith(MockitoExtension.class)
class TestFailureAnalysisAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private TestFailureAnalysisAgent agent;

    @Captor
    private ArgumentCaptor<Map<String, String>> variablesCaptor;

    @Test
    void testAnalyze_MapsContextToVariables() {
        TestFailureAnalysisAgent.FailureContext ctx = new TestFailureAnalysisAgent.FailureContext();
        ctx.setProjectId("proj-1");
        ctx.setCaseName("登录-正常场景");
        ctx.setTargetNode("api:/auth/login");
        ctx.setRequest("{\"username\":\"a\"}");
        ctx.setResponse("500 Internal Server Error");
        ctx.setErrorMessage("NullPointerException at AuthService:42");
        ctx.setGraphPath("login -> AuthService -> t_user");
        ctx.setRecentTrace("trace-123");

        TestFailureAnalysis expected = new TestFailureAnalysis();
        expected.setSummary("空指针，疑似用户不存在未处理");
        expected.setShouldLowerConfidence(true);

        when(llmGateway.callWithTemplate(eq("proj-1"), eq("test-failure-analysis"),
                anyMap(), eq(TestFailureAnalysis.class))).thenReturn(expected);

        TestFailureAnalysis result = agent.analyze(ctx);

        assertNotNull(result);
        assertTrue(result.getShouldLowerConfidence());

        verify(llmGateway).callWithTemplate(eq("proj-1"), eq("test-failure-analysis"),
                variablesCaptor.capture(), eq(TestFailureAnalysis.class));
        Map<String, String> vars = variablesCaptor.getValue();
        assertEquals("登录-正常场景", vars.get("caseName"));
        assertEquals("api:/auth/login", vars.get("targetNode"));
        assertEquals("NullPointerException at AuthService:42", vars.get("errorMessage"));
        assertEquals("login -> AuthService -> t_user", vars.get("graphPath"));
    }

    @Test
    void testAnalyze_NullFields_BecomeEmptyStrings() {
        TestFailureAnalysisAgent.FailureContext ctx = new TestFailureAnalysisAgent.FailureContext();
        ctx.setProjectId("proj-1");
        // 其余字段为 null

        when(llmGateway.callWithTemplate(any(), eq("test-failure-analysis"),
                anyMap(), eq(TestFailureAnalysis.class))).thenReturn(new TestFailureAnalysis());

        agent.analyze(ctx);

        verify(llmGateway).callWithTemplate(any(), eq("test-failure-analysis"),
                variablesCaptor.capture(), eq(TestFailureAnalysis.class));
        Map<String, String> vars = variablesCaptor.getValue();
        assertEquals("", vars.get("caseName"));
        assertEquals("", vars.get("errorMessage"));
        assertEquals("", vars.get("recentTrace"));
    }
}
