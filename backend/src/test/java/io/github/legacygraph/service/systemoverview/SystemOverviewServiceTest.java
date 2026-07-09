package io.github.legacygraph.service.systemoverview;

import io.github.legacygraph.dto.systemoverview.LayerMappingDTO;
import io.github.legacygraph.dto.systemoverview.SystemOverviewDTO;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link SystemOverviewService} 单元测试。
 * 验证 12 业务域映射、按域模糊匹配、Markdown 报告生成。
 */
@ExtendWith(MockitoExtension.class)
class SystemOverviewServiceTest {

    @Mock
    private KnowledgeClaimService knowledgeClaimService;

    private SystemOverviewService service;

    @BeforeEach
    void setUp() {
        service = new SystemOverviewService(knowledgeClaimService);
        lenient().when(knowledgeClaimService.listClaims(anyString(), anyString(), any(), any(), any(), any(), anyInt()))
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
        when(knowledgeClaimService.listClaims(eq("p2"), eq("v2"), any(), any(), isNull(), any(), anyInt()))
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
        assertTrue(md.contains("- API：`POST /orders`"));
        assertTrue(md.contains("- Controller：`RefundController`"));
        assertTrue(md.contains("## 6. 数据表影响面"));
        assertTrue(md.contains("| lg_refund_order | 订单域 | POST /orders/refund | RefundController | RefundService |"));
        assertTrue(md.contains("## 7. Mermaid 关系图"));
        assertTrue(md.contains("\"订单域\" -->|CONTAINS| \"POST /orders\""));
        assertTrue(md.contains("## 8. QA 文档基础"));
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
}
