package io.github.legacygraph.agent;

import io.github.legacygraph.dto.ReportInsight;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReportInsightAgent 测试。
 */
@ExtendWith(MockitoExtension.class)
class ReportInsightAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private ReportInsightAgent agent;

    @Captor
    private ArgumentCaptor<Map<String, String>> variablesCaptor;

    @Test
    void testGenerateInsights_PassesMetricsAndGaps() {
        ReportInsight expected = new ReportInsight();
        expected.setSummary("优先补充低置信 API 证据");
        ReportInsight.ActionItem action = new ReportInsight.ActionItem();
        action.setActionType("ADD_EVIDENCE");
        action.setPriority("HIGH");
        expected.setActions(List.of(action));

        when(llmGateway.callWithTemplate(eq("proj-1"), eq("report-insight"),
                anyMap(), eq(ReportInsight.class))).thenReturn(expected);

        ReportInsight result = agent.generateInsights("proj-1",
                "{\"pendingRatio\":0.3}", "{\"isolated\":5}");

        assertNotNull(result);
        assertEquals(1, result.getActions().size());
        assertEquals("HIGH", result.getActions().get(0).getPriority());

        verify(llmGateway).callWithTemplate(eq("proj-1"), eq("report-insight"),
                variablesCaptor.capture(), eq(ReportInsight.class));
        Map<String, String> vars = variablesCaptor.getValue();
        assertEquals("{\"pendingRatio\":0.3}", vars.get("metrics"));
        assertEquals("{\"isolated\":5}", vars.get("gaps"));
    }

    @Test
    void testGenerateInsights_NullInputs_DefaultToEmptyJson() {
        when(llmGateway.callWithTemplate(any(), eq("report-insight"),
                anyMap(), eq(ReportInsight.class))).thenReturn(new ReportInsight());

        agent.generateInsights("proj-1", null, null);

        verify(llmGateway).callWithTemplate(any(), eq("report-insight"),
                variablesCaptor.capture(), eq(ReportInsight.class));
        assertEquals("{}", variablesCaptor.getValue().get("metrics"));
        assertEquals("{}", variablesCaptor.getValue().get("gaps"));
    }
}
