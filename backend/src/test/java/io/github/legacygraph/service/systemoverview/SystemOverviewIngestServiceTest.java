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
        assertFalse(drafts.stream().anyMatch(d -> "READS".equals(d.getPredicate()) || "WRITES".equals(d.getPredicate())),
                "内置关系仅提供表影响面，不应臆造读写方向");
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
                        .tableAccessType("READS")
                        .build()))
                .build();

        service.ingest(req);

        ArgumentCaptor<List<KnowledgeClaimDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeClaimService).upsertDrafts(captor.capture());
        List<KnowledgeClaimDraft> drafts = captor.getValue();

        // CONTAINS + IMPLEMENTED_BY(Feature→Controller) + IMPLEMENTED_BY(Controller→Service) + USES + READS + MAPS_TO(lg_test) = 6；Neo4j 被跳过
        assertEquals(6, drafts.size());
        assertTrue(drafts.stream().anyMatch(d -> "IMPLEMENTED_BY".equals(d.getPredicate())
                && "TestService".equals(d.getObjectKey())));
        assertTrue(drafts.stream().anyMatch(d -> "READS".equals(d.getPredicate())
                && "lg_test".equals(d.getObjectKey())));
        assertFalse(drafts.stream().anyMatch(d -> "WRITES".equals(d.getPredicate())
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
        assertFalse(drafts.stream().anyMatch(d -> ("READS".equals(d.getPredicate()) || "WRITES".equals(d.getPredicate()))
                && "lg_order".equals(d.getObjectKey())),
                "API 聚合表集合未携带访问方向，不应生成读写 Claim");
    }

    /**
     * Table 捕获必须保留 SqlStatement→Table 的原始访问方向，不能把所有表同时写成 READS/WRITES。
     * <p>API 锚定回溯在 Service↔Mapper 无边的项目里够不到表，导致「哪些数据库表」类问题无数据。
     * 现以 Mapper 为锚补全，subjectType 必须是 Mapper（不是 Service）。
     * </p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void ingestFromProjectGraph_capturesTableAccessViaMapper() {
        when(graphQueryService.getApiImplementationRelations("p1", "v1")).thenReturn(List.of());
        when(graphQueryService.getTableAccessRelations("p1", "v1")).thenReturn(List.of(
                java.util.Map.of(
                        "mapperKey", "com.example.OrderMapper",
                        "mapperName", "OrderMapper",
                        "tableName", "nx_order",
                        "accessType", "READS",
                        "accessSourceType", "SQL_PARSE",
                        "accessConfidence", 0.95),
                java.util.Map.of(
                        "mapperKey", "com.example.OrderMapper",
                        "mapperName", "OrderMapper",
                        "tableName", "nx_order_item",
                        "accessType", "WRITES",
                        "accessSourceType", "SQL_PARSE",
                        "accessConfidence", 0.95)));
        when(knowledgeClaimService.upsertDrafts(anyList())).thenReturn(List.of(new KnowledgeClaim()));

        SystemOverviewIngestResult result = service.ingestFromProjectGraph("p1", "v1");

        // 两条精确访问关系各自向量化，读写方向不再被聚合抹平。
        assertEquals(2, result.getVectorCount());
        ArgumentCaptor<List<KnowledgeClaimDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeClaimService).upsertDrafts(captor.capture());
        List<KnowledgeClaimDraft> drafts = captor.getValue();

        // subjectType=Mapper（不是 Service），且只保留原始方向。
        assertTrue(drafts.stream().anyMatch(d -> "Mapper".equals(d.getSubjectType())
                && "READS".equals(d.getPredicate()) && "Table".equals(d.getObjectType())
                && "OrderMapper".equals(d.getSubjectKey()) && "nx_order".equals(d.getObjectKey())),
                "应以 Mapper 为 subject 生成 READS Table Claim");
        assertTrue(drafts.stream().anyMatch(d -> "WRITES".equals(d.getPredicate())
                && "nx_order_item".equals(d.getObjectKey())));
        assertFalse(drafts.stream().anyMatch(d -> "WRITES".equals(d.getPredicate())
                && "nx_order".equals(d.getObjectKey())));
        assertFalse(drafts.stream().anyMatch(d -> "READS".equals(d.getPredicate())
                && "nx_order_item".equals(d.getObjectKey())));
    }

    @Test
    void ingestFromProjectGraph_emptyRelationsReturnsZero() {
        when(graphQueryService.getApiImplementationRelations("p1", "v1")).thenReturn(List.of());

        SystemOverviewIngestResult result = service.ingestFromProjectGraph("p1", "v1");

        assertEquals(0, result.getVectorCount());
        assertEquals(0, result.getClaimCount());
        verify(knowledgeClaimService, never()).upsertDrafts(anyList());
    }

    // ──────────── P0-3：推断关系降级测试 ────────────

    /**
     * P0-3：推断的 WRITES 边应降级为 AI_INFERENCE / 0.6，不被 computeStatus 判为 CONFIRMED。
     * <p>
     * CODE 来源（非 CODE_AST/SQL_PARSE 等）的表访问未经 AST/SQL 直接证明，
     * sourceType 应为 AI_INFERENCE、confidence 应为 0.6。
     * KnowledgeClaimService.computeStatus 对 AI_INFERENCE 始终返回 PENDING_CONFIRM，
     * 因此这些 Claim 不会被冒充为 CONFIRMED。
     * </p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void toClaims_inferredWritesDowngradedToAiInference() {
        when(knowledgeClaimService.upsertDrafts(anyList())).thenReturn(List.of());

        // CODE 来源（非 CODE_AST）→ 表访问推断
        SystemOverviewIngestRequest req = SystemOverviewIngestRequest.builder()
                .projectId("p1").versionId("v1")
                .relations(List.of(RelationRow.builder()
                        .businessDomain("Order")
                        .capability("订单列表")
                        .controller("OrderController")
                        .codeModule("OrderService")
                        .codeModuleType("Service")
                        .dataTables("lg_order")
                        .tableAccessType("WRITES")
                        .sourceType("CODE")
                        .confidence(0.85)
                        .build()))
                .build();

        service.ingest(req);

        ArgumentCaptor<List<KnowledgeClaimDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeClaimService).upsertDrafts(captor.capture());
        List<KnowledgeClaimDraft> drafts = captor.getValue();

        KnowledgeClaimDraft writesDraft = drafts.stream()
                .filter(d -> "WRITES".equals(d.getPredicate()) && "lg_order".equals(d.getObjectKey()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 WRITES lg_order 的 draft"));

        assertEquals("AI_INFERENCE", writesDraft.getSourceType(),
                "推断的 WRITES 边 sourceType 应为 AI_INFERENCE");
        assertEquals(0.6, writesDraft.getConfidence().doubleValue(), 0.0001,
                "推断的 WRITES 边 confidence 应为 0.6");
        // AI_INFERENCE 在 computeStatus 中始终返回 PENDING_CONFIRM，不会被误判为 CONFIRMED
        assertTrue(writesDraft.getConfidence().doubleValue() < 0.85,
                "推断 Claim confidence < 0.85，computeStatus 不会判为 CONFIRMED");
    }

    /**
     * P0-3：推断的 READS 边同样降级为 AI_INFERENCE / 0.6。
     */
    @Test
    @SuppressWarnings("unchecked")
    void toClaims_inferredReadsDowngradedToAiInference() {
        when(knowledgeClaimService.upsertDrafts(anyList())).thenReturn(List.of());

        SystemOverviewIngestRequest req = SystemOverviewIngestRequest.builder()
                .projectId("p1").versionId("v1")
                .relations(List.of(RelationRow.builder()
                        .businessDomain("Order")
                        .capability("订单列表")
                        .controller("OrderController")
                        .codeModule("OrderService")
                        .codeModuleType("Service")
                        .dataTables("lg_order")
                        .tableAccessType("READS")
                        .sourceType("CODE")
                        .confidence(0.85)
                        .build()))
                .build();

        service.ingest(req);

        ArgumentCaptor<List<KnowledgeClaimDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeClaimService).upsertDrafts(captor.capture());
        List<KnowledgeClaimDraft> drafts = captor.getValue();

        KnowledgeClaimDraft readsDraft = drafts.stream()
                .filter(d -> "READS".equals(d.getPredicate()) && "lg_order".equals(d.getObjectKey()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 READS lg_order 的 draft"));

        assertEquals("AI_INFERENCE", readsDraft.getSourceType(),
                "推断的 READS 边 sourceType 应为 AI_INFERENCE");
        assertEquals(0.6, readsDraft.getConfidence().doubleValue(), 0.0001,
                "推断的 READS 边 confidence 应为 0.6");
    }

    /**
     * P0-3：直接来源（CODE_AST）的表访问不应降级，保持原 sourceType 和 confidence。
     */
    @Test
    @SuppressWarnings("unchecked")
    void toClaims_directSourceTableAccessNotDowngraded() {
        when(knowledgeClaimService.upsertDrafts(anyList())).thenReturn(List.of());

        SystemOverviewIngestRequest req = SystemOverviewIngestRequest.builder()
                .projectId("p1").versionId("v1")
                .relations(List.of(RelationRow.builder()
                        .businessDomain("Order")
                        .capability("订单列表")
                        .controller("OrderController")
                        .codeModule("OrderMapper")
                        .codeModuleType("Mapper")
                        .dataTables("lg_order")
                        .tableAccessType("READS")
                        .sourceType("CODE_AST")
                        .confidence(0.9)
                        .build()))
                .build();

        service.ingest(req);

        ArgumentCaptor<List<KnowledgeClaimDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeClaimService).upsertDrafts(captor.capture());
        List<KnowledgeClaimDraft> drafts = captor.getValue();

        KnowledgeClaimDraft readsDraft = drafts.stream()
                .filter(d -> "READS".equals(d.getPredicate()) && "lg_order".equals(d.getObjectKey()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 READS lg_order 的 draft"));

        assertEquals("CODE_AST", readsDraft.getSourceType(),
                "CODE_AST 来源不应降级");
        assertEquals(0.9, readsDraft.getConfidence().doubleValue(), 0.0001,
                "CODE_AST 来源保持原 confidence");
    }

    /**
     * P0-3：推断的业务域（deriveDomain 产出）应降级为 AI_INFERENCE / 0.6。
     * <p>
     * Controller 名为 "OrderController" → deriveDomain 产出 "Order"，
     * 与 row.businessDomain 一致 → 判为推断 → 降级。
     * </p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void toClaims_inferredDomainDowngradedToAiInference() {
        when(knowledgeClaimService.upsertDrafts(anyList())).thenReturn(List.of());

        // businessDomain="Order" 与 deriveDomain("OrderController", null)="Order" 一致 → 推断
        SystemOverviewIngestRequest req = SystemOverviewIngestRequest.builder()
                .projectId("p1").versionId("v1")
                .relations(List.of(RelationRow.builder()
                        .businessDomain("Order")
                        .capability("订单能力")
                        .controller("OrderController")
                        .codeModule("OrderService")
                        .dataTables("lg_order")
                        .sourceType("CODE")
                        .confidence(0.85)
                        .build()))
                .build();

        service.ingest(req);

        ArgumentCaptor<List<KnowledgeClaimDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeClaimService).upsertDrafts(captor.capture());
        List<KnowledgeClaimDraft> drafts = captor.getValue();

        KnowledgeClaimDraft containsDraft = drafts.stream()
                .filter(d -> "CONTAINS".equals(d.getPredicate())
                        && "BusinessDomain".equals(d.getSubjectType())
                        && "Order".equals(d.getSubjectKey()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 Domain CONTAINS Feature 的 draft"));

        assertEquals("AI_INFERENCE", containsDraft.getSourceType(),
                "推断的业务域 CONTAINS sourceType 应为 AI_INFERENCE");
        assertTrue(containsDraft.getConfidence().doubleValue() < 0.85,
                "推断的业务域 confidence < 0.85");
        assertEquals(0.6, containsDraft.getConfidence().doubleValue(), 0.0001,
                "推断的业务域 confidence 应为 0.6");
    }

    /**
     * P0-3：非推断的业务域（人工梳理，与 deriveDomain 输出不一致）不应降级。
     */
    @Test
    @SuppressWarnings("unchecked")
    void toClaims_nonInferredDomainNotDowngraded() {
        when(knowledgeClaimService.upsertDrafts(anyList())).thenReturn(List.of());

        // businessDomain="订单管理" ≠ deriveDomain("OrderController",null)="Order" → 非推断
        SystemOverviewIngestRequest req = SystemOverviewIngestRequest.builder()
                .projectId("p1").versionId("v1")
                .relations(List.of(RelationRow.builder()
                        .businessDomain("订单管理")
                        .capability("订单能力")
                        .controller("OrderController")
                        .codeModule("OrderService")
                        .dataTables("lg_order")
                        .sourceType("DOC")
                        .confidence(0.70)
                        .build()))
                .build();

        service.ingest(req);

        ArgumentCaptor<List<KnowledgeClaimDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeClaimService).upsertDrafts(captor.capture());
        List<KnowledgeClaimDraft> drafts = captor.getValue();

        KnowledgeClaimDraft containsDraft = drafts.stream()
                .filter(d -> "CONTAINS".equals(d.getPredicate())
                        && "BusinessDomain".equals(d.getSubjectType())
                        && "订单管理".equals(d.getSubjectKey()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 Domain CONTAINS Feature 的 draft"));

        assertEquals("DOC", containsDraft.getSourceType(),
                "非推断的业务域应保持原 sourceType");
        assertEquals(0.70, containsDraft.getConfidence().doubleValue(), 0.0001,
                "非推断的业务域应保持原 confidence");
    }

    /**
     * P0-3：推断 Claim 的 lineage 字段应含推导规则 JSON。
     * <p>
     * WRITES 边 lineage 含 TABLE_NAME_HEURISTIC 规则；
     * Domain CONTAINS 边 lineage 含 CONTROLLER_NAME_HEURISTIC 规则。
     * </p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void toClaims_inferredClaimLineageContainsRule() {
        when(knowledgeClaimService.upsertDrafts(anyList())).thenReturn(List.of());

        SystemOverviewIngestRequest req = SystemOverviewIngestRequest.builder()
                .projectId("p1").versionId("v1")
                .relations(List.of(RelationRow.builder()
                        .businessDomain("Order")
                        .capability("订单能力")
                        .controller("OrderController")
                        .codeModule("OrderService")
                        .codeModuleType("Service")
                        .dataTables("lg_order")
                        .tableAccessType("WRITES")
                        .sourceType("CODE")
                        .confidence(0.85)
                        .build()))
                .build();

        service.ingest(req);

        ArgumentCaptor<List<KnowledgeClaimDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeClaimService).upsertDrafts(captor.capture());
        List<KnowledgeClaimDraft> drafts = captor.getValue();

        // WRITES lineage 含 TABLE_NAME_HEURISTIC
        KnowledgeClaimDraft writesDraft = drafts.stream()
                .filter(d -> "WRITES".equals(d.getPredicate()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 WRITES draft"));
        assertNotNull(writesDraft.getLineage(), "推断的 WRITES 边应有 lineage");
        assertTrue(writesDraft.getLineage().contains("TABLE_NAME_HEURISTIC"),
                "WRITES lineage 应含 TABLE_NAME_HEURISTIC 规则: " + writesDraft.getLineage());
        assertTrue(writesDraft.getLineage().contains("originalConfidence"),
                "lineage 应含 originalConfidence 字段: " + writesDraft.getLineage());

        assertFalse(drafts.stream().anyMatch(d -> "READS".equals(d.getPredicate())),
                "指定 WRITES 后不得额外生成 READS");

        // Domain CONTAINS lineage 含 CONTROLLER_NAME_HEURISTIC
        KnowledgeClaimDraft containsDraft = drafts.stream()
                .filter(d -> "CONTAINS".equals(d.getPredicate())
                        && "BusinessDomain".equals(d.getSubjectType()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 CONTAINS draft"));
        assertNotNull(containsDraft.getLineage(), "推断的业务域边应有 lineage");
        assertTrue(containsDraft.getLineage().contains("CONTROLLER_NAME_HEURISTIC"),
                "Domain lineage 应含 CONTROLLER_NAME_HEURISTIC 规则: " + containsDraft.getLineage());

        // 非推断的 Claim（如 IMPLEMENTED_BY）不应有 lineage
        KnowledgeClaimDraft implDraft = drafts.stream()
                .filter(d -> "IMPLEMENTED_BY".equals(d.getPredicate()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 IMPLEMENTED_BY draft"));
        assertNull(implDraft.getLineage(),
                "非推断的 Claim 不应有 lineage");
    }
}
