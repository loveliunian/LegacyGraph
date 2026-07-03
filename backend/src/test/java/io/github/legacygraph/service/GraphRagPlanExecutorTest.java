package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.rag.GraphRagEvidenceCard;
import io.github.legacygraph.entity.KnowledgeClaim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import io.github.legacygraph.service.graph.GapFinderService;
import io.github.legacygraph.service.graph.GraphRagPlanExecutor;
import io.github.legacygraph.service.graph.KnowledgeClaimService;

@ExtendWith(MockitoExtension.class)
class GraphRagPlanExecutorTest {

    @Mock
    private KnowledgeClaimService claimService;

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @Mock
    private GapFinderService gapFinderService;

    @Test
    void executeClaimQueriesFiltersBySubjectKeys() {
        GraphRagPlanExecutor executor = new GraphRagPlanExecutor(claimService, neo4jGraphDao, gapFinderService);
        when(claimService.listClaims(eq("project-1"), eq("v1"), any(), any(), any(), any(), eq(200)))
                .thenReturn(List.of(claim("feature:order"), claim("feature:customer")));

        List<GraphRagEvidenceCard> cards = executor.executeClaimQueries(
                "project-1", "v1", List.of("feature:order"), 200);

        assertEquals(1, cards.size());
        assertEquals("feature:order", cards.get(0).getNodeKey());
    }

    private KnowledgeClaim claim(String subjectKey) {
        KnowledgeClaim claim = new KnowledgeClaim();
        claim.setId("claim-" + subjectKey);
        claim.setProjectId("project-1");
        claim.setVersionId("v1");
        claim.setSubjectType("Feature");
        claim.setSubjectKey(subjectKey);
        claim.setPredicate("IMPLEMENTED_BY");
        claim.setObjectType("Service");
        claim.setObjectKey("OrderService");
        claim.setSourceType("CODE");
        claim.setConfidence(BigDecimal.ONE);
        claim.setStatus("CONFIRMED");
        return claim;
    }
}
