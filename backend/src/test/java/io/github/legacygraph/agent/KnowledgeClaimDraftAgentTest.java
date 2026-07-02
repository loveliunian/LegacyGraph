package io.github.legacygraph.agent;

import io.github.legacygraph.dto.FactExtractionResult;
import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class KnowledgeClaimDraftAgentTest {

    @Test
    void docUnderstandingAgentConvertsFeaturesObjectsAndRulesToClaimDrafts() {
        DocUnderstandingAgent agent = new DocUnderstandingAgent(mock(LlmGateway.class));
        DocUnderstandingAgent.BusinessFactExtraction extraction = new DocUnderstandingAgent.BusinessFactExtraction();
        extraction.setFeatures(List.of("订单创建"));
        DocUnderstandingAgent.BusinessObject object = new DocUnderstandingAgent.BusinessObject();
        object.setName("订单");
        object.setConfidence(0.81);
        DocUnderstandingAgent.BusinessRule rule = new DocUnderstandingAgent.BusinessRule();
        rule.setName("库存校验");
        rule.setConfidence(0.76);
        extraction.setBusinessObjects(List.of(object));
        extraction.setBusinessRules(List.of(rule));

        List<KnowledgeClaimDraft> drafts = agent.toClaimDrafts("project-1", "v1", extraction, "doc.md");

        assertTrue(drafts.stream().anyMatch(d -> "Feature".equals(d.getSubjectType())
                && "feature:订单创建".equals(d.getSubjectKey())
                && "DESCRIBED_BY".equals(d.getPredicate())));
        assertTrue(drafts.stream().anyMatch(d -> "BusinessObject".equals(d.getSubjectType())
                && "object:订单".equals(d.getSubjectKey())));
        assertTrue(drafts.stream().anyMatch(d -> "BusinessRule".equals(d.getSubjectType())
                && "rule:库存校验".equals(d.getSubjectKey())));
    }

    @Test
    void codeFactAgentConvertsCodeFactsToFeatureImplementationClaims() {
        CodeFactAgent agent = new CodeFactAgent(mock(LlmGateway.class));
        FactExtractionResult result = new FactExtractionResult();
        FactExtractionResult.FactItem item = new FactExtractionResult.FactItem();
        item.setKey("code-feature:创建订单");
        item.setName("创建订单");
        item.setConfidence(BigDecimal.valueOf(0.83));
        result.setItems(List.of(item));

        List<KnowledgeClaimDraft> drafts = agent.toClaimDrafts("project-1", "v1", result, "/tmp/OrderService.java");

        assertEquals(1, drafts.size());
        KnowledgeClaimDraft draft = drafts.get(0);
        assertEquals("Feature", draft.getSubjectType());
        assertEquals("feature:创建订单", draft.getSubjectKey());
        assertEquals("IMPLEMENTS", draft.getPredicate());
        assertEquals("SourceFile", draft.getObjectType());
        assertEquals("/tmp/OrderService.java", draft.getObjectKey());
        assertEquals("CODE_AI", draft.getSourceType());
    }

    @Test
    void featureMappingAgentConvertsMappingsToFeatureApiAndPermissionClaims() {
        FeatureMappingAgent agent = new FeatureMappingAgent(mock(LlmGateway.class));
        FeatureMappingAgent.Mapping mapping = new FeatureMappingAgent.Mapping();
        mapping.setBusinessAction("订单创建");
        mapping.setPageKey("page:/orders/new");
        mapping.setApiKey("POST /orders");
        mapping.setPermissionKey("order:create");
        mapping.setConfidence(0.84);
        FeatureMappingAgent.MappingResult result = new FeatureMappingAgent.MappingResult();
        result.setMappings(List.of(mapping));

        List<KnowledgeClaimDraft> drafts = agent.toClaimDrafts("project-1", "v1", result);

        assertTrue(drafts.stream().anyMatch(d -> "Feature".equals(d.getSubjectType())
                && "feature:订单创建".equals(d.getSubjectKey())
                && "EXPOSED_BY".equals(d.getPredicate())
                && "Page".equals(d.getObjectType())));
        assertTrue(drafts.stream().anyMatch(d -> "Feature".equals(d.getSubjectType())
                && "feature:订单创建".equals(d.getSubjectKey())
                && "IMPLEMENTS".equals(d.getPredicate())
                && "ApiEndpoint".equals(d.getObjectType())));
        assertTrue(drafts.stream().anyMatch(d -> "ApiEndpoint".equals(d.getSubjectType())
                && "POST /orders".equals(d.getSubjectKey())
                && "REQUIRES_PERMISSION".equals(d.getPredicate())));
    }
}
