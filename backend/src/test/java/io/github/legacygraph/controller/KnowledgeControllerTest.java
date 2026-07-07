package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.gap.GapTaskView;
import io.github.legacygraph.entity.GapTask;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.service.graph.GapFinderService;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeControllerTest {

    @Mock
    private KnowledgeClaimService knowledgeClaimService;
    @Mock
    private GapFinderService gapFinderService;

    private KnowledgeController controller;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeController(knowledgeClaimService, gapFinderService, new ObjectMapper());
    }

    @Test
    void listClaimsPassesFiltersToService() {
        KnowledgeClaim claim = new KnowledgeClaim();
        claim.setId("claim-1");
        Page<KnowledgeClaim> mockPage = new Page<>();
        mockPage.setRecords(List.of(claim));
        mockPage.setTotal(1L);
        when(knowledgeClaimService.listClaimsPaged("project-1", "v1", "Feature", "IMPLEMENTS",
                "PENDING_CONFIRM", "DOC_AI", 1, 20)).thenReturn(mockPage);

        PageQuery query = new PageQuery();
        query.setPageNum(1);
        query.setPageSize(20);
        Result<PageResult<KnowledgeClaim>> result = controller.listClaims("project-1", "v1", "Feature",
                "IMPLEMENTS", "PENDING_CONFIRM", "DOC_AI", query);

        assertEquals(0, result.getCode());
        assertEquals(List.of(claim), result.getData().getList());
    }

    @Test
    void getClaimScopesLookupByProject() {
        KnowledgeClaim claim = new KnowledgeClaim();
        claim.setId("claim-1");
        when(knowledgeClaimService.getClaim("project-1", "claim-1")).thenReturn(claim);

        Result<KnowledgeClaim> result = controller.getClaim("project-1", "claim-1");

        assertEquals(0, result.getCode());
        assertSame(claim, result.getData());
    }

    @Test
    void listGapsReturnsParsedViewLists() {
        GapTask gap = new GapTask();
        gap.setId("gap-1");
        gap.setGapType("doc_only_feature");
        gap.setGapKey("feature:订单创建");
        gap.setTitle("只有文档");
        gap.setSeverity("HIGH");
        gap.setStatus("OPEN");
        gap.setSubjectType("Feature");
        gap.setSubjectKey("feature:订单创建");
        gap.setRelatedClaimIds("[\"claim-1\"]");
        gap.setRelatedNodeIds("[\"node-1\"]");
        gap.setEvidenceIds("[\"ev-1\"]");
        gap.setPriorityScore(BigDecimal.valueOf(0.8));
        
        Page<GapTask> mockPage = new Page<>();
        mockPage.setRecords(List.of(gap));
        mockPage.setTotal(1L);
        when(gapFinderService.listGapsPaged("project-1", "v1", "doc_only_feature", "OPEN",
                "HIGH", 1, 10)).thenReturn(mockPage);

        PageQuery query = new PageQuery();
        query.setPageNum(1);
        query.setPageSize(10);
        Result<PageResult<GapTaskView>> result = controller.listGaps("project-1", "v1",
                "doc_only_feature", "OPEN", "HIGH", query);

        assertEquals(0, result.getCode());
        assertEquals(List.of("claim-1"), result.getData().getList().get(0).getRelatedClaimIds());
        assertEquals(List.of("node-1"), result.getData().getList().get(0).getRelatedNodeIds());
        assertEquals(List.of("ev-1"), result.getData().getList().get(0).getEvidenceIds());
    }

    @Test
    void resolveGapScopesMutationByProject() {
        when(gapFinderService.resolveGap("project-1", "gap-1")).thenReturn(true);

        Result<Void> result = controller.resolveGap("project-1", "gap-1");

        assertEquals(0, result.getCode());
        verify(gapFinderService).resolveGap("project-1", "gap-1");
    }
}
