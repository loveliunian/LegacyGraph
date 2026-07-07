package io.github.legacygraph.builder;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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
        verify(writer, atLeast(2)).upsertNode(any(GraphNodeClaim.class));
        verify(writer, atLeast(1)).upsertEdge(any(GraphEdgeClaim.class));
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
                List.of(createMethod, cancelMethod)
        );

        graphBuilder.buildJavaStructureGraph("project-1", "v1", List.of(classInfo));

        var nodeCaptor = org.mockito.ArgumentCaptor.forClass(GraphNodeClaim.class);
        verify(writer, times(3)).upsertNode(nodeCaptor.capture());
        List<GraphNodeClaim> nodes = nodeCaptor.getAllValues();
        assertTrue(nodes.stream().anyMatch(n ->
                NodeType.Service.name().equals(n.getNodeType())
                        && "com.demo.OrderService".equals(n.getNodeKey())));
        assertEquals(2, nodes.stream().filter(n -> NodeType.Method.name().equals(n.getNodeType())).count());

        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(GraphEdgeClaim.class);
        verify(writer, times(2)).upsertEdge(edgeCaptor.capture());
        assertTrue(edgeCaptor.getAllValues().stream()
                .allMatch(e -> EdgeType.CONTAINS.name().equals(e.getEdgeType())));
    }

    @Test
    void buildDatabaseGraph_putsColumnMetadataIntoNodeProperties() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);
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

        var nodeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeNodesBatch(nodeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphNode> nodes = nodeCaptor.getValue();
        GraphNode statusColumn = nodes.stream()
                .filter(node -> "public.orders.status".equals(node.getNodeKey()))
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

        ScheduledJobExtractor.ScheduledJobFact job = new ScheduledJobExtractor.ScheduledJobFact();
        job.setClassName("com.demo.OrderJob");
        job.setMethodName("sync");
        job.setMethodSignature("sync()");
        job.setAnnotationType("Scheduled");
        job.setCronExpression("0 0 * * * ?");
        job.setSourcePath("/tmp/OrderJob.java");

        graphBuilder.buildScheduledJobGraph("project-1", "v1", List.of(job));

        var nodeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeNodesBatch(nodeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphNode> nodes = nodeCaptor.getValue();
        assertTrue(nodes.stream().anyMatch(node ->
                NodeType.Method.name().equals(node.getNodeType())
                        && "com.demo.OrderJob.sync()".equals(node.getNodeKey())));

        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesBatch(edgeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphEdge> edges = edgeCaptor.getValue();
        assertTrue(edges.stream().anyMatch(edge ->
                EdgeType.HANDLED_BY.name().equals(edge.getEdgeType())
                        && "job:com.demo.OrderJob.sync->handled_by->com.demo.OrderJob.sync()".equals(edge.getEdgeKey())));
    }

    @Test
    void buildMQGraph_createsConsumerTopicAndMethodEdges() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);

        MQExtractor.MQConsumerFact consumer = new MQExtractor.MQConsumerFact();
        consumer.setClassName("com.demo.OrderListener");
        consumer.setMethodName("handle");
        consumer.setMethodSignature("handle(String)");
        consumer.setAnnotationType("KafkaListener");
        consumer.setTopic("order.created");
        consumer.setConsumerGroup("order-service");
        consumer.setSourcePath("/tmp/OrderListener.java");

        graphBuilder.buildMQGraph("project-1", "v1", List.of(consumer));

        var nodeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeNodesBatch(nodeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphNode> nodes = nodeCaptor.getValue();
        assertTrue(nodes.stream().anyMatch(node -> NodeType.MQConsumer.name().equals(node.getNodeType())));
        assertTrue(nodes.stream().anyMatch(node ->
                NodeType.MQTopic.name().equals(node.getNodeType())
                        && "mq-topic:order.created".equals(node.getNodeKey())));
        assertTrue(nodes.stream().anyMatch(node ->
                NodeType.Method.name().equals(node.getNodeType())
                        && "com.demo.OrderListener.handle(String)".equals(node.getNodeKey())));

        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesBatch(edgeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphEdge> edges = edgeCaptor.getValue();
        assertTrue(edges.stream().anyMatch(edge -> EdgeType.CONSUMES.name().equals(edge.getEdgeType())));
        assertTrue(edges.stream().anyMatch(edge -> EdgeType.HANDLED_BY.name().equals(edge.getEdgeType())));
        assertTrue(edges.stream().anyMatch(edge -> EdgeType.TRIGGERS.name().equals(edge.getEdgeType())));
    }

    @Test
    void buildExternalSystemGraph_createsExternalEndpointAndMethodEdges() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer);

        ExternalSystemExtractor.ExternalCallFact call = new ExternalSystemExtractor.ExternalCallFact();
        call.setClassName("com.demo.PaymentClient");
        call.setMethodName("pay");
        call.setMethodSignature("pay(PaymentRequest)");
        call.setClientType("RestTemplate");
        call.setBaseUrl("https://pay.example.com/api/pay");
        call.setSourcePath("/tmp/PaymentClient.java");

        graphBuilder.buildExternalSystemGraph("project-1", "v1", List.of(call));

        var nodeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeNodesBatch(nodeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphNode> nodes = nodeCaptor.getValue();
        assertTrue(nodes.stream().anyMatch(node -> NodeType.ExternalSystem.name().equals(node.getNodeType())));
        assertTrue(nodes.stream().anyMatch(node ->
                NodeType.ApiEndpoint.name().equals(node.getNodeType())
                        && "external:https://pay.example.com/api/pay".equals(node.getNodeKey())));

        var edgeCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(neo4jGraphDao).mergeEdgesBatch(edgeCaptor.capture());
        @SuppressWarnings("unchecked")
        List<GraphEdge> edges = edgeCaptor.getValue();
        assertTrue(edges.stream().anyMatch(edge ->
                EdgeType.CALLS_EXTERNAL.name().equals(edge.getEdgeType())
                        && edge.getEdgeKey().contains("com.demo.PaymentClient.pay(PaymentRequest)->calls_external->")));
        assertTrue(edges.stream().anyMatch(edge ->
                EdgeType.CALLS_EXTERNAL.name().equals(edge.getEdgeType())
                        && edge.getEdgeKey().contains("->calls_external->external:https://pay.example.com/api/pay")));
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
}
