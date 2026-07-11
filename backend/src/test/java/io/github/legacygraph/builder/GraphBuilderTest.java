package io.github.legacygraph.builder;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dao.Neo4jWriteRepository;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.extractors.DatabaseMetadataExtractor;
import io.github.legacygraph.extractors.ExternalSystemExtractor;
import io.github.legacygraph.extractors.FeatureModuleExtractor;
import io.github.legacygraph.extractors.JavaStructureExtractor;
import io.github.legacygraph.extractors.MQExtractor;
import io.github.legacygraph.extractors.MyBatisXmlExtractor;
import io.github.legacygraph.extractors.ScheduledJobExtractor;
import io.github.legacygraph.extractors.TestCaseExtractor;
import io.github.legacygraph.model.MapperSqlFact;
import io.github.legacygraph.model.NodeExtractionResult;
import io.github.legacygraph.model.UserRoleAssignment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphBuilderTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private EvidenceGraphWriter writer;

    private GraphBuilder graphBuilder;

    @Test
    void testConstruction() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);
        assertNotNull(graphBuilder);
    }

    @Test
    void normalizeApiKey_StripsQueryAndUnifiesParameterNames() {
        assertEquals(
                "GET /lg/projects/{id}/scan-versions/{id}",
                GraphBuilder.normalizeApiKey(
                        "get",
                        "/lg/projects/${projectId}/scan-versions/${versionId}?includeLogs=true"
                )
        );

        assertEquals(
                "GET /lg/projects/{id}/scan-versions/{id}",
                GraphBuilder.normalizeApiKey(
                        "GET",
                        "/lg/projects/{projectId}/scan-versions/{versionId}"
                )
        );
    }

    /**
     * C：扫描期不再调用 SQL 顾问 LLM，buildMapperSqlGraph 仅构建图谱节点/边。
     */
    @Test
    void testBuildMapperSqlGraph_BuildsGraphWithoutLlm() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);
        when(writer.upsertNode(any(GraphNodeClaim.class))).thenAnswer(invocation -> {
            GraphNodeClaim claim = invocation.getArgument(0);
            GraphNode node = new GraphNode();
            node.setId(claim.getNodeType() + ":" + claim.getNodeKey());
            node.setProjectId(claim.getProjectId());
            node.setVersionId(claim.getVersionId());
            node.setNodeType(claim.getNodeType());
            node.setNodeKey(claim.getNodeKey());
            node.setNodeName(claim.getNodeName());
            node.setDisplayName(claim.getDisplayName());
            node.setConfidence(claim.getConfidence());
            node.setStatus(claim.getStatus());
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
            edge.setStatus(claim.getStatus());
            return edge;
        });

        MyBatisXmlExtractor.SqlStatement stmt = new MyBatisXmlExtractor.SqlStatement();
        stmt.setId("list");
        stmt.setType("select");
        stmt.setSql("select * from orders");
        MapperSqlFact mapper = new MapperSqlFact();
        mapper.setNamespace("com.demo.OrderMapper");
        mapper.setMapperInterface("OrderMapper");
        mapper.setSourcePath("/tmp/OrderMapper.xml");
        mapper.setStatements(List.of(stmt));

        graphBuilder.buildMapperSqlGraph("project-1", "v1", mapper);

        // 仍构建 Mapper、SqlStatement、Table 等节点与 CONTAINS/READS/EXECUTES 等边
        verify(neo4jGraphDao).mergeNodesBatch(eq("project-1"), eq("v1"), anyList());
        verify(neo4jGraphDao).mergeEdgesByKeyBatch(eq("project-1"), eq("v1"), anyList());
    }

    @Test
    void buildJavaStructureGraph_createsClassAndMethodNodesForPlainJavaClasses() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);
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

        JavaStructureExtractor.JavaMethodInfo createMethod =
                new JavaStructureExtractor.JavaMethodInfo("create", "com.demo.OrderService.create", 8, 10);
        JavaStructureExtractor.JavaMethodInfo cancelMethod =
                new JavaStructureExtractor.JavaMethodInfo("cancel", "com.demo.OrderService.cancel", 12, 14);
        JavaStructureExtractor.JavaClassInfo classInfo = new JavaStructureExtractor.JavaClassInfo(
                "OrderService",
                "com.demo",
                "com.demo.OrderService",
                "CLASS",
                "/tmp/OrderService.java",
                5,
                15,
                List.of(createMethod, cancelMethod),
                List.of(),
                List.of(),
                List.of()
        );

        graphBuilder.buildJavaStructureGraph("project-1", "v1", List.of(classInfo));

        // 生产代码使用 neo4jGraphDao 批量写入，不再走 writer.upsertNode/upsertEdge
        @SuppressWarnings("unchecked")
        var nodeBatchCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeNodesBatch(eq("project-1"), eq("v1"), nodeBatchCaptor.capture());
        List<Neo4jWriteRepository.BatchNodeUpsert> nodes = nodeBatchCaptor.getValue();
        assertEquals(3, nodes.size());
        assertTrue(nodes.stream().anyMatch(n ->
                NodeType.Service.name().equals(n.nodeType())
                        && "com.demo.OrderService".equals(n.nodeKey())));
        assertEquals(2, nodes.stream().filter(n -> NodeType.Method.name().equals(n.nodeType())).count());

        @SuppressWarnings("unchecked")
        var edgeBatchCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesByKeyBatch(eq("project-1"), eq("v1"), edgeBatchCaptor.capture());
        List<Neo4jWriteRepository.BatchEdgeByKeyUpsert> edges = edgeBatchCaptor.getValue();
        assertEquals(2, edges.size());
        assertTrue(edges.stream()
                .allMatch(e -> EdgeType.CONTAINS.name().equals(e.edgeType())));
    }

    @Test
    void buildDatabaseGraph_putsColumnMetadataIntoNodeProperties() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);
        when(writer.upsertNode(any(GraphNodeClaim.class))).thenAnswer(invocation -> {
            GraphNodeClaim claim = invocation.getArgument(0);
            GraphNode node = new GraphNode();
            node.setId(claim.getNodeType() + ":" + claim.getNodeKey());
            node.setNodeType(claim.getNodeType());
            node.setNodeKey(claim.getNodeKey());
            node.setNodeName(claim.getNodeName());
            node.setProperties(claim.getProperties());
            return node;
        });
        lenient().when(writer.upsertEdge(any(GraphEdgeClaim.class))).thenAnswer(invocation -> {
            GraphEdgeClaim claim = invocation.getArgument(0);
            GraphEdge edge = new GraphEdge();
            edge.setId(claim.getEdgeType() + ":" + claim.getEdgeKey());
            edge.setEdgeType(claim.getEdgeType());
            edge.setEdgeKey(claim.getEdgeKey());
            return edge;
        });
        DatabaseMetadataExtractor.ColumnMetadata column = new DatabaseMetadataExtractor.ColumnMetadata();
        column.setColumnName("status");
        column.setDataType("12");
        column.setTypeName("varchar");
        column.setColumnSize(32);
        column.setNullable(false);
        column.setColumnDefault("'NEW'");
        column.setColumnComment("订单状态");
        column.setPrimaryKey(false);
        column.setSemanticType("status");

        DatabaseMetadataExtractor.TableMetadata table = new DatabaseMetadataExtractor.TableMetadata();
        table.setTableSchema("public");
        table.setTableName("orders");
        table.setTableComment("订单表");
        table.setColumns(List.of(column));

        graphBuilder.buildDatabaseGraph("project-1", "v1", List.of(table));

        var nodeCaptor = org.mockito.ArgumentCaptor.forClass(GraphNodeClaim.class);
        verify(writer, atLeast(1)).upsertNode(nodeCaptor.capture());
        GraphNodeClaim statusColumn = nodeCaptor.getAllValues().stream()
                .filter(claim -> "orders.status".equals(claim.getNodeKey()))
                .findFirst()
                .orElseThrow();

        assertNotNull(statusColumn.getProperties());
        assertTrue(statusColumn.getProperties().contains("\"nullable\":false"));
        assertTrue(statusColumn.getProperties().contains("\"columnDefault\":\"'NEW'\""));
        assertTrue(statusColumn.getProperties().contains("\"semanticType\":\"status\""));
    }

    @Test
    void buildFeatureModuleGraph_createsModuleFeatureContainsEdgeInSameBatch() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);
        when(neo4jGraphDao.findNode(any(), any(), any(), any())).thenReturn(Optional.empty());

        FeatureModuleExtractor.FeatureModuleFact module = new FeatureModuleExtractor.FeatureModuleFact();
        module.setModuleName("orders");
        module.setModulePath("/tmp/frontend/src/views/orders");
        module.setPageCount(1);

        FeatureModuleExtractor.FeatureFact feature = new FeatureModuleExtractor.FeatureFact();
        feature.setModuleName("orders");
        feature.setFeatureName("index");
        feature.setFeaturePath("/tmp/frontend/src/views/orders/index.vue");

        graphBuilder.buildFeatureModuleGraph("project-1", "v1", List.of(module), List.of(feature));

        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesBatch(edgeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphEdge> edges = edgeCaptor.getValue();

        assertTrue(edges.stream().anyMatch(edge ->
                EdgeType.CONTAINS.name().equals(edge.getEdgeType())
                        && "module:orders->contains->feature:orders/index".equals(edge.getEdgeKey())));
    }

    @Test
    void buildScheduledJobGraph_createsMethodAndHandledByEdge() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);
        when(writer.upsertNode(any(GraphNodeClaim.class))).thenAnswer(invocation -> {
            GraphNodeClaim claim = invocation.getArgument(0);
            GraphNode node = new GraphNode();
            node.setId(claim.getNodeType() + ":" + claim.getNodeKey());
            node.setNodeType(claim.getNodeType());
            node.setNodeKey(claim.getNodeKey());
            node.setNodeName(claim.getNodeName());
            return node;
        });
        when(writer.upsertEdge(any(GraphEdgeClaim.class))).thenAnswer(invocation -> {
            GraphEdgeClaim claim = invocation.getArgument(0);
            GraphEdge edge = new GraphEdge();
            edge.setId(claim.getEdgeType() + ":" + claim.getEdgeKey());
            edge.setEdgeType(claim.getEdgeType());
            edge.setEdgeKey(claim.getEdgeKey());
            return edge;
        });

        ScheduledJobExtractor.ScheduledJobFact job = new ScheduledJobExtractor.ScheduledJobFact();
        job.setClassName("com.demo.OrderJob");
        job.setMethodName("sync");
        job.setMethodSignature("sync()");
        job.setAnnotationType("Scheduled");
        job.setCronExpression("0 0 * * * ?");
        job.setSourcePath("/tmp/OrderJob.java");

        graphBuilder.buildScheduledJobGraph("project-1", "v1", List.of(job));

        var nodeCaptor = org.mockito.ArgumentCaptor.forClass(GraphNodeClaim.class);
        verify(writer, atLeast(1)).upsertNode(nodeCaptor.capture());
        assertTrue(nodeCaptor.getAllValues().stream().anyMatch(claim ->
                NodeType.Method.name().equals(claim.getNodeType())
                        && "com.demo.OrderJob.sync()".equals(claim.getNodeKey())));

        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(GraphEdgeClaim.class);
        verify(writer, atLeast(1)).upsertEdge(edgeCaptor.capture());
        assertTrue(edgeCaptor.getAllValues().stream().anyMatch(claim ->
                EdgeType.HANDLED_BY.name().equals(claim.getEdgeType())
                        && "job:com.demo.OrderJob.sync->handled_by->com.demo.OrderJob.sync()".equals(claim.getEdgeKey())));
    }

    @Test
    void buildMQGraph_createsConsumerTopicAndMethodEdges() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);
        when(writer.upsertNode(any(GraphNodeClaim.class))).thenAnswer(invocation -> {
            GraphNodeClaim claim = invocation.getArgument(0);
            GraphNode node = new GraphNode();
            node.setId(claim.getNodeType() + ":" + claim.getNodeKey());
            node.setNodeType(claim.getNodeType());
            node.setNodeKey(claim.getNodeKey());
            node.setNodeName(claim.getNodeName());
            return node;
        });
        when(writer.upsertEdge(any(GraphEdgeClaim.class))).thenAnswer(invocation -> {
            GraphEdgeClaim claim = invocation.getArgument(0);
            GraphEdge edge = new GraphEdge();
            edge.setId(claim.getEdgeType() + ":" + claim.getEdgeKey());
            edge.setEdgeType(claim.getEdgeType());
            edge.setEdgeKey(claim.getEdgeKey());
            return edge;
        });

        MQExtractor.MQConsumerFact consumer = new MQExtractor.MQConsumerFact();
        consumer.setClassName("com.demo.OrderListener");
        consumer.setMethodName("handle");
        consumer.setMethodSignature("handle(String)");
        consumer.setAnnotationType("KafkaListener");
        consumer.setTopic("order.created");
        consumer.setConsumerGroup("order-service");
        consumer.setSourcePath("/tmp/OrderListener.java");

        graphBuilder.buildMQGraph("project-1", "v1", List.of(consumer));

        var nodeCaptor = org.mockito.ArgumentCaptor.forClass(GraphNodeClaim.class);
        verify(writer, atLeast(1)).upsertNode(nodeCaptor.capture());
        assertTrue(nodeCaptor.getAllValues().stream().anyMatch(claim ->
                NodeType.MQConsumer.name().equals(claim.getNodeType())));
        assertTrue(nodeCaptor.getAllValues().stream().anyMatch(claim ->
                NodeType.MQTopic.name().equals(claim.getNodeType())
                        && "mq-topic:order.created".equals(claim.getNodeKey())));
        assertTrue(nodeCaptor.getAllValues().stream().anyMatch(claim ->
                NodeType.Method.name().equals(claim.getNodeType())
                        && "com.demo.OrderListener.handle(String)".equals(claim.getNodeKey())));

        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(GraphEdgeClaim.class);
        verify(writer, atLeast(1)).upsertEdge(edgeCaptor.capture());
        assertTrue(edgeCaptor.getAllValues().stream().anyMatch(claim ->
                EdgeType.CONSUMES.name().equals(claim.getEdgeType())));
        assertTrue(edgeCaptor.getAllValues().stream().anyMatch(claim ->
                EdgeType.HANDLED_BY.name().equals(claim.getEdgeType())));
        assertTrue(edgeCaptor.getAllValues().stream().anyMatch(claim ->
                EdgeType.TRIGGERS.name().equals(claim.getEdgeType())));
    }

    @Test
    void buildExternalSystemGraph_createsExternalEndpointAndMethodEdges() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);
        when(writer.upsertNode(any(GraphNodeClaim.class))).thenAnswer(invocation -> {
            GraphNodeClaim claim = invocation.getArgument(0);
            GraphNode node = new GraphNode();
            node.setId(claim.getNodeType() + ":" + claim.getNodeKey());
            node.setNodeType(claim.getNodeType());
            node.setNodeKey(claim.getNodeKey());
            node.setNodeName(claim.getNodeName());
            return node;
        });
        when(writer.upsertEdge(any(GraphEdgeClaim.class))).thenAnswer(invocation -> {
            GraphEdgeClaim claim = invocation.getArgument(0);
            GraphEdge edge = new GraphEdge();
            edge.setId(claim.getEdgeType() + ":" + claim.getEdgeKey());
            edge.setEdgeType(claim.getEdgeType());
            edge.setEdgeKey(claim.getEdgeKey());
            return edge;
        });

        ExternalSystemExtractor.ExternalCallFact call = new ExternalSystemExtractor.ExternalCallFact();
        call.setClassName("com.demo.PaymentClient");
        call.setMethodName("pay");
        call.setMethodSignature("pay(PaymentRequest)");
        call.setClientType("RestTemplate");
        call.setBaseUrl("https://pay.example.com/api/pay");
        call.setSourcePath("/tmp/PaymentClient.java");

        graphBuilder.buildExternalSystemGraph("project-1", "v1", List.of(call));

        var nodeCaptor = org.mockito.ArgumentCaptor.forClass(GraphNodeClaim.class);
        verify(writer, atLeast(1)).upsertNode(nodeCaptor.capture());
        assertTrue(nodeCaptor.getAllValues().stream().anyMatch(claim ->
                NodeType.ExternalSystem.name().equals(claim.getNodeType())));
        assertTrue(nodeCaptor.getAllValues().stream().anyMatch(claim ->
                NodeType.ApiEndpoint.name().equals(claim.getNodeType())
                        && "external:https://pay.example.com/api/pay".equals(claim.getNodeKey())));

        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(GraphEdgeClaim.class);
        verify(writer, atLeast(1)).upsertEdge(edgeCaptor.capture());
        assertTrue(edgeCaptor.getAllValues().stream().anyMatch(claim ->
                EdgeType.CALLS_EXTERNAL.name().equals(claim.getEdgeType())
                        && claim.getEdgeKey().contains("com.demo.PaymentClient.pay(PaymentRequest)->calls_external->")));
        assertTrue(edgeCaptor.getAllValues().stream().anyMatch(claim ->
                EdgeType.CALLS_EXTERNAL.name().equals(claim.getEdgeType())
                        && claim.getEdgeKey().contains("->calls_external->external:https://pay.example.com/api/pay")));
    }

    @Test
    void buildTestCaseGraph_createsAssertionNodesAndContainsEdges() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);
        when(neo4jGraphDao.findNode(any(), any(), any(), any())).thenReturn(Optional.empty());

        TestCaseExtractor.AssertionFact assertion = new TestCaseExtractor.AssertionFact();
        assertion.setAssertionType("assertEquals");
        assertion.setExpectedValue("expected");
        assertion.setStartLine(12);
        assertion.setEndLine(12);

        TestCaseExtractor.TestCaseFact testCase = new TestCaseExtractor.TestCaseFact();
        testCase.setClassName("com.demo.OrderServiceTest");
        testCase.setMethodName("createsOrder");
        testCase.setAnnotationType("Test");
        testCase.setSourcePath("/tmp/OrderServiceTest.java");
        testCase.setAssertions(List.of(assertion));

        graphBuilder.buildTestCaseGraph("project-1", "v1", List.of(testCase));

        var nodeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeNodesBatch(nodeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphNode> nodes = nodeCaptor.getValue();
        assertTrue(nodes.stream().anyMatch(node -> NodeType.TestCase.name().equals(node.getNodeType())));
        assertTrue(nodes.stream().anyMatch(node ->
                NodeType.Assertion.name().equals(node.getNodeType())
                        && node.getNodeKey().contains("assertEquals#12")));

        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesBatch(edgeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphEdge> edges = edgeCaptor.getValue();
        assertTrue(edges.stream().anyMatch(edge ->
                EdgeType.CONTAINS.name().equals(edge.getEdgeType())
                        && edge.getEdgeKey().contains("->contains->assertion:")));
    }

    /**
     * SubTask 5.3：验证方法级 VERIFIED_BY 边构建。
     * <p>当 TestCaseFact 含 invokedMethodCalls 时，应建立 Method--VERIFIED_BY-->TestCase 边。
     * 同时保留类级 VERIFIED_BY 作为回退。本测试断言两种级别的 VERIFIED_BY 边均存在。</p>
     */
    @Test
    void testMethodLevelVerifiedBy() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);

        // findNode 对被测类返回空（触发新建 Service 节点）
        when(neo4jGraphDao.findNode(any(), any(), any(), any())).thenReturn(Optional.empty());

        // 模拟已存在的 Method 节点（buildMethodLookupForClass 反查 Method 节点）
        GraphNode methodNode = new GraphNode();
        methodNode.setId("method-1");
        methodNode.setNodeType(NodeType.Method.name());
        methodNode.setNodeKey("com.demo.OrderService.createOrder");
        methodNode.setNodeName("createOrder");

        when(neo4jGraphDao.queryNodes(any(), any(), eq(NodeType.Method.name()),
                any(), any(), any(), anyInt()))
                .thenReturn(List.of(methodNode));

        // 构建含被测方法调用的 TestCaseFact
        TestCaseExtractor.InvokedMethodCall invokedCall = new TestCaseExtractor.InvokedMethodCall();
        invokedCall.setMethodName("createOrder");

        TestCaseExtractor.TestCaseFact testCase = new TestCaseExtractor.TestCaseFact();
        testCase.setClassName("com.demo.OrderServiceTest");
        testCase.setMethodName("createsOrder");
        testCase.setAnnotationType("Test");
        testCase.setSourcePath("/tmp/OrderServiceTest.java");
        testCase.setInvokedMethodCalls(List.of(invokedCall));

        graphBuilder.buildTestCaseGraph("project-1", "v1", List.of(testCase));

        // 捕获批量写入的边
        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesBatch(edgeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphEdge> edges = edgeCaptor.getValue();

        // 验证类级 VERIFIED_BY 边存在（回退）
        assertTrue(edges.stream().anyMatch(edge ->
                EdgeType.VERIFIED_BY.name().equals(edge.getEdgeType())
                        && edge.getEdgeKey().contains("com.demo.OrderService->verified_by->")),
                "应存在类级 VERIFIED_BY 边作为回退");

        // 验证方法级 VERIFIED_BY 边存在
        assertTrue(edges.stream().anyMatch(edge ->
                EdgeType.VERIFIED_BY.name().equals(edge.getEdgeType())
                        && edge.getEdgeKey().contains("com.demo.OrderService.createOrder->verified_by->")),
                "应存在方法级 VERIFIED_BY 边（Method --VERIFIED_BY--> TestCase）");

        // 验证方法级边来源为 Method 节点
        assertTrue(edges.stream().anyMatch(edge ->
                EdgeType.VERIFIED_BY.name().equals(edge.getEdgeType())
                        && "method-1".equals(edge.getFromNodeId())),
                "方法级 VERIFIED_BY 边来源应为 Method 节点");
    }

    // ==================== Package 包图谱测试（架构依赖链路） ====================

    /**
     * 验证 Class --BELONGS_TO--> Package 边构建。
     * <p>buildPackageGraph 反查已存在的 Class 节点，为每个类建立到所属 Package 的 BELONGS_TO 边。
     */
    @Test
    void testBelongsToEdgeBuilt() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);

        // 模拟已存在的 Service 节点（buildPackageGraph 反查 Class 节点建 BELONGS_TO 边，不创建）
        GraphNode classNode = new GraphNode();
        classNode.setId("Service:com.demo.OrderService");
        classNode.setNodeType("Service");
        classNode.setNodeKey("com.demo.OrderService");
        lenient().when(neo4jGraphDao.findNode(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(neo4jGraphDao.findNode(any(), any(), eq(NodeType.Service.name()), eq("com.demo.OrderService")))
                .thenReturn(Optional.of(classNode));

        when(writer.upsertNode(any(GraphNodeClaim.class))).thenAnswer(invocation -> {
            GraphNodeClaim claim = invocation.getArgument(0);
            GraphNode node = new GraphNode();
            node.setId(claim.getNodeType() + ":" + claim.getNodeKey());
            node.setNodeType(claim.getNodeType());
            node.setNodeKey(claim.getNodeKey());
            return node;
        });
        when(writer.upsertEdge(any(GraphEdgeClaim.class))).thenAnswer(invocation -> {
            GraphEdgeClaim claim = invocation.getArgument(0);
            GraphEdge edge = new GraphEdge();
            edge.setId(claim.getEdgeType() + ":" + claim.getEdgeKey());
            edge.setEdgeType(claim.getEdgeType());
            edge.setEdgeKey(claim.getEdgeKey());
            return edge;
        });

        JavaStructureExtractor.JavaClassInfo classInfo = new JavaStructureExtractor.JavaClassInfo(
                "OrderService", "com.demo", "com.demo.OrderService", "CLASS",
                "/tmp/OrderService.java", 5, 15,
                List.of(), List.of(), List.of(), List.of()
        );

        graphBuilder.buildPackageGraph("project-1", "v1", List.of(classInfo));

        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(GraphEdgeClaim.class);
        verify(writer, atLeastOnce()).upsertEdge(edgeCaptor.capture());
        // 验证存在 BELONGS_TO 边
        assertTrue(edgeCaptor.getAllValues().stream().anyMatch(edge ->
                EdgeType.BELONGS_TO.name().equals(edge.getEdgeType())),
                "应构建 Class --BELONGS_TO--> Package 边");
    }

    /**
     * 验证 Package --DEPENDS_ON--> Package 边构建。
     * <p>import 语句中引入的业务包（非框架包）会生成 Package 间 DEPENDS_ON 边。
     */
    @Test
    void testDependsOnEdgeBuilt() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);

        GraphNode classNode = new GraphNode();
        classNode.setId("Service:com.demo.OrderService");
        classNode.setNodeType("Service");
        classNode.setNodeKey("com.demo.OrderService");
        lenient().when(neo4jGraphDao.findNode(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(neo4jGraphDao.findNode(any(), any(), eq(NodeType.Service.name()), eq("com.demo.OrderService")))
                .thenReturn(Optional.of(classNode));

        when(writer.upsertNode(any(GraphNodeClaim.class))).thenAnswer(invocation -> {
            GraphNodeClaim claim = invocation.getArgument(0);
            GraphNode node = new GraphNode();
            node.setId(claim.getNodeType() + ":" + claim.getNodeKey());
            node.setNodeType(claim.getNodeType());
            node.setNodeKey(claim.getNodeKey());
            return node;
        });
        when(writer.upsertEdge(any(GraphEdgeClaim.class))).thenAnswer(invocation -> {
            GraphEdgeClaim claim = invocation.getArgument(0);
            GraphEdge edge = new GraphEdge();
            edge.setId(claim.getEdgeType() + ":" + claim.getEdgeKey());
            edge.setEdgeType(claim.getEdgeType());
            edge.setEdgeKey(claim.getEdgeKey());
            return edge;
        });

        // imports 包含一个业务包 com.demo.repository（目标类 com.demo.repository.OrderRepository）
        JavaStructureExtractor.JavaClassInfo classInfo = new JavaStructureExtractor.JavaClassInfo(
                "OrderService", "com.demo", "com.demo.OrderService", "CLASS",
                "/tmp/OrderService.java", 5, 15,
                List.of(), List.of(), List.of(),
                List.of("com.demo.repository.OrderRepository")
        );

        graphBuilder.buildPackageGraph("project-1", "v1", List.of(classInfo));

        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(GraphEdgeClaim.class);
        verify(writer, atLeastOnce()).upsertEdge(edgeCaptor.capture());
        // 验证存在 DEPENDS_ON 边
        assertTrue(edgeCaptor.getAllValues().stream().anyMatch(edge ->
                EdgeType.DEPENDS_ON.name().equals(edge.getEdgeType())),
                "应构建 Package --DEPENDS_ON--> Package 边");
    }

    /**
     * 验证 DEPENDS_ON 边排除 java.* / javax.* / org.springframework.* 等框架包，
     * 不会为框架包引入创建 DEPENDS_ON 边。
     */
    @Test
    void testDependsOnExcludesFrameworkPackages() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);

        GraphNode classNode = new GraphNode();
        classNode.setId("Service:com.demo.OrderService");
        classNode.setNodeType("Service");
        classNode.setNodeKey("com.demo.OrderService");
        lenient().when(neo4jGraphDao.findNode(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(neo4jGraphDao.findNode(any(), any(), eq(NodeType.Service.name()), eq("com.demo.OrderService")))
                .thenReturn(Optional.of(classNode));

        when(writer.upsertNode(any(GraphNodeClaim.class))).thenAnswer(invocation -> {
            GraphNodeClaim claim = invocation.getArgument(0);
            GraphNode node = new GraphNode();
            node.setId(claim.getNodeType() + ":" + claim.getNodeKey());
            node.setNodeType(claim.getNodeType());
            node.setNodeKey(claim.getNodeKey());
            return node;
        });
        when(writer.upsertEdge(any(GraphEdgeClaim.class))).thenAnswer(invocation -> {
            GraphEdgeClaim claim = invocation.getArgument(0);
            GraphEdge edge = new GraphEdge();
            edge.setId(claim.getEdgeType() + ":" + claim.getEdgeKey());
            edge.setEdgeType(claim.getEdgeType());
            edge.setEdgeKey(claim.getEdgeKey());
            return edge;
        });

        // imports 全部为框架包：java.*/javax.*/org.springframework.*/lombok.*/org.slf4j.* 等
        JavaStructureExtractor.JavaClassInfo classInfo = new JavaStructureExtractor.JavaClassInfo(
                "OrderService", "com.demo", "com.demo.OrderService", "CLASS",
                "/tmp/OrderService.java", 5, 15,
                List.of(), List.of(), List.of(),
                List.of(
                        "java.util.List",
                        "java.util.Map",
                        "javax.servlet.http.HttpServletRequest",
                        "org.springframework.stereotype.Service",
                        "org.springframework.web.bind.annotation.RequestMapping",
                        "lombok.extern.slf4j.Slf4j",
                        "org.slf4j.Logger",
                        "com.fasterxml.jackson.annotation.JsonProperty",
                        "org.apache.commons.lang3.StringUtils"
                )
        );

        graphBuilder.buildPackageGraph("project-1", "v1", List.of(classInfo));

        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(GraphEdgeClaim.class);
        verify(writer, atLeastOnce()).upsertEdge(edgeCaptor.capture());
        // 不应出现任何 DEPENDS_ON 边（所有 import 均为框架包）
        assertFalse(edgeCaptor.getAllValues().stream().anyMatch(edge ->
                EdgeType.DEPENDS_ON.name().equals(edge.getEdgeType())),
                "框架包（java.*/javax.*/org.springframework.* 等）不应产生 DEPENDS_ON 边");

        // 直观验证 isFrameworkPackage 对各框架包前缀的判定（逻辑已迁移至 PackageExtractor）
        assertTrue(io.github.legacygraph.extractors.PackageExtractor.isFrameworkPackage("java.util"));
        assertTrue(io.github.legacygraph.extractors.PackageExtractor.isFrameworkPackage("javax.servlet"));
        assertTrue(io.github.legacygraph.extractors.PackageExtractor.isFrameworkPackage("org.springframework.web"));
        assertTrue(io.github.legacygraph.extractors.PackageExtractor.isFrameworkPackage("lombok"));
        assertTrue(io.github.legacygraph.extractors.PackageExtractor.isFrameworkPackage("org.slf4j"));
        assertTrue(io.github.legacygraph.extractors.PackageExtractor.isFrameworkPackage("com.fasterxml.jackson.databind"));
        assertTrue(io.github.legacygraph.extractors.PackageExtractor.isFrameworkPackage("org.apache.commons.lang3"));
        // 业务包不应被判定为框架包
        assertFalse(io.github.legacygraph.extractors.PackageExtractor.isFrameworkPackage("com.demo.repository"));
        assertFalse(io.github.legacygraph.extractors.PackageExtractor.isFrameworkPackage("com.example.service"));
    }

    /**
     * 验证 INSERT INTO b SELECT FROM a 构建 table_a --DATA_FLOW--> table_b 边。
     */
    @Test
    void testInsertSelectDataFlow() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);
        when(writer.upsertNode(any(GraphNodeClaim.class))).thenAnswer(invocation -> {
            GraphNodeClaim claim = invocation.getArgument(0);
            GraphNode node = new GraphNode();
            node.setId(claim.getNodeType() + ":" + claim.getNodeKey());
            node.setProjectId(claim.getProjectId());
            node.setVersionId(claim.getVersionId());
            node.setNodeType(claim.getNodeType());
            node.setNodeKey(claim.getNodeKey());
            node.setNodeName(claim.getNodeName());
            node.setConfidence(claim.getConfidence());
            node.setStatus(claim.getStatus());
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
            edge.setStatus(claim.getStatus());
            return edge;
        });

        MyBatisXmlExtractor.SqlStatement stmt = new MyBatisXmlExtractor.SqlStatement();
        stmt.setId("insertFromSelect");
        stmt.setType("insert");
        stmt.setSql("INSERT INTO order_archive SELECT * FROM orders WHERE created_at < '2024-01-01'");
        MapperSqlFact mapper = new MapperSqlFact();
        mapper.setNamespace("com.demo.OrderMapper");
        mapper.setMapperInterface("OrderMapper");
        mapper.setSourcePath("/tmp/OrderMapper.xml");
        mapper.setStatements(List.of(stmt));

        graphBuilder.buildMapperSqlGraph("project-1", "v1", mapper);

        @SuppressWarnings("unchecked")
        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao, atLeastOnce()).mergeEdgesByKeyBatch(eq("project-1"), eq("v1"), edgeCaptor.capture());
        var allEdges = edgeCaptor.getAllValues().stream()
                .map(list -> (List<Neo4jWriteRepository.BatchEdgeByKeyUpsert>) list)
                .flatMap(List::stream)
                .toList();
        // 验证存在 DATA_FLOW 边：orders ->data_flow-> order_archive
        assertTrue(allEdges.stream().anyMatch(edge ->
                EdgeType.DATA_FLOW.name().equals(edge.edgeType())
                        && edge.edgeKey().contains("orders->data_flow->order_archive")),
                "INSERT INTO ... SELECT FROM 应构建源表到目标表的 DATA_FLOW 边");
        // 验证存在 READS 边（SqlStatement -> orders）
        assertTrue(allEdges.stream().anyMatch(edge ->
                EdgeType.READS.name().equals(edge.edgeType())
                        && edge.edgeKey().contains("->reads->orders")),
                "应存在 SqlStatement --READS--> orders 边");
        // 验证存在 WRITES 边（SqlStatement -> order_archive）
        assertTrue(allEdges.stream().anyMatch(edge ->
                EdgeType.WRITES.name().equals(edge.edgeType())
                        && edge.edgeKey().contains("->writes->order_archive")),
                "应存在 SqlStatement --WRITES--> order_archive 边");
    }

    /**
     * 验证 UPDATE 语句构建写入目标表的 WRITES 边，但因无源表不构建 DATA_FLOW 边。
     */
    @Test
    void testUpdateDataFlow() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);
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
        when(writer.upsertEdge(any(GraphEdgeClaim.class))).thenAnswer(invocation -> {
            GraphEdgeClaim claim = invocation.getArgument(0);
            GraphEdge edge = new GraphEdge();
            edge.setId(claim.getEdgeType() + ":" + claim.getEdgeKey());
            edge.setFromNodeId(claim.getFromNodeId());
            edge.setToNodeId(claim.getToNodeId());
            edge.setEdgeType(claim.getEdgeType());
            edge.setEdgeKey(claim.getEdgeKey());
            return edge;
        });

        MyBatisXmlExtractor.SqlStatement stmt = new MyBatisXmlExtractor.SqlStatement();
        stmt.setId("updateStatus");
        stmt.setType("update");
        stmt.setSql("UPDATE orders SET status = 'SHIPPED' WHERE id = ?");
        MapperSqlFact mapper = new MapperSqlFact();
        mapper.setNamespace("com.demo.OrderMapper");
        mapper.setMapperInterface("OrderMapper");
        mapper.setSourcePath("/tmp/OrderMapper.xml");
        mapper.setStatements(List.of(stmt));

        graphBuilder.buildMapperSqlGraph("project-1", "v1", mapper);

        @SuppressWarnings("unchecked")
        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao, atLeastOnce()).mergeEdgesByKeyBatch(eq("project-1"), eq("v1"), edgeCaptor.capture());
        var allEdges = edgeCaptor.getAllValues().stream()
                .map(list -> (List<Neo4jWriteRepository.BatchEdgeByKeyUpsert>) list)
                .flatMap(List::stream)
                .toList();
        // 验证存在 WRITES 边（SqlStatement -> orders）
        assertTrue(allEdges.stream().anyMatch(edge ->
                EdgeType.WRITES.name().equals(edge.edgeType())
                        && edge.edgeKey().contains("->writes->orders")),
                "UPDATE 应构建写入目标表的 WRITES 边");
        // 验证不存在 DATA_FLOW 边（UPDATE 无源表）
        assertFalse(allEdges.stream().anyMatch(edge ->
                EdgeType.DATA_FLOW.name().equals(edge.edgeType())),
                "简单 UPDATE 无源表，不应构建 DATA_FLOW 边");
    }

    /**
     * 验证 INSERT INTO b VALUES (...) 无源表时不构建 DATA_FLOW 边。
     */
    @Test
    void testNoSourceTableDataFlow() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);
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
        when(writer.upsertEdge(any(GraphEdgeClaim.class))).thenAnswer(invocation -> {
            GraphEdgeClaim claim = invocation.getArgument(0);
            GraphEdge edge = new GraphEdge();
            edge.setId(claim.getEdgeType() + ":" + claim.getEdgeKey());
            edge.setFromNodeId(claim.getFromNodeId());
            edge.setToNodeId(claim.getToNodeId());
            edge.setEdgeType(claim.getEdgeType());
            edge.setEdgeKey(claim.getEdgeKey());
            return edge;
        });

        MyBatisXmlExtractor.SqlStatement stmt = new MyBatisXmlExtractor.SqlStatement();
        stmt.setId("insertValues");
        stmt.setType("insert");
        stmt.setSql("INSERT INTO orders (id, status) VALUES (?, ?)");
        MapperSqlFact mapper = new MapperSqlFact();
        mapper.setNamespace("com.demo.OrderMapper");
        mapper.setMapperInterface("OrderMapper");
        mapper.setSourcePath("/tmp/OrderMapper.xml");
        mapper.setStatements(List.of(stmt));

        graphBuilder.buildMapperSqlGraph("project-1", "v1", mapper);

        @SuppressWarnings("unchecked")
        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao, atLeastOnce()).mergeEdgesByKeyBatch(eq("project-1"), eq("v1"), edgeCaptor.capture());
        var allEdges = edgeCaptor.getAllValues().stream()
                .map(list -> (List<Neo4jWriteRepository.BatchEdgeByKeyUpsert>) list)
                .flatMap(List::stream)
                .toList();
        // 验证存在 WRITES 边（SqlStatement -> orders）
        assertTrue(allEdges.stream().anyMatch(edge ->
                EdgeType.WRITES.name().equals(edge.edgeType())
                        && edge.edgeKey().contains("->writes->orders")),
                "INSERT INTO ... VALUES 应构建写入目标表的 WRITES 边");
        // 验证不存在 DATA_FLOW 边（无源表）
        assertFalse(allEdges.stream().anyMatch(edge ->
                EdgeType.DATA_FLOW.name().equals(edge.edgeType())),
                "INSERT INTO ... VALUES 无源表，不应构建 DATA_FLOW 边");
    }

    // ==================== RBAC 权限链路 GRANTS/ASSIGNED_TO 边测试 ====================

    /**
     * SubTask 2.5：验证 Role --GRANTS--> Permission 边构建。
     * <p>buildRbacRoleGraph 从 Role 节点 properties.permissions 读取关联权限，
     * 为每个 Permission 创建节点并建立 GRANTS 边。edgeKey 格式：{@code roleKey->grants->permKey}。</p>
     */
    @Test
    void testGrantsEdgeBuilt() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);

        // Role 节点：nodeKey=role:admin，permissions=[user:read]
        NodeExtractionResult role = NodeExtractionResult.builder()
                .nodeType("Role")
                .nodeKey("role:admin")
                .displayName("admin")
                .description("RBAC 角色: admin")
                .sourcePath("/tmp/AdminController.java")
                .sourceType("CODE_AST")
                .confidence(0.95)
                .properties(Map.of(
                        "role", "admin",
                        "permissions", List.of("user:read")
                ))
                .build();

        graphBuilder.buildRbacRoleGraph("project-1", "v1", List.of(role));

        // 捕获批量写入的边
        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesBatch(edgeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphEdge> edges = edgeCaptor.getValue();

        // 验证存在 GRANTS 边：role:admin ->grants-> user:read
        assertTrue(edges.stream().anyMatch(edge ->
                EdgeType.GRANTS.name().equals(edge.getEdgeType())
                        && "role:admin->grants->user:read".equals(edge.getEdgeKey())),
                "应构建 Role --GRANTS--> Permission 边（edgeKey=role:admin->grants->user:read）");

        // 捕获批量写入的节点
        var nodeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeNodesBatch(nodeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphNode> nodes = nodeCaptor.getValue();

        // 验证存在 Permission 节点
        assertTrue(nodes.stream().anyMatch(node ->
                NodeType.Permission.name().equals(node.getNodeType())
                        && "user:read".equals(node.getNodeKey())),
                "应创建 Permission 节点（nodeKey=user:read）");
        // 验证存在 Role 节点
        assertTrue(nodes.stream().anyMatch(node ->
                NodeType.Role.name().equals(node.getNodeType())
                        && "role:admin".equals(node.getNodeKey())),
                "应创建 Role 节点（nodeKey=role:admin）");
    }

    /**
     * SubTask 2.5：验证 Role --ASSIGNED_TO--> User 边构建。
     * <p>buildRbacUserGraph 从 UserRoleAssignment 列表构建 User 节点和 ASSIGNED_TO 边。
     * edgeKey 格式：{@code roleKey->assigned_to->userKey}。</p>
     */
    @Test
    void testAssignedToEdgeBuilt() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);

        // 模拟已存在的 Role 节点（buildRbacUserGraph 反查 Role 节点建 ASSIGNED_TO 边）
        GraphNode roleNode = new GraphNode();
        roleNode.setId("role-admin-id");
        roleNode.setNodeType(NodeType.Role.name());
        roleNode.setNodeKey("role:admin");
        roleNode.setNodeName("admin");
        when(neo4jGraphDao.findNode(any(), any(), eq(NodeType.Role.name()), eq("role:admin")))
                .thenReturn(Optional.of(roleNode));

        // User-Role 关联：userName=bob, roleName=admin
        UserRoleAssignment assignment = UserRoleAssignment.builder()
                .userName("bob")
                .roleName("admin")
                .sourcePath("sys_user_role")
                .build();

        graphBuilder.buildRbacUserGraph("project-1", "v1", List.of(assignment));

        // 捕获批量写入的边
        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesBatch(edgeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphEdge> edges = edgeCaptor.getValue();

        // 验证存在 ASSIGNED_TO 边：role:admin ->assigned_to-> user:bob
        assertTrue(edges.stream().anyMatch(edge ->
                EdgeType.ASSIGNED_TO.name().equals(edge.getEdgeType())
                        && "role:admin->assigned_to->user:bob".equals(edge.getEdgeKey())),
                "应构建 Role --ASSIGNED_TO--> User 边（edgeKey=role:admin->assigned_to->user:bob）");
        // 验证边来源为已存在的 Role 节点
        assertTrue(edges.stream().anyMatch(edge ->
                EdgeType.ASSIGNED_TO.name().equals(edge.getEdgeType())
                        && "role-admin-id".equals(edge.getFromNodeId())),
                "ASSIGNED_TO 边来源应为已存在的 Role 节点");

        // 捕获批量写入的节点
        var nodeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeNodesBatch(nodeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphNode> nodes = nodeCaptor.getValue();

        // 验证存在 User 节点
        assertTrue(nodes.stream().anyMatch(node ->
                NodeType.User.name().equals(node.getNodeType())
                        && "user:bob".equals(node.getNodeKey())),
                "应创建 User 节点（nodeKey=user:bob）");
    }

    /**
     * SubTask 2.5：验证 Permission nodeKey 小写化。
     * <p>无论权限标识原值是大写（USER:READ）还是混合大小写（Order:Create），
     * nodeKey 都应统一小写化（user:read / order:create），确保前后端 Permission 节点 MERGE 合并。</p>
     */
    @Test
    void testPermissionNodeKeyLowercase() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);

        // Role 节点：permissions=[USER:READ, Order:Create]（大写和混合大小写）
        NodeExtractionResult role = NodeExtractionResult.builder()
                .nodeType("Role")
                .nodeKey("role:admin")
                .displayName("admin")
                .description("RBAC 角色: admin")
                .sourcePath("/tmp/AdminController.java")
                .sourceType("CODE_AST")
                .confidence(0.95)
                .properties(Map.of(
                        "role", "admin",
                        "permissions", List.of("USER:READ", "Order:Create")
                ))
                .build();

        graphBuilder.buildRbacRoleGraph("project-1", "v1", List.of(role));

        // 捕获批量写入的节点
        var nodeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeNodesBatch(nodeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphNode> nodes = nodeCaptor.getValue();

        // 验证 USER:READ → user:read 小写化
        assertTrue(nodes.stream().anyMatch(node ->
                NodeType.Permission.name().equals(node.getNodeType())
                        && "user:read".equals(node.getNodeKey())),
                "USER:READ 应小写化为 user:read");
        // 验证 Order:Create → order:create 小写化
        assertTrue(nodes.stream().anyMatch(node ->
                NodeType.Permission.name().equals(node.getNodeType())
                        && "order:create".equals(node.getNodeKey())),
                "Order:Create 应小写化为 order:create");

        // 捕获批量写入的边，验证 edgeKey 中的 permKey 也小写化
        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesBatch(edgeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphEdge> edges = edgeCaptor.getValue();

        assertTrue(edges.stream().anyMatch(edge ->
                EdgeType.GRANTS.name().equals(edge.getEdgeType())
                        && "role:admin->grants->user:read".equals(edge.getEdgeKey())),
                "GRANTS 边 edgeKey 中的 permKey 应小写化（user:read）");
        assertTrue(edges.stream().anyMatch(edge ->
                EdgeType.GRANTS.name().equals(edge.getEdgeType())
                        && "role:admin->grants->order:create".equals(edge.getEdgeKey())),
                "GRANTS 边 edgeKey 中的 permKey 应小写化（order:create）");
    }
}
