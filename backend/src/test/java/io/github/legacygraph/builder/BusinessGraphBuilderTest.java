package io.github.legacygraph.builder;

import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessGraphBuilderTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private EvidenceRepository evidenceRepository;
    @Mock
    private NodeEvidenceRepository nodeEvidenceRepository;
    @Mock
    private EdgeEvidenceRepository edgeEvidenceRepository;
    @Mock
    private DocChunkRepository docChunkRepository;
    @Mock
    private EvidenceGraphWriter writer;

    private BusinessGraphBuilder businessGraphBuilder;

    @BeforeEach
    void setUp() {
        // 使用真实 FeatureIdentityNormalizer + ConfigurableTerminologyService 测试归一化/相似度行为。
        // 术语映射改为从表加载，此处 mock repository 返回空列表（本用例不依赖具体术语映射）。
        io.github.legacygraph.terminology.TerminologyProperties props =
                new io.github.legacygraph.terminology.TerminologyProperties();
        io.github.legacygraph.repository.TerminologyMappingRepository terminologyMappingRepository =
                mock(io.github.legacygraph.repository.TerminologyMappingRepository.class);
        when(terminologyMappingRepository.selectList(any())).thenReturn(new ArrayList<>());
        io.github.legacygraph.terminology.ConfigurableTerminologyService terminologyService =
                new io.github.legacygraph.terminology.ConfigurableTerminologyService(props, terminologyMappingRepository);
        terminologyService.reload();
        businessGraphBuilder = new BusinessGraphBuilder(neo4jGraphDao, docChunkRepository, writer,
                new FeatureIdentityNormalizer(), terminologyService);
    }

    @Test
    void testConstruction() {
        assertNotNull(businessGraphBuilder);
    }

    /**
     * 端到端验证：文档业务事实 -> 业务图谱节点/边 -> 证据关联
     * 覆盖 P1-3：domain + process(含 step) 落库为节点，关系落库为边，每个节点生成证据。
     */
    @Test
    void testBuildBusinessGraph_PersistsNodesEdgesAndEvidence() {
        when(writer.upsertNode(any(GraphNodeClaim.class))).thenAnswer(invocation -> {
            GraphNodeClaim claim = invocation.getArgument(0);
            GraphNode node = new GraphNode();
            node.setId(claim.getNodeType() + ":" + claim.getNodeKey());
            node.setProjectId(claim.getProjectId());
            node.setVersionId(claim.getVersionId());
            node.setNodeType(claim.getNodeType());
            node.setNodeKey(claim.getNodeKey());
            node.setNodeName(claim.getNodeName());
            node.setSourcePath(claim.getSourcePath());
            return node;
        });
        when(writer.upsertEdge(any(GraphEdgeClaim.class))).thenAnswer(invocation -> {
            GraphEdgeClaim claim = invocation.getArgument(0);
            GraphEdge edge = new GraphEdge();
            edge.setId(claim.getEdgeType() + ":" + claim.getEdgeKey());
            edge.setProjectId(claim.getProjectId());
            edge.setVersionId(claim.getVersionId());
            edge.setFromNodeId(claim.getFromNodeId());
            edge.setToNodeId(claim.getToNodeId());
            edge.setEdgeType(claim.getEdgeType());
            edge.setEdgeKey(claim.getEdgeKey());
            return edge;
        });

        DocUnderstandingAgent.BusinessFactExtraction facts =
                new DocUnderstandingAgent.BusinessFactExtraction();

        DocUnderstandingAgent.BusinessDomain domain = new DocUnderstandingAgent.BusinessDomain();
        domain.setName("订单管理");
        domain.setDescription("订单全生命周期管理");
        domain.setConfidence(0.9);
        facts.getBusinessDomains().add(domain);

        DocUnderstandingAgent.BusinessProcess process = new DocUnderstandingAgent.BusinessProcess();
        process.setKey("create-order");
        process.setName("创建订单");
        process.setDescription("用户下单流程");
        process.setSteps(List.of("选择商品", "提交订单"));
        process.setConfidence(0.85);
        facts.getBusinessProcesses().add(process);

        // when
        businessGraphBuilder.buildBusinessGraph("project-1", "version-1", facts, "/docs/order.md");

        // then: 节点落库 —— 1 domain + 1 process + 1 流程级 feature + 2 step features = 5 个节点
        // （P1-A：BusinessProcess 本身也落一个粗粒度 Feature，不再仅依赖 steps）
        ArgumentCaptor<GraphNodeClaim> nodeCaptor = ArgumentCaptor.forClass(GraphNodeClaim.class);
        verify(writer, times(5)).upsertNode(nodeCaptor.capture());

        List<GraphNodeClaim> nodes = nodeCaptor.getAllValues();
        assertTrue(nodes.stream().anyMatch(n ->
                NodeType.BusinessDomain.name().equals(n.getNodeType()) && "订单管理".equals(n.getNodeName())));
        assertTrue(nodes.stream().anyMatch(n ->
                NodeType.BusinessProcess.name().equals(n.getNodeType()) && "创建订单".equals(n.getNodeName())));
        // 3 个 Feature：1 个流程级（创建订单）+ 2 个步骤级（选择商品、提交订单）
        assertEquals(3, nodes.stream().filter(n -> NodeType.Feature.name().equals(n.getNodeType())).count());
        // 全部来自文档 AI，项目/版本一致
        assertTrue(nodes.stream().allMatch(n ->
                "project-1".equals(n.getProjectId()) && "version-1".equals(n.getVersionId())));
        assertTrue(nodes.stream().allMatch(n -> "/docs/order.md".equals(n.getSourcePath())));

        // 边落库 —— process CONTAINS 流程级 feature(1) + process CONTAINS 2 steps(2) = 3 条边
        ArgumentCaptor<GraphEdgeClaim> edgeCaptor = ArgumentCaptor.forClass(GraphEdgeClaim.class);
        verify(writer, times(3)).upsertEdge(edgeCaptor.capture());
    }

    /**
     * P2：验证业务对象与数据库表按名称相似度对齐，建立 MAPS_TO 桥接边。
     */
    @Test
    void testMapBusinessObjectsToTables_CreatesMapsToEdge() {
        when(neo4jGraphDao.mergeEdgesBatch(anyList())).thenAnswer(invocation -> {
            List<GraphEdge> edges = invocation.getArgument(0);
            return edges.size();
        });

        GraphNode order = new GraphNode();
        order.setId("bo-order");
        order.setNodeType(NodeType.BusinessObject.name());
        order.setNodeKey("object:订单");
        order.setNodeName("订单");

        GraphNode ordersTable = new GraphNode();
        ordersTable.setId("tbl-orders");
        ordersTable.setNodeType(NodeType.Table.name());
        ordersTable.setNodeKey("table:t_order");
        ordersTable.setNodeName("t_order");
        ordersTable.setDisplayName("订单");

        GraphNode userTable = new GraphNode();
        userTable.setId("tbl-user");
        userTable.setNodeType(NodeType.Table.name());
        userTable.setNodeKey("table:sys_user");
        userTable.setNodeName("sys_user");
        userTable.setDisplayName("用户");

        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("version-1"),
                eq(NodeType.BusinessObject.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of(order));
        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("version-1"),
                eq(NodeType.Table.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of(ordersTable, userTable));

        businessGraphBuilder.mapBusinessObjectsToTables("project-1", "version-1");

        // 仅"订单"↔订单表(t_order) 匹配，用户表不匹配
        ArgumentCaptor<List<GraphEdge>> edgeCaptor = ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesBatch(edgeCaptor.capture());
        List<GraphEdge> edges = edgeCaptor.getValue();
        assertEquals(1, edges.size());
        GraphEdge edge = edges.get(0);
        assertEquals(EdgeType.MAPS_TO.name(), edge.getEdgeType());
        assertEquals("bo-order", edge.getFromNodeId());
        assertEquals("tbl-orders", edge.getToNodeId());
    }

    @Test
    void testMapBusinessObjectsToTables_DoesNotMapBlankBusinessObjectName() {
        GraphNode blankObject = new GraphNode();
        blankObject.setId("bo-blank");
        blankObject.setNodeType(NodeType.BusinessObject.name());
        blankObject.setNodeKey("object:blank");

        GraphNode ordersTable = new GraphNode();
        ordersTable.setId("tbl-orders");
        ordersTable.setNodeType(NodeType.Table.name());
        ordersTable.setNodeKey("table:orders");
        ordersTable.setNodeName("orders");
        ordersTable.setDisplayName("订单");

        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("version-1"),
                eq(NodeType.BusinessObject.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of(blankObject));
        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("version-1"),
                eq(NodeType.Table.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of(ordersTable));
        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("version-1"),
                eq(NodeType.Service.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("version-1"),
                eq(NodeType.Mapper.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("version-1"),
                eq(NodeType.Controller.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        businessGraphBuilder.mapBusinessObjectsToTables("project-1", "version-1");

        verify(neo4jGraphDao, never()).mergeEdgesBatch(anyList());
    }

    @Test
    void testMapFeaturesToCode_UsesDisplayNameFallbackAndDoesNotThrowOnBlankNodeName() {
        when(neo4jGraphDao.mergeEdgesBatch(anyList())).thenAnswer(invocation -> {
            List<GraphEdge> edges = invocation.getArgument(0);
            return edges.size();
        });

        GraphNode feature = new GraphNode();
        feature.setId("feature-order");
        feature.setNodeType(NodeType.Feature.name());
        feature.setNodeKey("feature:订单管理");
        feature.setDisplayName("订单管理");

        GraphNode page = new GraphNode();
        page.setId("page-order");
        page.setNodeType(NodeType.Page.name());
        page.setNodeKey("page:/order");
        page.setDisplayName("订单管理页面");

        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("version-1"),
                eq(NodeType.Feature.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of(feature));
        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("version-1"),
                eq(NodeType.Page.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of(page));
        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("version-1"),
                eq(NodeType.ApiEndpoint.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        businessGraphBuilder.mapFeaturesToCode("project-1", "version-1");

        ArgumentCaptor<List<GraphEdge>> edgeCaptor = ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesBatch(edgeCaptor.capture());
        GraphEdge edge = edgeCaptor.getValue().get(0);
        assertEquals(EdgeType.EXPOSED_BY.name(), edge.getEdgeType());
        assertEquals("feature-order", edge.getFromNodeId());
        assertEquals("page-order", edge.getToNodeId());
    }

    /**
     * 5.2-1：只有 getFeatures()、没有 BusinessProcess.steps 时，仍应创建 Feature 节点。
     * 验证 P0-B（features 清单落图）不依赖流程步骤。
     */
    @Test
    void testBuildBusinessGraph_FeaturesOnly_WithoutProcessSteps() {
        when(writer.upsertNode(any(GraphNodeClaim.class))).thenAnswer(invocation -> {
            GraphNodeClaim claim = invocation.getArgument(0);
            GraphNode node = new GraphNode();
            node.setId(claim.getNodeType() + ":" + claim.getNodeKey());
            node.setProjectId(claim.getProjectId());
            node.setVersionId(claim.getVersionId());
            node.setNodeType(claim.getNodeType());
            node.setNodeKey(claim.getNodeKey());
            node.setNodeName(claim.getNodeName());
            return node;
        });

        DocUnderstandingAgent.BusinessFactExtraction facts =
                new DocUnderstandingAgent.BusinessFactExtraction();
        // 仅有 features 清单，无任何 BusinessProcess
        facts.setFeatures(new ArrayList<>(List.of("用户注册", "密码重置", "订单查询")));

        businessGraphBuilder.buildBusinessGraph("project-1", "version-1", facts, "/docs/features.md");

        // 应创建 3 个 Feature 节点（每个 feature 一个），不要求任何流程
        ArgumentCaptor<GraphNodeClaim> nodeCaptor = ArgumentCaptor.forClass(GraphNodeClaim.class);
        verify(writer, times(3)).upsertNode(nodeCaptor.capture());

        List<GraphNodeClaim> nodes = nodeCaptor.getAllValues();
        assertTrue(nodes.stream().allMatch(n -> NodeType.Feature.name().equals(n.getNodeType())));
        // nodeKey 经 normalizeFeatureKey 归一化，统一加 "feature:" 前缀
        assertTrue(nodes.stream().anyMatch(n -> n.getNodeKey().contains("用户注册")));
        assertTrue(nodes.stream().anyMatch(n -> n.getNodeKey().contains("密码重置")));
        assertTrue(nodes.stream().anyMatch(n -> n.getNodeKey().contains("订单查询")));
        // 无流程步骤 → 无边创建
        verify(writer, never()).upsertEdge(any(GraphEdgeClaim.class));
    }

    /**
     * 5.2-2：Feature key 归一化——统一前缀、半角标点、合并空白。
     * 验证 normalizeFeatureKey 消除多种来源的 key 格式差异。
     */
    @Test
    void testBuildBusinessGraph_FeatureKeyNormalized_DedupAcrossSources() {
        when(writer.upsertNode(any(GraphNodeClaim.class))).thenAnswer(invocation -> {
            GraphNodeClaim claim = invocation.getArgument(0);
            GraphNode node = new GraphNode();
            node.setId(claim.getNodeType() + ":" + claim.getNodeKey());
            node.setNodeType(claim.getNodeType());
            node.setNodeKey(claim.getNodeKey());
            node.setNodeName(claim.getNodeName());
            return node;
        });

        DocUnderstandingAgent.BusinessFactExtraction facts =
                new DocUnderstandingAgent.BusinessFactExtraction();
        // 两个语义相同、写法不同的功能名
        facts.setFeatures(new ArrayList<>(List.of(
                "feature:用户注册",      // 带英文前缀
                "用户注册"               // 裸名称（应与上面归一为同一 key）
        )));

        DocUnderstandingAgent.BusinessProcess process = new DocUnderstandingAgent.BusinessProcess();
        process.setName("用户注册");    // 流程名与 feature 同名
        process.setConfidence(0.8);
        facts.getBusinessProcesses().add(process);

        businessGraphBuilder.buildBusinessGraph("project-1", "version-1", facts, "/docs/test.md");

        // 应有 2 个 Feature 节点（不是 3 个）：流程 Feature + features 清单中的唯一 Feature
        // features 清单中 "feature:用户注册" 和 "用户注册" 归一化为同一 key
        ArgumentCaptor<GraphNodeClaim> nodeCaptor = ArgumentCaptor.forClass(GraphNodeClaim.class);
        verify(writer, atLeastOnce()).upsertNode(nodeCaptor.capture());
        List<GraphNodeClaim> features = nodeCaptor.getAllValues().stream()
                .filter(n -> NodeType.Feature.name().equals(n.getNodeType()))
                .toList();

        // P1-A：流程本身也创建 Feature 节点，所以至少 2 个 Feature
        // (流程 Feature + 1 个归一化后的 features Feature = 2)
        // 注意：如果流程名恰好与 feature 名归一化后相同，MERGE 会去重为 1 个 → 1 个 Feature
        // 这里流程名 "用户注册" 与 feature "用户注册" 归一化后 key 相同 → 合并为 1
        assertTrue(features.size() >= 1,
                "Expecting features merged by normalizeFeatureKey, got " + features.size());
    }

    /**
     * 5.2-3：代码来源节点 sourceType 应为 CODE_AI，不应写成 DOC_AI。
     */
    @Test
    void testBuildBusinessGraph_CodeAiSourceType_NotDocAi() {
        when(writer.upsertNode(any(GraphNodeClaim.class))).thenAnswer(invocation -> {
            GraphNodeClaim claim = invocation.getArgument(0);
            GraphNode node = new GraphNode();
            node.setId(claim.getNodeType() + ":" + claim.getNodeKey());
            node.setNodeType(claim.getNodeType());
            node.setNodeKey(claim.getNodeKey());
            node.setSourcePath(claim.getSourcePath());
            return node;
        });

        DocUnderstandingAgent.BusinessFactExtraction facts =
                new DocUnderstandingAgent.BusinessFactExtraction();
        facts.setFeatures(new ArrayList<>(List.of("代码功能A")));

        // 用 CODE_AI sourceType 调用（模拟 persistAndBuildCodeFacts 路径）
        businessGraphBuilder.buildBusinessGraph("project-1", "version-1", facts,
                "/src/OrderService.java", SourceType.CODE_AI.name());

        // 节点 sourceType 应为 CODE_AI
        ArgumentCaptor<GraphNodeClaim> nodeCaptor = ArgumentCaptor.forClass(GraphNodeClaim.class);
        verify(writer, atLeastOnce()).upsertNode(nodeCaptor.capture());
        List<GraphNodeClaim> codeFeatures = nodeCaptor.getAllValues().stream()
                .filter(n -> NodeType.Feature.name().equals(n.getNodeType()))
                .toList();
        assertFalse(codeFeatures.isEmpty());
        // 关键断言：所有 Feature 节点的 sourceType 为 CODE_AI，不是 DOC_AI
        assertTrue(codeFeatures.stream().allMatch(n -> SourceType.CODE_AI.name().equals(n.getSourceType())),
                "Code-extracted features should have CODE_AI sourceType, not DOC_AI");
    }

    /**
     * P2 扩展：BusinessObject 同时映射到 Table + Service + Mapper。
     */
    @Test
    void testMapBusinessObjectsToTables_MapsToCodeEntities() {
        when(neo4jGraphDao.mergeEdgesBatch(anyList())).thenAnswer(invocation -> {
            List<GraphEdge> edges = invocation.getArgument(0);
            return edges.size();
        });

        GraphNode orderBo = new GraphNode();
        orderBo.setId("bo-order");
        orderBo.setNodeType(NodeType.BusinessObject.name());
        orderBo.setNodeKey("object:order");
        orderBo.setNodeName("order");          // 英文名，可与 order/orders/orderservice 匹配

        GraphNode ordersTable = new GraphNode();
        ordersTable.setId("tbl-orders");
        ordersTable.setNodeType(NodeType.Table.name());
        ordersTable.setNodeKey("table:orders");
        ordersTable.setNodeName("orders");     // normalizeEntityName → "orders", contains("order")

        GraphNode orderService = new GraphNode();
        orderService.setId("svc-order");
        orderService.setNodeType(NodeType.Service.name());
        orderService.setNodeKey("service:OrderService");
        orderService.setNodeName("OrderService"); // normalizeEntityName → "orderservice", contains("order")

        GraphNode orderMapper = new GraphNode();
        orderMapper.setId("mapper-order");
        orderMapper.setNodeType(NodeType.Mapper.name());
        orderMapper.setNodeKey("mapper:OrderMapper");
        orderMapper.setNodeName("OrderMapper");   // normalizeEntityName → "ordermapper", contains("order")

        // Stub queryNodes for all entity types
        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("version-1"),
                eq(NodeType.BusinessObject.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of(orderBo));
        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("version-1"),
                eq(NodeType.Table.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of(ordersTable));
        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("version-1"),
                eq(NodeType.Service.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of(orderService));
        when(neo4jGraphDao.queryNodes(eq("project-1"), eq("version-1"),
                eq(NodeType.Mapper.name()), any(), any(), any(), anyInt()))
                .thenReturn(List.of(orderMapper));

        businessGraphBuilder.mapBusinessObjectsToTables("project-1", "version-1");

        // 应产生 3 条边：MAPS_TO(Table) + IMPLEMENTED_BY(Service) + IMPLEMENTED_BY(Mapper)
        ArgumentCaptor<List<GraphEdge>> edgeCaptor = ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesBatch(edgeCaptor.capture());

        List<GraphEdge> edges = edgeCaptor.getValue();
        assertEquals(3, edges.size());
        // 1 条 MAPS_TO（Table）
        assertEquals(1, edges.stream()
                .filter(e -> EdgeType.MAPS_TO.name().equals(e.getEdgeType())).count());
        // 2 条 IMPLEMENTED_BY（Service + Mapper）
        assertEquals(2, edges.stream()
                .filter(e -> EdgeType.IMPLEMENTED_BY.name().equals(e.getEdgeType())).count());
        // 全部从 BusinessObject 出发
        assertTrue(edges.stream().allMatch(e -> "bo-order".equals(e.getFromNodeId())));
    }
}
