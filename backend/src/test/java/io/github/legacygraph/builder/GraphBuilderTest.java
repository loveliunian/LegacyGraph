package io.github.legacygraph.builder;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.extractors.JavaStructureExtractor;
import io.github.legacygraph.extractors.MyBatisXmlExtractor;
import io.github.legacygraph.model.MapperSqlFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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
}
