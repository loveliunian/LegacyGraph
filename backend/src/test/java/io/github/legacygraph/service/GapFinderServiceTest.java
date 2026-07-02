package io.github.legacygraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.GapTask;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.repository.GapTaskRepository;
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

@ExtendWith(MockitoExtension.class)
class GapFinderServiceTest {

    @Mock
    private KnowledgeClaimRepository claimRepository;
    @Mock
    private GapTaskRepository gapTaskRepository;

    private GapFinderService service;

    @BeforeEach
    void setUp() {
        service = new GapFinderService(claimRepository, gapTaskRepository, new ObjectMapper());
    }

    @Test
    void scanGaps_createsDocOnlyFeatureGapWhenFeatureHasNoImplementation() {
        when(claimRepository.selectList(any())).thenReturn(List.of(
                claim("c1", "Feature", "feature:订单创建", "DESCRIBED_BY", "Evidence", "doc.md", "DOC_AI")));
        when(gapTaskRepository.selectOne(any())).thenReturn(null);

        GapFinderService.GapScanResult result = service.scanGaps("project-1", "v1");

        assertTrue(result.getCreated() >= 1);
        ArgumentCaptor<GapTask> gapCaptor = ArgumentCaptor.forClass(GapTask.class);
        verify(gapTaskRepository, atLeastOnce()).insert(gapCaptor.capture());
        assertTrue(gapCaptor.getAllValues().stream()
                .anyMatch(g -> "doc_only_feature".equals(g.getGapType())
                        && "feature:订单创建".equals(g.getGapKey())
                        && g.getDescription() != null));
    }

    @Test
    void scanGaps_doesNotCreateDocOnlyFeatureWhenFeatureHasImplementationClaim() {
        when(claimRepository.selectList(any())).thenReturn(List.of(
                claim("c1", "Feature", "feature:订单创建", "DESCRIBED_BY", "Evidence", "doc.md", "DOC_AI"),
                claim("c2", "Feature", "feature:订单创建", "IMPLEMENTS", "ApiEndpoint", "POST /orders", "CODE_AI")));
        when(gapTaskRepository.selectOne(any())).thenReturn(null);

        service.scanGaps("project-1", "v1");

        ArgumentCaptor<GapTask> gapCaptor = ArgumentCaptor.forClass(GapTask.class);
        verify(gapTaskRepository, atLeastOnce()).insert(gapCaptor.capture());
        assertFalse(gapCaptor.getAllValues().stream()
                .anyMatch(g -> "doc_only_feature".equals(g.getGapType())));
    }

    @Test
    void scanGaps_reopensResolvedGapWhenStillPresent() {
        when(claimRepository.selectList(any())).thenReturn(List.of(
                claim("c1", "Feature", "feature:订单创建", "DESCRIBED_BY", "Evidence", "doc.md", "DOC_AI")));
        GapTask existing = new GapTask();
        existing.setId("gap-1");
        existing.setStatus("RESOLVED");
        existing.setGapType("doc_only_feature");
        existing.setGapKey("feature:订单创建");
        existing.setPriorityScore(BigDecimal.valueOf(0.5));
        when(gapTaskRepository.selectOne(any())).thenReturn(existing);

        GapFinderService.GapScanResult result = service.scanGaps("project-1", "v1");

        assertTrue(result.getReopened() >= 1);
        assertEquals("REOPENED", existing.getStatus());
        verify(gapTaskRepository, atLeastOnce()).updateById(existing);
    }

    private KnowledgeClaim claim(String id, String subjectType, String subjectKey, String predicate,
                                 String objectType, String objectKey, String sourceType) {
        KnowledgeClaim claim = new KnowledgeClaim();
        claim.setId(id);
        claim.setProjectId("project-1");
        claim.setVersionId("v1");
        claim.setSubjectType(subjectType);
        claim.setSubjectKey(subjectKey);
        claim.setPredicate(predicate);
        claim.setObjectType(objectType);
        claim.setObjectKey(objectKey);
        claim.setSourceType(sourceType);
        claim.setStatus("PENDING_CONFIRM");
        claim.setConfidence(BigDecimal.valueOf(0.8));
        return claim;
    }
}
