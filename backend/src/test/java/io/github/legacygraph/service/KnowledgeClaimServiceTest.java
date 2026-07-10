package io.github.legacygraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.entity.Evidence;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.repository.EvidenceRepository;
import io.github.legacygraph.repository.KnowledgeClaimRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.graph.KnowledgeClaimService;

@ExtendWith(MockitoExtension.class)
class KnowledgeClaimServiceTest {

    @Mock
    private KnowledgeClaimRepository repository;

    @Mock
    private EvidenceRepository evidenceRepository;

    private KnowledgeClaimService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeClaimService(repository, new ObjectMapper(), evidenceRepository);
    }

    @Test
    void upsertDraft_insertsAiClaimAsPendingConfirm() {
        when(repository.selectList(any())).thenReturn(List.of());

        KnowledgeClaim claim = service.upsertDraft(KnowledgeClaimDraft.builder()
                .projectId("project-1")
                .versionId("v1")
                .subjectType("Feature")
                .subjectKey("feature:订单创建")
                .predicate("DESCRIBED_BY")
                .objectType("Evidence")
                .objectKey("doc.md")
                .sourceType("DOC_AI")
                .confidence(BigDecimal.valueOf(0.95))
                .evidenceIds(List.of("ev-1"))
                .build());

        assertEquals("PENDING_CONFIRM", claim.getStatus());
        assertEquals("NEW", claim.getCompileStatus());
        verify(repository).insert(claim);
    }

    @Test
    void upsertDraft_codeSourceCanConfirmHighConfidenceClaim() {
        when(repository.selectList(any())).thenReturn(List.of());

        KnowledgeClaim claim = service.upsertDraft(KnowledgeClaimDraft.builder()
                .projectId("project-1")
                .versionId("v1")
                .subjectType("ApiEndpoint")
                .subjectKey("POST /orders")
                .predicate("HANDLED_BY")
                .objectType("Method")
                .objectKey("OrderController#create")
                .sourceType("CODE")
                .confidence(BigDecimal.valueOf(0.91))
                .build());

        assertEquals("CONFIRMED", claim.getStatus());
    }

    @Test
    void upsertDraft_mergesEvidenceAndDoesNotDowngradeConfirmedCodeWithAiSource() {
        KnowledgeClaim existing = new KnowledgeClaim();
        existing.setId("claim-1");
        existing.setProjectId("project-1");
        existing.setVersionId("v1");
        existing.setSubjectType("Feature");
        existing.setSubjectKey("feature:订单创建");
        existing.setPredicate("IMPLEMENTS");
        existing.setObjectType("ApiEndpoint");
        existing.setObjectKey("POST /orders");
        existing.setSourceType("CODE");
        existing.setConfidence(BigDecimal.valueOf(0.92));
        existing.setStatus("CONFIRMED");
        existing.setEvidenceIds("[\"ev-code\"]");

        when(repository.selectList(any())).thenReturn(List.of(existing));

        KnowledgeClaim claim = service.upsertDraft(KnowledgeClaimDraft.builder()
                .projectId("project-1")
                .versionId("v1")
                .subjectType("Feature")
                .subjectKey("feature:订单创建")
                .predicate("IMPLEMENTS")
                .objectType("ApiEndpoint")
                .objectKey("POST /orders")
                .sourceType("DOC_AI")
                .confidence(BigDecimal.valueOf(0.8))
                .evidenceIds(List.of("ev-doc"))
                .build());

        assertEquals("CODE", claim.getSourceType());
        assertEquals("CONFIRMED", claim.getStatus());
        assertTrue(claim.getEvidenceIds().contains("ev-code"));
        assertTrue(claim.getEvidenceIds().contains("ev-doc"));
        verify(repository).updateById(existing);
    }

    @Test
    void upsertDraft_rejectsEvidenceReferenceOutsideTheClaimDocument() {
        when(repository.selectList(any())).thenReturn(List.of());
        when(evidenceRepository.insertOrIgnore(any(Evidence.class))).thenReturn(1);

        DocUnderstandingAgent.EvidenceRef foreignRef = new DocUnderstandingAgent.EvidenceRef();
        foreignRef.setSourceUri("/other-project/secret.md");
        foreignRef.setExcerpt("不应被接受的跨文档引用");
        DocUnderstandingAgent.EvidenceRef chunkRef = new DocUnderstandingAgent.EvidenceRef();
        chunkRef.setSourceUri("/project/docs/order.md#chunk2:start100:end500");
        chunkRef.setExcerpt("订单创建需要校验库存");

        service.upsertDraft(KnowledgeClaimDraft.builder()
                .projectId("project-1")
                .versionId("v1")
                .subjectType("Feature")
                .subjectKey("feature:create-order")
                .predicate("DESCRIBED_BY")
                .objectType("Evidence")
                .objectKey("/project/docs/order.md")
                .sourceType("DOC_AI")
                .confidence(BigDecimal.valueOf(0.8))
                .transientEvidenceRefs(List.of(foreignRef, chunkRef))
                .build());

        ArgumentCaptor<Evidence> evidenceCaptor = ArgumentCaptor.forClass(Evidence.class);
        verify(evidenceRepository, times(1)).insertOrIgnore(evidenceCaptor.capture());
        assertEquals("/project/docs/order.md#chunk2:start100:end500", evidenceCaptor.getValue().getSourcePath());
    }

    @Test
    void getClaimScopesLookupByProjectId() {
        KnowledgeClaim claim = new KnowledgeClaim();
        claim.setId("claim-1");
        claim.setProjectId("project-1");
        when(repository.selectOne(any())).thenReturn(claim);

        KnowledgeClaim result = service.getClaim("project-1", "claim-1");

        assertSame(claim, result);
        verify(repository).selectOne(any());
        verify(repository, never()).selectById(any(String.class));
    }

    @Test
    void listClaimsBySubjectsReturnsMapped() {
        KnowledgeClaim claim = new KnowledgeClaim();
        claim.setId("claim-1");
        claim.setProjectId("project-1");
        claim.setVersionId("v1");
        claim.setSubjectKey("feature:order");
        claim.setConfidence(BigDecimal.valueOf(0.9));
        when(repository.selectList(any())).thenReturn(List.of(claim));

        List<KnowledgeClaim> result = service.listClaimsBySubjects(
                "project-1", "v1", List.of("feature:order", "feature:customer"),
                "CONFIRMED", 0.5, 100);

        assertEquals(1, result.size());
        assertSame(claim, result.get(0));
        verify(repository).selectList(any());
    }
}
