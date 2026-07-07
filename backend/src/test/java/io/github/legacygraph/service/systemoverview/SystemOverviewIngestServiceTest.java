package io.github.legacygraph.service.systemoverview;

import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.dto.systemoverview.SystemOverviewIngestRequest;
import io.github.legacygraph.dto.systemoverview.SystemOverviewIngestRequest.FaqCard;
import io.github.legacygraph.dto.systemoverview.SystemOverviewIngestRequest.RelationRow;
import io.github.legacygraph.dto.systemoverview.SystemOverviewIngestResult;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.service.graph.GraphQueryService;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.service.qa.SemanticCache;
import io.github.legacygraph.service.qa.VectorRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link SystemOverviewIngestService} 单元测试。
 * 验证：内置底座导入、Claim 谓词映射、非 lg_ 表过滤、空行跳过。
 */
@ExtendWith(MockitoExtension.class)
class SystemOverviewIngestServiceTest {

    @Mock
    private VectorRetrievalService vectorRetrievalService;

    @Mock
    private KnowledgeClaimService knowledgeClaimService;

    @Mock
    private SemanticCache semanticCache;

    @Mock
    private GraphQueryService graphQueryService;

    private SystemOverviewIngestService service;

    @BeforeEach
    void setUp() {
        service = new SystemOverviewIngestService(vectorRetrievalService, knowledgeClaimService, semanticCache, graphQueryService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestBuiltins_writesVectorsClaimsAndFaqs() {
        when(knowledgeClaimService.upsertDrafts(anyList())).thenReturn(List.of(new KnowledgeClaim()));

        SystemOverviewIngestResult result = service.ingestBuiltins("self", null);

        assertEquals("self", result.getProjectId());
        assertEquals("default", result.getVersionId());
        assertEquals(12, result.getVectorCount());
        assertEquals(5, result.getFaqCount());

        // 向量：12 条，chunkType=SYSTEM_OVERVIEW
        ArgumentCaptor<List<VectorDocument>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorRetrievalService).batchUpsertVectors(eq("self"), eq("default"), docsCaptor.capture());
        List<VectorDocument> docs = docsCaptor.getValue();
        assertEquals(12, docs.size());
        assertEquals(SystemOverviewIngestService.CHUNK_TYPE, docs.get(0).getChunkType());
        assertNotNull(docs.get(0).getContent());

        // Claim：覆盖总览需要的核心谓词，默认按 DOC 来源和谨慎置信度入库。
        ArgumentCaptor<List<KnowledgeClaimDraft>> draftsCaptor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeClaimService).upsertDrafts(draftsCaptor.capture());
        List<KnowledgeClaimDraft> drafts = draftsCaptor.getValue();
        assertTrue(drafts.size() > 12, "每行应生成多条 Claim");
        assertTrue(drafts.stream().anyMatch(d -> "CONTAINS".equals(d.getPredicate())));
        assertTrue(drafts.stream().anyMatch(d -> "IMPLEMENTED_BY".equals(d.getPredicate())));
        assertTrue(drafts.stream().anyMatch(d -> "HANDLED_BY".equals(d.getPredicate())));
        assertTrue(drafts.stream().anyMatch(d -> "USES".equals(d.getPredicate())));
        assertTrue(drafts.stream().anyMatch(d -> "READS".equals(d.getPredicate())));
        assertTrue(drafts.stream().anyMatch(d -> "WRITES".equals(d.getPredicate())));
        assertTrue(drafts.stream().anyMatch(d -> "MAPS_TO".equals(d.getPredicate())));
        assertTrue(drafts.stream().allMatch(d -> "DOC".equals(d.getSourceType())));
        assertTrue(drafts.stream().allMatch(d -> d.getConfidence().doubleValue() < 0.85));

        // FAQ：5 条入语义缓存
        verify(semanticCache, times(5)).put(eq("self"), anyString(), anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingest_mapsPredicatesAndSkipsNonLgTables() {
        when(knowledgeClaimService.upsertDrafts(anyList())).thenReturn(List.of());

        SystemOverviewIngestRequest req = SystemOverviewIngestRequest.builder()
                .projectId("p1").versionId("v1")
                .relations(List.of(RelationRow.builder()
                        .businessDomain("测试域")
                        .capability("测试能力")
                        .controller("TestController")
                        .codeModule("TestService")
                        .dataTables("lg_test,Neo4j")
                        .build()))
                .build();

        service.ingest(req);

        ArgumentCaptor<List<KnowledgeClaimDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeClaimService).upsertDrafts(captor.capture());
        List<KnowledgeClaimDraft> drafts = captor.getValue();

        // CONTAINS + IMPLEMENTED_BY(Feature→Controller) + IMPLEMENTED_BY(Controller→Service) + USES + READS/WRITES/MAPS_TO(lg_test) = 7；Neo4j 被跳过
        assertEquals(7, drafts.size());
        assertTrue(drafts.stream().anyMatch(d -> "IMPLEMENTED_BY".equals(d.getPredicate())
                && "TestService".equals(d.getObjectKey())));
        assertTrue(drafts.stream().anyMatch(d -> "READS".equals(d.getPredicate())
                && "lg_test".equals(d.getObjectKey())));
        assertTrue(drafts.stream().anyMatch(d -> "WRITES".equals(d.getPredicate())
                && "lg_test".equals(d.getObjectKey())));
        assertTrue(drafts.stream().anyMatch(d -> "MAPS_TO".equals(d.getPredicate())
                && "lg_test".equals(d.getObjectKey())));
        assertFalse(drafts.stream().anyMatch(d -> "Neo4j".equals(d.getObjectKey())),
                "非 lg_ 表不应生成 READS Claim");
    }

    @Test
    void ingest_skipsBlankRows() {
        SystemOverviewIngestRequest req = SystemOverviewIngestRequest.builder()
                .projectId("p1").versionId("v1")
                .relations(List.of(RelationRow.builder().build())) // 全空行
                .build();

        SystemOverviewIngestResult result = service.ingest(req);

        assertEquals(1, result.getSkipped());
        assertEquals(0, result.getVectorCount());
        assertEquals(0, result.getClaimCount());
        verify(vectorRetrievalService, never()).batchUpsertVectors(any(), any(), any());
        verify(knowledgeClaimService, never()).upsertDrafts(anyList());
    }

    @Test
    void ingest_faqsWrittenToSemanticCache() {
        SystemOverviewIngestRequest req = SystemOverviewIngestRequest.builder()
                .projectId("p1").versionId("v1")
                .faqs(List.of(FaqCard.builder()
                        .question("Q1").answer("A1").evidence("{}").build()))
                .build();

        SystemOverviewIngestResult result = service.ingest(req);

        assertEquals(1, result.getFaqCount());
        verify(semanticCache).put("p1", "Q1", "A1", "{}");
    }

    /**
     * ingestFromProjectGraph：从图谱回溯的 API 实现关系应转成四层 Claim。
     * <p>回归：旧实现走 getApiCallChain（有向 BFS + 200 边上限）在 Method 处断链，
     * 抽不到 Controller/Service/Table，导致报告为空。现走 getApiImplementationRelations
     * 双向遍历，应能产出 CONTAINS / IMPLEMENTED_BY / HANDLED_BY 等 Claim。</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void ingestFromProjectGraph_projectsApiImplToFourLayerClaims() {
        when(graphQueryService.getApiImplementationRelations("p1", "v1")).thenReturn(List.of(
                java.util.Map.of(
                        "nodeKey", "GET /order/list",
                        "displayName", "订单列表",
                        "controllers", List.of("OrderController"),
                        "services", List.of("OrderService"),
                        "tables", List.of("lg_order", "Neo4j")),
                java.util.Map.of(
                        "nodeKey", "GET /health",
                        "displayName", "健康检查",
                        "controllers", List.of(),      // 无任何实现 → 应被跳过
                        "services", List.of(),
                        "tables", List.of())));
        when(knowledgeClaimService.upsertDrafts(anyList())).thenReturn(List.of(new KnowledgeClaim()));

        SystemOverviewIngestResult result = service.ingestFromProjectGraph("p1", "v1");

        assertEquals(1, result.getVectorCount(), "仅 1 个有实现的 API 应产出 1 条向量");
        ArgumentCaptor<List<KnowledgeClaimDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeClaimService).upsertDrafts(captor.capture());
        List<KnowledgeClaimDraft> drafts = captor.getValue();

        // 业务域由 Controller 名近似：OrderController → Order
        assertTrue(drafts.stream().anyMatch(d -> "BusinessDomain".equals(d.getSubjectType())
                && "CONTAINS".equals(d.getPredicate()) && "Feature".equals(d.getObjectType())));
        assertTrue(drafts.stream().anyMatch(d -> "Feature".equals(d.getSubjectType())
                && "IMPLEMENTED_BY".equals(d.getPredicate()) && "Controller".equals(d.getObjectType())
                && "OrderController".equals(d.getObjectKey())));
        assertTrue(drafts.stream().anyMatch(d -> "ApiEndpoint".equals(d.getSubjectType())
                && "HANDLED_BY".equals(d.getPredicate()) && "GET /order/list".equals(d.getSubjectKey())));
        assertTrue(drafts.stream().anyMatch(d -> "READS".equals(d.getPredicate())
                && "lg_order".equals(d.getObjectKey())));
        assertFalse(drafts.stream().anyMatch(d -> "Neo4j".equals(d.getObjectKey())),
                "非 lg_ 表不应生成 READS Claim");
    }

    @Test
    void ingestFromProjectGraph_emptyRelationsReturnsZero() {
        when(graphQueryService.getApiImplementationRelations("p1", "v1")).thenReturn(List.of());

        SystemOverviewIngestResult result = service.ingestFromProjectGraph("p1", "v1");

        assertEquals(0, result.getVectorCount());
        assertEquals(0, result.getClaimCount());
        verify(knowledgeClaimService, never()).upsertDrafts(anyList());
    }
}
