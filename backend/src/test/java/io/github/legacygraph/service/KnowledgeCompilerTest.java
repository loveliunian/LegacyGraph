package io.github.legacygraph.service;

import io.github.legacygraph.builder.EvidenceGraphWriter;
import io.github.legacygraph.config.GraphWriteConfig;
import io.github.legacygraph.dto.claim.CompileOptions;
import io.github.legacygraph.dto.claim.CompiledGraphProjection;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.service.graph.KnowledgeCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * {@link KnowledgeCompiler} 单元测试。
 * 覆盖 dry-run 投影（不写图）与 non-dryRun 写图（04 阶段3）。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeCompilerTest {

    @Mock
    private KnowledgeClaimService claimService;

    @Mock
    private EvidenceGraphWriter evidenceGraphWriter;

    @Test
    void compileClaimsEmitsNodeClaimsAndKeyBasedEdgeForDryRunProjection() {
        KnowledgeCompiler compiler = new KnowledgeCompiler(claimService, evidenceGraphWriter, new GraphWriteConfig());
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

        // dryRun=true 不写图
        verify(evidenceGraphWriter, never()).upsertNode(any());
        verify(evidenceGraphWriter, never()).upsertEdge(any());
    }

    @Test
    void compileWritesProjectionWhenNotDryRun() {
        when(claimService.listClaims(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(claim("Feature", "feature:order-create", "IMPLEMENTED_BY", "Service", "OrderService")));

        KnowledgeCompiler compiler = new KnowledgeCompiler(claimService, evidenceGraphWriter, new GraphWriteConfig());

        compiler.compile("project-1", "v1", CompileOptions.builder()
                .dryRun(false)
                .includePending(false)
                .minConfidence(new BigDecimal("0.80"))
                .build());

        // non-dryRun 触发实际写图（04 阶段3：Claim 编译切主链路）
        verify(evidenceGraphWriter, atLeastOnce()).upsertNode(any());
        verify(evidenceGraphWriter, atLeastOnce()).upsertEdge(any());
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
