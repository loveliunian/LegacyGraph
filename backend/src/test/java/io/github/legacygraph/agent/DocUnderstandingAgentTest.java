package io.github.legacygraph.agent;

import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * DocUnderstandingAgentTest — 验证 toClaimDrafts() 的证据链修复（P0-1）。
 *
 * 覆盖场景：
 * 1. BusinessProcess 的 EvidenceRef 被传递到 draft.transientEvidenceRefs
 * 2. draft.evidenceIds 不再包含 sourcePath（由 KnowledgeClaimService 后续填充真实 UUID）
 * 3. BusinessDomain 的 evidenceText 被传递到 draft.transientContentExcerpt
 * 4. BusinessObject / BusinessRule 的 evidence 被传递到 draft.transientEvidenceRefs
 */
class DocUnderstandingAgentTest {

    @Test
    void toClaimDraftsPopulatesTransientEvidenceRefsForBusinessProcess() {
        DocUnderstandingAgent agent = new DocUnderstandingAgent(mock(LlmGateway.class));
        DocUnderstandingAgent.BusinessFactExtraction extraction = new DocUnderstandingAgent.BusinessFactExtraction();

        DocUnderstandingAgent.BusinessProcess process = new DocUnderstandingAgent.BusinessProcess();
        process.setName("下单流程");
        process.setConfidence(0.9);
        DocUnderstandingAgent.EvidenceRef ref = new DocUnderstandingAgent.EvidenceRef();
        ref.setSourceUri("doc/order.md");
        ref.setLineStart(10);
        ref.setLineEnd(20);
        ref.setExcerpt("用户点击下单按钮后触发订单创建");
        process.setEvidence(List.of(ref));
        extraction.setBusinessProcesses(List.of(process));

        List<KnowledgeClaimDraft> drafts = agent.toClaimDrafts("p1", "v1", extraction, "doc.md");

        KnowledgeClaimDraft processDraft = drafts.stream()
                .filter(d -> "BusinessProcess".equals(d.getSubjectType()))
                .findFirst().orElseThrow();
        assertNotNull(processDraft.getTransientEvidenceRefs());
        assertEquals(1, processDraft.getTransientEvidenceRefs().size());
        assertEquals("doc/order.md", processDraft.getTransientEvidenceRefs().get(0).getSourceUri());
        assertEquals(10, processDraft.getTransientEvidenceRefs().get(0).getLineStart());
        assertEquals(20, processDraft.getTransientEvidenceRefs().get(0).getLineEnd());
    }

    @Test
    void toClaimDraftsDoesNotPutSourcePathIntoEvidenceIds() {
        DocUnderstandingAgent agent = new DocUnderstandingAgent(mock(LlmGateway.class));
        DocUnderstandingAgent.BusinessFactExtraction extraction = new DocUnderstandingAgent.BusinessFactExtraction();
        extraction.setFeatures(List.of("订单创建"));

        List<KnowledgeClaimDraft> drafts = agent.toClaimDrafts("p1", "v1", extraction, "/tmp/doc.md");

        KnowledgeClaimDraft featureDraft = drafts.stream()
                .filter(d -> "Feature".equals(d.getSubjectType()))
                .findFirst().orElseThrow();
        // evidenceIds 应为空列表，不再包含 sourcePath
        assertNotNull(featureDraft.getEvidenceIds());
        assertTrue(featureDraft.getEvidenceIds().isEmpty(),
                "evidenceIds 不应包含 sourcePath，真实 UUID 由 KnowledgeClaimService 填充");
        assertFalse(featureDraft.getEvidenceIds().contains("/tmp/doc.md"));
    }

    @Test
    void toClaimDraftsPopulatesTransientContentExcerptForBusinessDomain() {
        DocUnderstandingAgent agent = new DocUnderstandingAgent(mock(LlmGateway.class));
        DocUnderstandingAgent.BusinessFactExtraction extraction = new DocUnderstandingAgent.BusinessFactExtraction();

        DocUnderstandingAgent.BusinessDomain domain = new DocUnderstandingAgent.BusinessDomain();
        domain.setName("电商交易");
        domain.setConfidence(0.85);
        domain.setEvidenceText("本系统涵盖订单、支付、物流等核心交易域");
        extraction.setBusinessDomains(List.of(domain));

        List<KnowledgeClaimDraft> drafts = agent.toClaimDrafts("p1", "v1", extraction, "doc.md");

        KnowledgeClaimDraft domainDraft = drafts.stream()
                .filter(d -> "BusinessDomain".equals(d.getSubjectType()))
                .findFirst().orElseThrow();
        assertEquals("本系统涵盖订单、支付、物流等核心交易域", domainDraft.getTransientContentExcerpt());
        // BusinessDomain 没有 EvidenceRef 列表
        assertNull(domainDraft.getTransientEvidenceRefs());
    }

    @Test
    void toClaimDraftsPopulatesEvidenceForBusinessObjectAndRule() {
        DocUnderstandingAgent agent = new DocUnderstandingAgent(mock(LlmGateway.class));
        DocUnderstandingAgent.BusinessFactExtraction extraction = new DocUnderstandingAgent.BusinessFactExtraction();

        DocUnderstandingAgent.BusinessObject object = new DocUnderstandingAgent.BusinessObject();
        object.setName("订单");
        object.setConfidence(0.8);
        DocUnderstandingAgent.EvidenceRef objRef = new DocUnderstandingAgent.EvidenceRef();
        objRef.setSourceUri("doc/order.md");
        objRef.setExcerpt("订单是交易的核心对象");
        object.setEvidence(List.of(objRef));

        DocUnderstandingAgent.BusinessRule rule = new DocUnderstandingAgent.BusinessRule();
        rule.setName("库存校验");
        rule.setConfidence(0.75);
        DocUnderstandingAgent.EvidenceRef ruleRef = new DocUnderstandingAgent.EvidenceRef();
        ruleRef.setSourceUri("doc/rule.md");
        ruleRef.setLineStart(5);
        ruleRef.setExcerpt("下单前必须校验库存");
        rule.setEvidence(List.of(ruleRef));

        extraction.setBusinessObjects(List.of(object));
        extraction.setBusinessRules(List.of(rule));

        List<KnowledgeClaimDraft> drafts = agent.toClaimDrafts("p1", "v1", extraction, "doc.md");

        KnowledgeClaimDraft objDraft = drafts.stream()
                .filter(d -> "BusinessObject".equals(d.getSubjectType()))
                .findFirst().orElseThrow();
        assertNotNull(objDraft.getTransientEvidenceRefs());
        assertEquals(1, objDraft.getTransientEvidenceRefs().size());
        assertEquals("订单是交易的核心对象", objDraft.getTransientEvidenceRefs().get(0).getExcerpt());

        KnowledgeClaimDraft ruleDraft = drafts.stream()
                .filter(d -> "BusinessRule".equals(d.getSubjectType()))
                .findFirst().orElseThrow();
        assertNotNull(ruleDraft.getTransientEvidenceRefs());
        assertEquals(1, ruleDraft.getTransientEvidenceRefs().size());
        assertEquals(5, ruleDraft.getTransientEvidenceRefs().get(0).getLineStart());
    }

    @Test
    void toClaimDraftsLeavesEvidenceIdsEmptyWhenNoEvidence() {
        DocUnderstandingAgent agent = new DocUnderstandingAgent(mock(LlmGateway.class));
        DocUnderstandingAgent.BusinessFactExtraction extraction = new DocUnderstandingAgent.BusinessFactExtraction();

        DocUnderstandingAgent.StatusTransition transition = new DocUnderstandingAgent.StatusTransition();
        transition.setBusinessObject("订单");
        transition.setFromStatus("待支付");
        transition.setToStatus("已支付");
        transition.setTrigger("支付回调");
        transition.setConfidence(0.8);
        extraction.setStatusTransitions(List.of(transition));

        List<KnowledgeClaimDraft> drafts = agent.toClaimDrafts("p1", "v1", extraction, "doc.md");

        KnowledgeClaimDraft transitionDraft = drafts.stream()
                .filter(d -> "StateTransition".equals(d.getSubjectType()))
                .findFirst().orElseThrow();
        assertNotNull(transitionDraft.getEvidenceIds());
        assertTrue(transitionDraft.getEvidenceIds().isEmpty());
    }
}
