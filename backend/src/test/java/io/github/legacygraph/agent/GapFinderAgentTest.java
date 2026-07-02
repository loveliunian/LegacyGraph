package io.github.legacygraph.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.entity.Evidence;
import io.github.legacygraph.entity.GapTask;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GapFinderAgentTest {

    @Mock
    private LlmGateway llmGateway;

    private GapFinderAgent agent;

    @BeforeEach
    void setUp() {
        agent = new GapFinderAgent(llmGateway, new ObjectMapper());
    }

    @Test
    void adviseUsesEnvelopeTemplateAndEvidenceIds() {
        GapFinderAgent.GapAdviceResponse response = new GapFinderAgent.GapAdviceResponse();
        response.setExplanation("缺少代码入口");
        response.setPriorityScore(BigDecimal.valueOf(0.87));
        response.setSuggestedActions(List.of("补扫 Controller"));
        response.setRequiredEvidenceTypes(List.of("code"));
        response.setNeedsHumanReview(true);
        when(llmGateway.callWithEnvelope(any(), eq("gap-finder"), anyMap(), eq(GapFinderAgent.GapAdviceResponse.class)))
                .thenReturn(response);

        Evidence evidence = new Evidence();
        evidence.setId("ev-1");
        evidence.setEvidenceType("doc");
        evidence.setSourcePath("doc.md");
        KnowledgeClaim claim = new KnowledgeClaim();
        claim.setId("claim-1");
        claim.setSubjectType("Feature");
        claim.setSubjectKey("feature:订单创建");
        claim.setPredicate("DESCRIBED_BY");
        GapTask gap = new GapTask();
        gap.setId("gap-1");
        gap.setGapType("doc_only_feature");
        gap.setTitle("只有文档");
        gap.setSubjectType("Feature");
        gap.setSubjectKey("feature:订单创建");

        GapFinderAgent.GapAdvice advice = agent.advise("project-1", gap, List.of(claim), List.of(evidence));

        assertEquals(BigDecimal.valueOf(0.87), advice.getPriorityScore());
        assertEquals(List.of("补扫 Controller"), advice.getSuggestedActions());

        ArgumentCaptor<AgentEnvelope<?>> envelopeCaptor = ArgumentCaptor.forClass(AgentEnvelope.class);
        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(llmGateway).callWithEnvelope(envelopeCaptor.capture(), eq("gap-finder"),
                varsCaptor.capture(), eq(GapFinderAgent.GapAdviceResponse.class));
        assertEquals("GapFinderAgent", envelopeCaptor.getValue().getAgentType());
        assertTrue(envelopeCaptor.getValue().getEvidenceCatalog().getUsedEvidenceIds().contains("ev-1"));
        assertTrue(varsCaptor.getValue().containsKey("gapTask"));
        assertTrue(varsCaptor.getValue().containsKey("claims"));
        assertTrue(varsCaptor.getValue().containsKey("evidence"));
        verify(llmGateway, never()).callWithTemplate(anyString(), anyString(), anyMap(), any());
    }
}
