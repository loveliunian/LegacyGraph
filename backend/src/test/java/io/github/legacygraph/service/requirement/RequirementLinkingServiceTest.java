package io.github.legacygraph.service.requirement;

import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.dto.RequirementItemDTO;
import io.github.legacygraph.dto.requirement.LinkedTarget;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.TerminologyMapping;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.TerminologyMappingRepository;
import io.github.legacygraph.service.qa.VectorRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link RequirementLinkingService} 单元测试（mock Neo4jGraphDao / VectorRetrievalService）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RequirementLinkingServiceTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private VectorRetrievalService vectorRetrievalService;
    @Mock
    private TerminologyMappingRepository terminologyMappingRepository;

    private RequirementLinkingService service;

    private static final String PROJECT_ID = "proj-001";
    private static final String VERSION_ID = "ver-001";
    private static final String REQUIREMENT_ID = "req-001";

    @BeforeEach
    void setUp() {
        service = new RequirementLinkingService(neo4jGraphDao, vectorRetrievalService,
                terminologyMappingRepository);
        // 默认：术语映射为空
        when(terminologyMappingRepository.selectList(any())).thenReturn(List.of());
        // 默认：queryNodes 返回空
        when(neo4jGraphDao.queryNodes(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
    }

    private GraphNode tableNode(String id, String key, String name) {
        GraphNode n = new GraphNode();
        n.setId(id);
        n.setNodeKey(key);
        n.setNodeName(name);
        n.setNodeType(NodeType.Table.name());
        n.setProjectId(PROJECT_ID);
        n.setVersionId(VERSION_ID);
        return n;
    }

    private GraphNode itemNode(String id, String key) {
        GraphNode n = new GraphNode();
        n.setId(id);
        n.setNodeKey(key);
        n.setNodeName("R1");
        n.setNodeType(NodeType.RequirementItem.name());
        n.setProjectId(PROJECT_ID);
        n.setVersionId(VERSION_ID);
        return n;
    }

    // ==================== extractReferences ====================

    @Test
    void extractReferences_schemaTableColumn_extractsFullAndPrefix() {
        Set<String> refs = service.extractReferences("修改 public.t_order.amount 字段类型");
        assertTrue(refs.contains("public.t_order.amount"));
        assertTrue(refs.contains("public.t_order"));
    }

    @Test
    void extractReferences_fqn_extracts() {
        Set<String> refs = service.extractReferences("调用 com.example.UserService 完成导出");
        assertTrue(refs.contains("com.example.UserService"));
    }

    @Test
    void extractReferences_urlPath_extracts() {
        Set<String> refs = service.extractReferences("访问 /api/v1/orders 接口");
        assertTrue(refs.contains("/api/v1/orders"));
    }

    @Test
    void extractReferences_backtick_extracts() {
        Set<String> refs = service.extractReferences("导出 `t_order` 表数据");
        assertTrue(refs.contains("t_order"));
    }

    @Test
    void extractReferences_blankText_returnsEmpty() {
        assertTrue(service.extractReferences("").isEmpty());
        assertTrue(service.extractReferences(null).isEmpty());
    }

    // ==================== matchExplicitReferences ====================

    @Test
    void matchExplicitReferences_nodeKeyHit_returnsConfirmed() {
        // nodeKey = "public.t_order" 命中
        GraphNode node = tableNode("t1", "public.t_order", "t_order");
        when(neo4jGraphDao.queryNodes(eq(PROJECT_ID), eq(VERSION_ID), isNull(),
                eq("public.t_order"), isNull(), isNull(), isNull(), eq(5)))
                .thenReturn(List.of(node));

        List<LinkedTarget> targets = service.matchExplicitReferences(
                PROJECT_ID, VERSION_ID, "R1", "修改 public.t_order 数据");

        assertEquals(1, targets.size());
        LinkedTarget t = targets.get(0);
        assertEquals("t1", t.getNodeId());
        assertEquals("public.t_order", t.getNodeKey());
        assertEquals("t_order", t.getNodeName());
        assertEquals(NodeType.Table.name(), t.getNodeType());
        assertEquals(RequirementLinkingService.MatchType.EXACT_REFERENCE.name(), t.getMatchType());
        assertEquals(0, BigDecimal.ONE.compareTo(t.getConfidence()));
        assertEquals(NodeStatus.CONFIRMED.name(), t.getStatus());
        assertEquals("R1", t.getItemCode());
    }

    @Test
    void matchExplicitReferences_noRefs_returnsEmpty() {
        List<LinkedTarget> targets = service.matchExplicitReferences(
                PROJECT_ID, VERSION_ID, "R1", "普通中文文本无引用");
        assertTrue(targets.isEmpty());
    }

    // ==================== matchTerminology ====================

    @Test
    void matchTerminology_hit_returnsConfirmed() {
        // 术语映射：结算单 → Table:lg_settlement
        Map<String, List<String>> termMap = Map.of("结算单", List.of("Table:lg_settlement"));
        GraphNode node = tableNode("s1", "public.lg_settlement", "lg_settlement");
        // findNodeByTerm 先按 nodeKey("lg_settlement") 查 → 命中
        when(neo4jGraphDao.queryNodes(eq(PROJECT_ID), eq(VERSION_ID), eq("Table"),
                eq("lg_settlement"), isNull(), isNull(), isNull(), eq(5)))
                .thenReturn(List.of(node));

        List<LinkedTarget> targets = service.matchTerminology(
                PROJECT_ID, VERSION_ID, "R1", "修改结算单状态", termMap);

        assertEquals(1, targets.size());
        LinkedTarget t = targets.get(0);
        assertEquals("s1", t.getNodeId());
        assertEquals(RequirementLinkingService.MatchType.TERMINOLOGY.name(), t.getMatchType());
        assertEquals(0, BigDecimal.valueOf(0.9).compareTo(t.getConfidence()));
        assertEquals(NodeStatus.CONFIRMED.name(), t.getStatus());
    }

    @Test
    void matchTerminology_emptyMap_returnsEmpty() {
        List<LinkedTarget> targets = service.matchTerminology(
                PROJECT_ID, VERSION_ID, "R1", "文本", Map.of());
        assertTrue(targets.isEmpty());
    }

    @Test
    void matchTerminology_noSourceTermInText_returnsEmpty() {
        Map<String, List<String>> termMap = Map.of("结算单", List.of("Table:lg_settlement"));
        List<LinkedTarget> targets = service.matchTerminology(
                PROJECT_ID, VERSION_ID, "R1", "普通文本不含术语", termMap);
        assertTrue(targets.isEmpty());
    }

    // ==================== matchSemantic ====================

    @Test
    void matchSemantic_aboveThreshold_returnsPendingConfirm() {
        // distance=0.1 → similarity=0.9 > 0.80
        VectorDocument doc = new VectorDocument();
        doc.setSourceUri("node-sem-1");
        doc.setDistance(0.1);
        when(vectorRetrievalService.semanticSearch(eq(PROJECT_ID), eq(VERSION_ID),
                anyString(), eq(10), isNull()))
                .thenReturn(List.of(doc));

        GraphNode node = tableNode("node-sem-1", "public.t_order", "t_order");
        when(neo4jGraphDao.findNodesByIds(List.of("node-sem-1")))
                .thenReturn(List.of(node));

        List<LinkedTarget> targets = service.matchSemantic(
                PROJECT_ID, VERSION_ID, "R1", "导出订单数据");

        assertEquals(1, targets.size());
        LinkedTarget t = targets.get(0);
        assertEquals("node-sem-1", t.getNodeId());
        assertEquals(RequirementLinkingService.MatchType.SEMANTIC.name(), t.getMatchType());
        assertEquals(0, BigDecimal.valueOf(0.9).compareTo(t.getConfidence()));
        assertEquals(NodeStatus.PENDING_CONFIRM.name(), t.getStatus());
    }

    @Test
    void matchSemantic_belowThreshold_returnsEmpty() {
        // distance=0.3 → similarity=0.7 < 0.80
        VectorDocument doc = new VectorDocument();
        doc.setSourceUri("node-sem-2");
        doc.setDistance(0.3);
        when(vectorRetrievalService.semanticSearch(eq(PROJECT_ID), eq(VERSION_ID),
                anyString(), eq(10), isNull()))
                .thenReturn(List.of(doc));

        List<LinkedTarget> targets = service.matchSemantic(
                PROJECT_ID, VERSION_ID, "R1", "无关文本");
        assertTrue(targets.isEmpty());
    }

    @Test
    void matchSemantic_nullDistance_returnsEmpty() {
        VectorDocument doc = new VectorDocument();
        doc.setSourceUri("node-sem-3");
        doc.setDistance(null);
        when(vectorRetrievalService.semanticSearch(any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(doc));

        assertTrue(service.matchSemantic(PROJECT_ID, VERSION_ID, "R1", "文本").isEmpty());
    }

    @Test
    void matchSemantic_emptyResults_returnsEmpty() {
        when(vectorRetrievalService.semanticSearch(any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of());
        assertTrue(service.matchSemantic(PROJECT_ID, VERSION_ID, "R1", "文本").isEmpty());
    }

    // ==================== link 完整流程 ====================

    @Test
    void link_createsAffectsEdgesForExactMatch() {
        RequirementAnalysis analysis = new RequirementAnalysis();
        RequirementItemDTO item = new RequirementItemDTO();
        item.setCode("R1");
        item.setText("修改 public.t_order 表数据");
        analysis.setItems(List.of(item));

        // item 节点查找命中
        GraphNode itemN = itemNode("item-1", "req:req-001:R1");
        when(neo4jGraphDao.queryNodes(eq(PROJECT_ID), eq(VERSION_ID), eq("RequirementItem"),
                eq("req:req-001:R1"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(List.of(itemN));
        // nodeKey "public.t_order" 命中
        GraphNode tableN = tableNode("t1", "public.t_order", "t_order");
        when(neo4jGraphDao.queryNodes(eq(PROJECT_ID), eq(VERSION_ID), isNull(),
                eq("public.t_order"), isNull(), isNull(), isNull(), eq(5)))
                .thenReturn(List.of(tableN));
        // 语义搜索返回空
        when(vectorRetrievalService.semanticSearch(any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of());
        when(neo4jGraphDao.createEdge(any())).thenAnswer(inv -> inv.getArgument(0));

        List<LinkedTarget> targets = service.link(PROJECT_ID, VERSION_ID, REQUIREMENT_ID, analysis);

        assertEquals(1, targets.size());
        assertEquals(NodeStatus.CONFIRMED.name(), targets.get(0).getStatus());

        // 验证 AFFECTS 边创建
        ArgumentCaptor<GraphEdge> edgeCaptor = ArgumentCaptor.forClass(GraphEdge.class);
        verify(neo4jGraphDao).createEdge(edgeCaptor.capture());
        GraphEdge edge = edgeCaptor.getValue();
        assertEquals("item-1", edge.getFromNodeId());
        assertEquals("t1", edge.getToNodeId());
        assertEquals("AFFECTS", edge.getEdgeType());
        assertTrue(edge.getProperties().contains("EXACT_REFERENCE"));
    }

    @Test
    void link_dedupAcrossSteps_keepsFirstMatch() {
        RequirementAnalysis analysis = new RequirementAnalysis();
        RequirementItemDTO item = new RequirementItemDTO();
        item.setCode("R1");
        item.setText("修改 public.t_order");
        analysis.setItems(List.of(item));

        GraphNode itemN = itemNode("item-1", "req:req-001:R1");
        when(neo4jGraphDao.queryNodes(eq(PROJECT_ID), eq(VERSION_ID), eq("RequirementItem"),
                eq("req:req-001:R1"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(List.of(itemN));
        // 显式匹配命中
        GraphNode tableN = tableNode("t1", "public.t_order", "t_order");
        when(neo4jGraphDao.queryNodes(eq(PROJECT_ID), eq(VERSION_ID), isNull(),
                eq("public.t_order"), isNull(), isNull(), isNull(), eq(5)))
                .thenReturn(List.of(tableN));
        // 语义匹配也命中同一节点（应被去重）
        VectorDocument doc = new VectorDocument();
        doc.setSourceUri("t1");
        doc.setDistance(0.1);
        when(vectorRetrievalService.semanticSearch(any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(doc));
        when(neo4jGraphDao.findNodesByIds(anyList())).thenReturn(List.of(tableN));

        List<LinkedTarget> targets = service.link(PROJECT_ID, VERSION_ID, REQUIREMENT_ID, analysis);

        // 同一 nodeId 只保留一条（显式匹配优先）
        assertEquals(1, targets.size());
        assertEquals(RequirementLinkingService.MatchType.EXACT_REFERENCE.name(),
                targets.get(0).getMatchType());
        // AFFECTS 边只创建一次
        verify(neo4jGraphDao, times(1)).createEdge(any());
    }

    @Test
    void link_nullAnalysis_returnsEmpty() {
        assertTrue(service.link(PROJECT_ID, VERSION_ID, REQUIREMENT_ID, null).isEmpty());
    }

    @Test
    void link_emptyItems_returnsEmpty() {
        RequirementAnalysis analysis = new RequirementAnalysis();
        analysis.setItems(List.of());
        assertTrue(service.link(PROJECT_ID, VERSION_ID, REQUIREMENT_ID, analysis).isEmpty());
    }

    @Test
    void link_itemNodeNotFound_skipsEdgesButReturnsTargets() {
        RequirementAnalysis analysis = new RequirementAnalysis();
        RequirementItemDTO item = new RequirementItemDTO();
        item.setCode("R1");
        item.setText("修改 public.t_order");
        analysis.setItems(List.of(item));

        // item 节点不命中（queryNodes 默认返回空）
        GraphNode tableN = tableNode("t1", "public.t_order", "t_order");
        when(neo4jGraphDao.queryNodes(eq(PROJECT_ID), eq(VERSION_ID), isNull(),
                eq("public.t_order"), isNull(), isNull(), isNull(), eq(5)))
                .thenReturn(List.of(tableN));
        when(vectorRetrievalService.semanticSearch(any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of());

        List<LinkedTarget> targets = service.link(PROJECT_ID, VERSION_ID, REQUIREMENT_ID, analysis);

        assertEquals(1, targets.size());
        // 未创建 AFFECTS 边（item 节点不存在）
        verify(neo4jGraphDao, never()).createEdge(any());
    }
}
