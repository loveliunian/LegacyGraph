package io.github.legacygraph.agent;

import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.graph.PatchPlan;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatchPlanAgentTest {

    @Mock private LlmGateway llmGateway;

    private PatchPlanAgent agent;

    @BeforeEach
    void setUp() {
        agent = new PatchPlanAgent(llmGateway);
    }

    @Test
    void generate_stampsTaskFieldsAndPassesVariables() {
        PatchPlan raw = PatchPlan.builder().riskLevel("LOW").build();
        when(llmGateway.callWithTemplate(eq("p1"), eq("patch-plan"), anyMap(), eq(PatchPlan.class)))
                .thenReturn(raw);

        ImpactSubgraph sg = ImpactSubgraph.builder()
                .targetName("TicketService")
                .dependencySummary("dep summary")
                .impactedFiles(List.of("src/A.java", "src/B.java"))
                .build();

        PatchPlan result = agent.generate("p1", "chg-1", "修复X", "空指针",
                sg, "evd summary", "TicketServiceTest.fail");

        assertEquals("chg-1", result.getTaskId());
        assertEquals("BUGFIX", result.getTaskType());
        assertEquals("PatchPlanAgent", result.getGeneratedBy());

        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(llmGateway).callWithTemplate(eq("p1"), eq("patch-plan"), varsCaptor.capture(), eq(PatchPlan.class));
        Map<String, String> vars = varsCaptor.getValue();
        assertEquals("TicketService", vars.get("changeTarget"));
        assertEquals("dep summary", vars.get("dependencySummary"));
        assertTrue(vars.get("impactedFiles").contains("src/A.java"));
        assertEquals("evd summary", vars.get("evidenceSummary"));
        assertEquals("TicketServiceTest.fail", vars.get("failingTests"));
    }

    @Test
    void generate_nullPlan_returnsNull() {
        when(llmGateway.callWithTemplate(any(), any(), anyMap(), eq(PatchPlan.class))).thenReturn(null);
        PatchPlan result = agent.generate("p1", "chg-1", "t", "i", null, null, null);
        assertNull(result);
    }
}
