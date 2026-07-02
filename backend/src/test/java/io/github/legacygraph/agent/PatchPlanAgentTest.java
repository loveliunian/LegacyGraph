package io.github.legacygraph.agent;

import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.graph.PatchPlan;
import io.github.legacygraph.llm.LlmCallException;
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

    @Test
    void generateEnvelope_usesEvidenceContractGateway() {
        PatchPlan raw = PatchPlan.builder().riskLevel("LOW").build();
        when(llmGateway.callWithEnvelope(any(), eq("patch-plan"), anyMap(), eq(PatchPlan.class)))
                .thenReturn(raw);

        AgentEnvelope<PatchPlanAgent.PatchPlanInput> envelope = AgentEnvelope.<PatchPlanAgent.PatchPlanInput>builder()
                .projectId("p1")
                .taskId("chg-1")
                .agentType("PatchPlanAgent")
                .contractId("contract-1")
                .input(PatchPlanAgent.PatchPlanInput.builder()
                        .taskId("chg-1")
                        .title("修复X")
                        .changeTarget("TicketService")
                        .inputIssue("空指针")
                        .dependencySummary("dep summary")
                        .impactedFiles(List.of("src/A.java"))
                        .evidenceSummary("evd summary")
                        .failingTests("TicketServiceTest.fail")
                        .build())
                .evidenceCatalog(AgentEnvelope.EvidenceCatalog.builder()
                        .usedEvidenceIds(List.of("ev-1"))
                        .requiredEvidenceTypes(List.of("TEST_RESULT"))
                        .summary("evd summary")
                        .build())
                .policy(AgentEnvelope.RequiredEvidencePolicy.strict())
                .build();

        PatchPlan result = agent.generate(envelope);

        assertEquals("chg-1", result.getTaskId());
        assertEquals("BUGFIX", result.getTaskType());
        assertEquals("PatchPlanAgent", result.getGeneratedBy());
        verify(llmGateway).callWithEnvelope(eq(envelope), eq("patch-plan"), anyMap(), eq(PatchPlan.class));
        verify(llmGateway, never()).callWithTemplate(any(), any(), anyMap(), eq(PatchPlan.class));
    }

    @Test
    void generateEnvelope_strictMissingEvidenceReturnsManualReviewPlan() {
        when(llmGateway.callWithEnvelope(any(), eq("patch-plan"), anyMap(), eq(PatchPlan.class)))
                .thenThrow(new LlmCallException("Required evidence missing", null, true, null));

        AgentEnvelope<PatchPlanAgent.PatchPlanInput> envelope = AgentEnvelope.<PatchPlanAgent.PatchPlanInput>builder()
                .projectId("p1")
                .taskId("chg-1")
                .input(PatchPlanAgent.PatchPlanInput.builder()
                        .taskId("chg-1")
                        .title("修复X")
                        .build())
                .evidenceCatalog(AgentEnvelope.EvidenceCatalog.builder()
                        .requiredEvidenceTypes(List.of("TEST_RESULT"))
                        .build())
                .policy(AgentEnvelope.RequiredEvidencePolicy.strict())
                .build();

        PatchPlan result = agent.generate(envelope);

        assertNotNull(result);
        assertTrue(result.isManualReviewNeeded());
        assertEquals("chg-1", result.getTaskId());
        assertEquals("BUGFIX", result.getTaskType());
        assertEquals("PatchPlanAgent", result.getGeneratedBy());
    }
}
