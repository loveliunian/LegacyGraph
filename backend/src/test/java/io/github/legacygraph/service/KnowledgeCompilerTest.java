package io.github.legacygraph.service;

import io.github.legacygraph.service.graph.KnowledgeCompiler;

import io.github.legacygraph.dto.claim.CompileOptions;
import io.github.legacygraph.dto.claim.CompiledGraphProjection;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.entity.KnowledgeClaim;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KnowledgeCompilerTest {

    @Test
    void compileClaimsEmitsNodeClaimsAndKeyBasedEdgeForDryRunProjection() {
        KnowledgeCompiler compiler = new KnowledgeCompiler(null);
        KnowledgeClaim claim = claim("Feature", "feature:order-create", "IMPLEMENTED_BY", "Service", "OrderService");

        CompiledGraphProjection projection = compiler.compileClaims(List.of(claim), CompileOptions.builder()
                .dryRun(true)
                .includePending(false)
                .minConfidence(new BigDecimal("0.80"))
                .build());

        assertEquals(2, projection.getNodeClaims().size());
        assertEquals("feature:order-create", projection.getNodeClaims().get(0).getNodeKey());
        assertEquals("OrderService", projection.getNodeClaims().get(1).getNodeKey());

        assertEquals(1, projection.getEdgeClaims().size());
        GraphEdgeClaim edge = projection.getEdgeClaims().get(0);
        assertNull(edge.getFromNodeId());
        assertNull(edge.getToNodeId());
        assertEquals("feature:order-create", edge.getFromNodeKey());
        assertEquals("OrderService", edge.getToNodeKey());
        assertEquals("claim-1", edge.getClaimId());
    }

    private KnowledgeClaim claim(String subjectType, String subjectKey,
                                 String predicate, String objectType, String objectKey) {
        KnowledgeClaim claim = new KnowledgeClaim();
        claim.setId("claim-1");
        claim.setProjectId("project-1");
        claim.setVersionId("v1");
        claim.setSubjectType(subjectType);
        claim.setSubjectKey(subjectKey);
        claim.setPredicate(predicate);
        claim.setObjectType(objectType);
        claim.setObjectKey(objectKey);
        claim.setSourceType("CODE");
        claim.setConfidence(new BigDecimal("0.90"));
        claim.setStatus("CONFIRMED");
        return claim;
    }
}
