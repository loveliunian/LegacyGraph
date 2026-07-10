package io.github.legacygraph.service.systemoverview;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.systemoverview.LayerMappingDTO;
import io.github.legacygraph.dto.systemoverview.SystemOverviewDTO;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link SystemOverviewService} 单元测试。
 * 验证 12 业务域映射、按域模糊匹配、Markdown 报告生成、图谱直查四层结构与贯穿链路。
 */
@ExtendWith(MockitoExtension.class)
class SystemOverviewServiceTest {

    @Mock
    private KnowledgeClaimService knowledgeClaimService;

    @Mock
    private Neo4jGraphDao graphDao;

    private SystemOverviewService service;

    @BeforeEach
    void setUp() {
        service = new SystemOverviewService(knowledgeClaimService, graphDao);
        lenient().when(knowledgeClaimService.listClaims(anyString(), anyString(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(knowledgeClaimService.countClaimsByStatus(anyString(), anyString()))
                .thenReturn(Map.of());
        // 图谱查询默认返回空，让现有测试回退到 Claim/内置映射
        lenient().when(graphDao.businessDomainContains(anyString(), anyString())).thenReturn(List.of());
        lenient().when(graphDao.apiImplementationRelations(anyString(), anyString())).thenReturn(List.of());
        lenient().when(graphDao.versionGraphStats(anyString(), anyString()))
                .thenReturn(Map.of("totalNodes", 0L, "totalEdges", 0L, "avgConfidence", 0.0));
        lenient().when(graphDao.nodeTypeDistribution(anyString(), anyString())).thenReturn(List.of());
        lenient().when(graphDao.edgeTypeDistribution(anyString(), anyString())).thenReturn(List.of());
        lenient().when(graphDao.queryNodes(anyString(), any(), anyString(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(graphDao.queryEdges(anyString(), any(), anyString(), any(), anyInt()))
                .thenReturn(List.of());
    }

    @Test
    void getOverview_returns12DomainsAnd12Paths() {
        SystemOverviewDTO dto = service.getOverview("self", "v1");

        assertEquals("self", dto.getProjectId());
        assertEquals(12, dto.getMappings().size());
        assertEquals(12, dto.getCorePaths().size());
        assertEquals(12, dto.getTotalDomains());
    }

    @Test
    void getDomain_fuzzyMatchByCapability() {
        List<LayerMappingDTO> rows = service.getDomain("self", "v1", "QA");

        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(m ->
                m.getBusinessDomain().contains("QA") || m.getCapability().contains("QA")));
    }

    @Test
    void getDomain_blankReturnsAll() {
        List<LayerMappingDTO> rows = service.getDomain("self", "v1", "");
        assertEquals(12, rows.size());
    }

    @Test
    void generateMarkdown_containsKeySections() {
        String md = service.generateMarkdown("self", "v1");

        assertTrue(md.contains("系统关系总览报告"));
        assertTrue(md.contains("业务域映射总表"));
        assertTrue(md.contains("核心贯穿链路"));
        assertTrue(md.contains("图谱关系统计"));
        assertTrue(md.contains("图谱关系明细"));
        assertTrue(md.contains("ProjectController"));
        assertTrue(md.contains("BusinessDomain CONTAINS Feature"));
    }

    @Test
    void generateMarkdown_includesDetailedGraphRelationsAndImpactViews() {
        // generateMarkdown 按 status 分区加载 Claim，需分别 stub CONFIRMED / PENDING_CONFIRM / INFERRED
        when(knowledgeClaimService.listClaims(eq("p2"), eq("v2"), any(), any(), eq("CONFIRMED"), any(), anyInt()))
                .thenReturn(List.of());
        when(knowledgeClaimService.listClaims(eq("p2"), eq("v2"), any(), any(), eq("INFERRED"), any(), anyInt()))
                .thenReturn(List.of());
        when(knowledgeClaimService.listClaims(eq("p2"), eq("v2"), any(), any(), eq("PENDING_CONFIRM"), any(), anyInt()))
                .thenReturn(List.of(
                        claim("BusinessDomain", "订单域", "CONTAINS", "Feature", "POST /orders", "DOC", "PENDING_CONFIRM"),
                        claim("BusinessDomain", "订单域", "CONTAINS", "Feature", "POST /orders/refund", "DOC", "PENDING_CONFIRM"),
                        claim("Feature", "POST /orders", "IMPLEMENTED_BY", "Controller", "OrderController", "DOC", "PENDING_CONFIRM"),
                        claim("Feature", "POST /orders/refund", "IMPLEMENTED_BY", "Controller", "RefundController", "DOC", "PENDING_CONFIRM"),
                        claim("Feature", "POST /orders", "USES", "Service", "OrderService", "DOC", "PENDING_CONFIRM"),
                        claim("Feature", "POST /orders/refund", "USES", "Service", "RefundService", "DOC", "PENDING_CONFIRM"),
                        claim("ApiEndpoint", "POST /orders", "HANDLED_BY", "Controller", "OrderController", "DOC", "PENDING_CONFIRM"),
                        claim("Service", "OrderService", "READS", "Table", "lg_order", "DOC", "PENDING_CONFIRM"),
                        claim("Service", "RefundService", "WRITES", "Table", "lg_refund_order", "DOC", "PENDING_CONFIRM")
                ));

        String md = service.generateMarkdown("p2", "v2");

        assertTrue(md.contains("## 3. 图谱关系统计"));
        assertTrue(md.contains("| CONTAINS | 2 |"));
        assertTrue(md.contains("## 4. 图谱关系明细"));
        assertTrue(md.contains("| BusinessDomain | 订单域 | CONTAINS | Feature | POST /orders | DOC | PENDING_CONFIRM | 0.6000 |"));
        assertTrue(md.contains("| Feature | POST /orders/refund | USES | Service | RefundService | DOC | PENDING_CONFIRM | 0.6000 |"));
        assertTrue(md.contains("## 5. 按业务域拆解"));
        assertFalse(md.contains("- API：`POST /orders`"), "待确认关系不得进入正文按域拆解");
        assertTrue(md.contains("## 6. 数据表影响面"));
        assertFalse(md.contains("| lg_refund_order | 订单域 | POST /orders/refund | RefundController | RefundService |"),
                "待确认关系不得进入正文数据表影响面");
        assertTrue(md.contains("## 7. Mermaid 关系图"));
        assertTrue(md.contains("\"订单域\" -->|CONTAINS| \"POST /orders\""));
        assertTrue(md.contains("## 11. QA 文档基础"));
    }

    // ==================== P0-4: 统一报告真值口径 ====================

    @Test
    void generateMarkdown_partitionsBodyAndAppendixByTruthPolicy() {
        // 正文：CONFIRMED Claim
        when(knowledgeClaimService.listClaims(eq("p5"), eq("v5"), any(), any(), eq("CONFIRMED"), any(), anyInt()))
                .thenReturn(List.of(
                        claim("Service", "OrderService", "READS", "Table", "lg_order", "CODE", "CONFIRMED")
                ));
        when(knowledgeClaimService.listClaims(eq("p5"), eq("v5"), any(), any(), eq("INFERRED"), any(), anyInt()))
                .thenReturn(List.of());
        // 附录：PENDING_CONFIRM Claim
        when(knowledgeClaimService.listClaims(eq("p5"), eq("v5"), any(), any(), eq("PENDING_CONFIRM"), any(), anyInt()))
                .thenReturn(List.of(
                        claim("Feature", "下单", "USES", "Service", "OrderService", "DOC", "PENDING_CONFIRM")
                ));

        String md = service.generateMarkdown("p5", "v5");

        // 正文（已确认结论）仅含 CONFIRMED
        assertTrue(md.contains("### 正文（已确认结论）"));
        assertTrue(md.contains("| Service | OrderService | READS | Table | lg_order | CODE | CONFIRMED |"));
        // 附录（待确认/推断）含 PENDING_CONFIRM 并明确标识
        assertTrue(md.contains("### 附录（待确认/推断）"));
        assertTrue(md.contains("> 以下关系尚未经确认，仅供参考"));
        assertTrue(md.contains("| Feature | 下单 | USES | Service | OrderService | DOC | PENDING_CONFIRM |"));
    }

    @Test
    void generateMarkdown_primaryMappingsExcludePendingClaims() {
        List<KnowledgeClaim> confirmed = List.of(
                claim("BusinessDomain", "确认域", "CONTAINS", "Feature", "确认能力", "CODE", "CONFIRMED"),
                claim("Feature", "确认能力", "IMPLEMENTED_BY", "Controller", "ConfirmedController", "CODE", "CONFIRMED"),
                claim("Feature", "确认能力", "USES", "Service", "ConfirmedService", "CODE", "CONFIRMED"),
                claim("Service", "ConfirmedService", "READS", "Table", "lg_confirmed", "SQL_PARSE", "CONFIRMED")
        );
        List<KnowledgeClaim> pending = List.of(
                claim("BusinessDomain", "待确认域", "CONTAINS", "Feature", "待确认能力", "DOC_AI", "PENDING_CONFIRM"),
                claim("Feature", "待确认能力", "IMPLEMENTED_BY", "Controller", "PendingController", "DOC_AI", "PENDING_CONFIRM")
        );
        when(knowledgeClaimService.listClaims(eq("truth-p1"), eq("v1"), any(), any(), eq("CONFIRMED"), any(), anyInt()))
                .thenReturn(confirmed);
        when(knowledgeClaimService.listClaims(eq("truth-p1"), eq("v1"), any(), any(), eq("PENDING_CONFIRM"), any(), anyInt()))
                .thenReturn(pending);
        when(knowledgeClaimService.listClaims(eq("truth-p1"), eq("v1"), any(), any(), eq("INFERRED"), any(), anyInt()))
                .thenReturn(List.of());

        String markdown = service.generateMarkdown("truth-p1", "v1");
        String primaryMappings = markdown.substring(0, markdown.indexOf("## 3. 图谱关系统计"));

        assertTrue(primaryMappings.contains("确认域"));
        assertFalse(primaryMappings.contains("待确认域"));
        assertTrue(markdown.contains("待确认域"), "待确认事实应仅在附录明细中保留");
    }

    @Test
    void generateMarkdown_showsCoverageHintWhenTruncated() {
        // 正文：仅 1 条 CONFIRMED，但总数 10 → 触发覆盖率提示
        when(knowledgeClaimService.listClaims(eq("p6"), eq("v6"), any(), any(), eq("CONFIRMED"), any(), anyInt()))
                .thenReturn(List.of(
                        claim("Service", "OrderService", "READS", "Table", "lg_order", "CODE", "CONFIRMED")
                ));
        when(knowledgeClaimService.listClaims(eq("p6"), eq("v6"), any(), any(), eq("INFERRED"), any(), anyInt()))
                .thenReturn(List.of());
        when(knowledgeClaimService.listClaims(eq("p6"), eq("v6"), any(), any(), eq("PENDING_CONFIRM"), any(), anyInt()))
                .thenReturn(List.of());
        when(knowledgeClaimService.countClaimsByStatus(eq("p6"), eq("v6")))
                .thenReturn(Map.of("CONFIRMED", 10L, "PENDING_CONFIRM", 0L));

        String md = service.generateMarkdown("p6", "v6");

        assertTrue(md.contains("⚠ 覆盖 1/10，尚有 9 条未展示"));
    }

    @Test
    void getOverview_usesPendingDocClaimsInsteadOfFallingBackToBuiltins() {
        when(knowledgeClaimService.listClaims(eq("p2"), eq("v2"), any(), any(), isNull(), any(), anyInt()))
                .thenReturn(List.of(
                        claim("BusinessDomain", "订单域", "CONTAINS", "Feature", "下单", "DOC", "PENDING_CONFIRM"),
                        claim("Feature", "下单", "IMPLEMENTED_BY", "Controller", "OrderController", "DOC", "PENDING_CONFIRM"),
                        claim("Feature", "下单", "USES", "Service", "OrderService", "DOC", "PENDING_CONFIRM"),
                        claim("ApiEndpoint", "POST /orders", "HANDLED_BY", "Controller", "OrderController", "DOC", "PENDING_CONFIRM"),
                        claim("Service", "OrderService", "READS", "Table", "lg_order", "DOC", "PENDING_CONFIRM"),
                        claim("Service", "OrderService", "WRITES", "Table", "lg_order_item", "DOC", "PENDING_CONFIRM")
                ));

        SystemOverviewDTO dto = service.getOverview("p2", "v2");

        assertEquals(1, dto.getMappings().size());
        LayerMappingDTO mapping = dto.getMappings().get(0);
        assertEquals("订单域", mapping.getBusinessDomain());
        assertEquals("下单", mapping.getCapability());
        assertEquals("OrderController", mapping.getController());
        assertEquals("POST /orders", mapping.getApiPath());
        assertEquals("OrderService", mapping.getCodeModule());
        assertEquals(List.of("lg_order", "lg_order_item"), mapping.getDataTables());
    }

    @Test
    void getPaths_filtersDynamicMappingsByFromAndTo() {
        when(knowledgeClaimService.listClaims(eq("p3"), eq("v3"), any(), any(), isNull(), any(), anyInt()))
                .thenReturn(List.of(
                        claim("BusinessDomain", "订单域", "CONTAINS", "Feature", "下单", "DOC", "PENDING_CONFIRM"),
                        claim("Feature", "下单", "IMPLEMENTED_BY", "Controller", "OrderController", "DOC", "PENDING_CONFIRM"),
                        claim("Feature", "下单", "USES", "Service", "OrderService", "DOC", "PENDING_CONFIRM"),
                        claim("Service", "OrderService", "READS", "Table", "lg_order", "DOC", "PENDING_CONFIRM")
                ));

        List<String> paths = service.getPaths("p3", "v3", "订单", "lg_order");

        assertEquals(1, paths.size());
        assertTrue(paths.get(0).contains("订单域"));
        assertTrue(paths.get(0).contains("lg_order"));
    }

    /**
     * 回归：同业务域下多个 Feature 必须各占一行，不能折叠成一行。
     * <p>旧实现按 subjectKey 分组后每域只 findFirst 取一条 Feature，
     * 导致 188 个 API 只出 14 行（每域 1 条）。
     */
    @Test
    void getOverview_emitsOneMappingPerFeatureNotPerDomain() {
        when(knowledgeClaimService.listClaims(eq("p4"), eq("v4"), any(), any(), isNull(), any(), anyInt()))
                .thenReturn(List.of(
                        // 订单域下两个 Feature（feature key 即 API 路径，对齐 ingestFromProjectGraph 的真实形状）
                        claim("BusinessDomain", "订单域", "CONTAINS", "Feature", "POST /orders", "DOC", "PENDING_CONFIRM"),
                        claim("BusinessDomain", "订单域", "CONTAINS", "Feature", "POST /orders/refund", "DOC", "PENDING_CONFIRM"),
                        claim("Feature", "POST /orders", "IMPLEMENTED_BY", "Controller", "OrderController", "DOC", "PENDING_CONFIRM"),
                        claim("Feature", "POST /orders/refund", "IMPLEMENTED_BY", "Controller", "OrderController", "DOC", "PENDING_CONFIRM"),
                        claim("ApiEndpoint", "POST /orders", "HANDLED_BY", "Controller", "OrderController", "DOC", "PENDING_CONFIRM"),
                        claim("ApiEndpoint", "POST /orders/refund", "HANDLED_BY", "Controller", "OrderController", "DOC", "PENDING_CONFIRM")
                ));

        SystemOverviewDTO dto = service.getOverview("p4", "v4");

        // 2 个 CONTAINS Claim → 2 行映射（而非按域折叠成 1 行）
        assertEquals(2, dto.getMappings().size());
        assertEquals(1, dto.getTotalDomains(), "仍为 1 个业务域");
        // apiPath 取 Feature 自身（feature key 命中 ApiEndpoint subjectKey），不再都用 Controller 首个 API
        List<String> apiPaths = dto.getMappings().stream()
                .map(LayerMappingDTO::getApiPath)
                .sorted()
                .toList();
        assertEquals(List.of("POST /orders", "POST /orders/refund"), apiPaths);
    }

    // ==================== SubTask 15.3: 图谱直查四层结构 ====================

    @Test
    void getOverview_usesGraphBasedMappingsWhenAvailable() {
        when(graphDao.businessDomainContains(eq("g1"), eq("v1")))
                .thenReturn(List.of(
                        Map.of("domainDisplayName", "订单域", "domainName", "order",
                                "features", List.of("POST /orders", "POST /orders/refund")),
                        Map.of("domainDisplayName", "用户域", "domainName", "user",
                                "features", List.of("GET /users"))
                ));
        when(graphDao.apiImplementationRelations(eq("g1"), eq("v1")))
                .thenReturn(List.of(
                        Map.of("nodeKey", "POST /orders", "displayName", "POST /orders",
                                "controllers", List.of("OrderController"),
                                "services", List.of("OrderService"),
                                "tables", List.of("lg_order")),
                        Map.of("nodeKey", "POST /orders/refund", "displayName", "POST /orders/refund",
                                "controllers", List.of("RefundController"),
                                "services", List.of("RefundService"),
                                "tables", List.of("lg_refund_order")),
                        Map.of("nodeKey", "GET /users", "displayName", "GET /users",
                                "controllers", List.of("UserController"),
                                "services", List.of("UserService"),
                                "tables", List.of("lg_user"))
                ));

        SystemOverviewDTO dto = service.getOverview("g1", "v1");

        assertEquals(3, dto.getMappings().size());
        // 验证四层结构从图谱查询
        LayerMappingDTO orderMapping = dto.getMappings().stream()
                .filter(m -> "POST /orders".equals(m.getApiPath()))
                .findFirst().orElseThrow();
        assertEquals("订单域", orderMapping.getBusinessDomain());
        assertEquals("OrderController", orderMapping.getController());
        assertEquals("OrderService", orderMapping.getCodeModule());
        assertEquals(List.of("lg_order"), orderMapping.getDataTables());
        assertEquals("GRAPH", orderMapping.getEdgeType());
    }

    @Test
    void generateMarkdown_containsGraphBasedSections() {
        when(graphDao.versionGraphStats(eq("g2"), eq("v2")))
                .thenReturn(Map.of("totalNodes", 150L, "totalEdges", 300L, "avgConfidence", 0.85));
        when(graphDao.nodeTypeDistribution(eq("g2"), eq("v2")))
                .thenReturn(List.of(
                        Map.of("nodeType", "Table", "cnt", 20L),
                        Map.of("nodeType", "Service", "cnt", 30L)
                ));
        when(graphDao.edgeTypeDistribution(eq("g2"), eq("v2")))
                .thenReturn(List.of(
                        Map.of("edgeType", "CONTAINS", "cnt", 80L),
                        Map.of("edgeType", "CALLS", "cnt", 50L)
                ));
        when(graphDao.apiImplementationRelations(eq("g2"), eq("v2")))
                .thenReturn(List.of(
                        Map.of("nodeKey", "POST /orders", "displayName", "POST /orders",
                                "controllers", List.of("OrderController"),
                                "services", List.of("OrderService"),
                                "tables", List.of("lg_order", "lg_order_item"))
                ));

        String md = service.generateMarkdown("g2", "v2");

        // 核心贯穿链路（图谱直查）
        assertTrue(md.contains("## 8. 核心贯穿链路（图谱直查）"));
        assertTrue(md.contains("lg_order"));
        assertTrue(md.contains("OrderService"));
        assertTrue(md.contains("OrderController"));
        assertTrue(md.contains("POST /orders"));

        // 图谱统计摘要
        assertTrue(md.contains("## 9. 图谱统计摘要"));
        assertTrue(md.contains("节点总数: 150"));
        assertTrue(md.contains("边总数: 300"));
        assertTrue(md.contains("| Table | 20 |"));
        assertTrue(md.contains("| CONTAINS | 80 |"));
    }

    // ==================== SubTask 15.4: 核心贯穿链路查询 ====================

    @Test
    void generateMarkdown_containsCoreThroughChainTop5() {
        when(graphDao.apiImplementationRelations(eq("g3"), eq("v3")))
                .thenReturn(List.of(
                        Map.of("nodeKey", "POST /orders", "displayName", "POST /orders",
                                "controllers", List.of("OrderController"),
                                "services", List.of("OrderService"),
                                "tables", List.of("lg_order", "lg_order_item")),
                        Map.of("nodeKey", "GET /users", "displayName", "GET /users",
                                "controllers", List.of("UserController"),
                                "services", List.of("UserService"),
                                "tables", List.of("lg_user"))
                ));

        String md = service.generateMarkdown("g3", "v3");

        assertTrue(md.contains("## 8. 核心贯穿链路（图谱直查）"));
        // 最长链路应排在前面（4 节点 > 3 节点）
        int ordersIdx = md.indexOf("lg_order");
        int usersIdx = md.indexOf("lg_user");
        assertTrue(ordersIdx > 0 && usersIdx > 0);
        assertTrue(ordersIdx < usersIdx, "更长的链路应排在前面");
    }

    // ==================== SubTask 15.5: 模块依赖 Mermaid 图 ====================

    @Test
    void generateMarkdown_containsModuleDependencyMermaid() {
        GraphNode pkgA = node("id-a", "com.example.service");
        GraphNode pkgB = node("id-b", "com.example.controller");
        GraphNode pkgC = node("id-c", "com.example.mapper");
        when(graphDao.queryNodes(eq("g4"), any(), eq("Package"), any(), any(), any(), anyInt()))
                .thenReturn(List.of(pkgA, pkgB, pkgC));
        GraphEdge edge1 = edge("id-b", "id-a");
        GraphEdge edge2 = edge("id-a", "id-c");
        when(graphDao.queryEdges(eq("g4"), any(), eq("DEPENDS_ON"), any(), anyInt()))
                .thenReturn(List.of(edge1, edge2));

        String md = service.generateMarkdown("g4", "v4");

        assertTrue(md.contains("## 10. 模块依赖关系图"));
        assertTrue(md.contains("```mermaid"));
        assertTrue(md.contains("graph TD"));
        assertTrue(md.contains("\"com.example.controller\" --> \"com.example.service\""));
        assertTrue(md.contains("\"com.example.service\" --> \"com.example.mapper\""));
        assertTrue(md.contains("共 3 个 Package"));
        assertTrue(md.contains("2 条 DEPENDS_ON 依赖"));
    }

    private static KnowledgeClaim claim(String subjectType, String subjectKey, String predicate,
                                        String objectType, String objectKey,
                                        String sourceType, String status) {
        KnowledgeClaim claim = new KnowledgeClaim();
        claim.setProjectId("p");
        claim.setVersionId("v");
        claim.setSubjectType(subjectType);
        claim.setSubjectKey(subjectKey);
        claim.setPredicate(predicate);
        claim.setObjectType(objectType);
        claim.setObjectKey(objectKey);
        claim.setSourceType(sourceType);
        claim.setStatus(status);
        claim.setConfidence(new BigDecimal("0.6000"));
        return claim;
    }

    private static GraphNode node(String id, String nodeKey) {
        GraphNode n = new GraphNode();
        n.setId(id);
        n.setNodeKey(nodeKey);
        n.setNodeName(nodeKey);
        return n;
    }

    private static GraphEdge edge(String fromId, String toId) {
        GraphEdge e = new GraphEdge();
        e.setFromNodeId(fromId);
        e.setToNodeId(toId);
        e.setEdgeType("DEPENDS_ON");
        return e;
    }
}
