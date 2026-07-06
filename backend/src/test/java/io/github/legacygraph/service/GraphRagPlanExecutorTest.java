package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.rag.GraphRagEvidenceCard;
import io.github.legacygraph.dto.rag.GraphRagPlan;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.KnowledgeClaim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
        when(claimService.listClaimsBySubjects(eq("project-1"), eq("v1"), eq(List.of("feature:order")), any(), any(), eq(200)))
                .thenReturn(List.of(claim("feature:order")));

        GraphRagPlan.ClaimQuery cq = new GraphRagPlan.ClaimQuery();
        cq.setSubjectKey("feature:order");
        List<GraphRagEvidenceCard> cards = executor.executeClaimQueries(
                "project-1", "v1", List.of(cq), 200);

        assertEquals(1, cards.size());
        assertEquals("feature:order", cards.get(0).getNodeKey());
    }

    @Test
    void executePathQueriesReturnsRealPaths() {
        GraphRagPlanExecutor executor = new GraphRagPlanExecutor(claimService, neo4jGraphDao, gapFinderService);

        GraphNode fromNode = new GraphNode();
        fromNode.setNodeKey("OrderService");
        fromNode.setNodeName("OrderService");
        fromNode.setStatus("CONFIRMED");
        fromNode.setSourcePath("src/OrderService.java");
        fromNode.setStartLine(10);
        fromNode.setEndLine(20);
        GraphNode toNode = new GraphNode();
        toNode.setNodeKey("OrderRepository");
        toNode.setNodeName("OrderRepository");

        GraphEdge edge = new GraphEdge();
        edge.setEdgeType("CALLS");

        Neo4jGraphDao.GraphPath path = new Neo4jGraphDao.GraphPath(
                List.of(fromNode, toNode), List.of(edge),
                List.of("CALLS"), "src/OrderService.java", 10, 20,
                BigDecimal.valueOf(0.9), "OrderService", "OrderRepository");

        when(neo4jGraphDao.findPaths(eq("project-1"), eq("v1"), eq("OrderService"), eq("OrderRepository"),
                anyList(), anyInt(), anyInt()))
                .thenReturn(List.of(path));

        GraphRagPlan.PathQuery pq = GraphRagPlan.PathQuery.builder()
                .startNodeFilter("OrderService")
                .endNodeFilter("OrderRepository")
                .relationshipPattern("[:CALLS]->")
                .pathDepth("1..3")
                .build();
        List<GraphRagEvidenceCard> cards = executor.executePathQueries("project-1", "v1", List.of(pq));

        assertEquals(1, cards.size());
        GraphRagEvidenceCard card = cards.get(0);
        assertEquals("NEO4J", card.getSourceType());
        assertEquals("OrderService->OrderRepository", card.getNodeKey());
        assertEquals(List.of("CALLS"), card.getRelationTypes());
        assertEquals(List.of("OrderService", "OrderRepository"), card.getPathNodeKeys());
        assertEquals("src/OrderService.java", card.getSourcePath());
        assertEquals(Integer.valueOf(10), card.getStartLine());
        assertEquals(Integer.valueOf(20), card.getEndLine());
        assertEquals(0, BigDecimal.valueOf(0.9).compareTo(card.getConfidence()));
        assertEquals("CONFIRMED", card.getStatus());
        assertTrue(card.getExcerpt().contains("CALLS"));
        assertTrue(card.getExcerpt().contains("OrderService"));

        // 关系类型从 relationshipPattern 正确提取并下推到 findPaths
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> relTypesCap = ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).findPaths(eq("project-1"), eq("v1"), eq("OrderService"), eq("OrderRepository"),
                relTypesCap.capture(), eq(3), eq(20));
        assertEquals(List.of("CALLS"), relTypesCap.getValue());
    }

    @Test
    void executeClaimQueriesAppliesStatusAndConfidence() {
        GraphRagPlanExecutor executor = new GraphRagPlanExecutor(claimService, neo4jGraphDao, gapFinderService);
        when(claimService.listClaimsBySubjects(anyString(), anyString(), anyList(), any(), any(), anyInt()))
                .thenReturn(List.of());

        List<GraphRagPlan.ClaimQuery> queries = List.of(
                GraphRagPlan.ClaimQuery.builder()
                        .subjectKey("feature:order").status("CONFIRMED").minConfidence(0.8).build(),
                GraphRagPlan.ClaimQuery.builder()
                        .subjectKey("feature:customer").status("PENDING_CONFIRM").minConfidence(0.4).build()
        );
        executor.executeClaimQueries("project-1", "v1", queries, 100);

        ArgumentCaptor<String> statusCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Double> confCap = ArgumentCaptor.forClass(Double.class);
        verify(claimService).listClaimsBySubjects(eq("project-1"), eq("v1"), anyList(),
                statusCap.capture(), confCap.capture(), eq(100));
        // status 取首个非空
        assertEquals("CONFIRMED", statusCap.getValue());
        // minConfidence 取 min（最宽召回）
        assertEquals(0.4, confCap.getValue(), 0.0001);
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
