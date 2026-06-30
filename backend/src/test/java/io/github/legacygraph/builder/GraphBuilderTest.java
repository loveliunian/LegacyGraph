package io.github.legacygraph.builder;

import io.github.legacygraph.agent.SqlAdvisorAgent;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.SqlAdvisorResult;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.extractors.MyBatisXmlExtractor;
import io.github.legacygraph.model.MapperSqlFact;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
    @Mock
    private SqlAdvisorAgent sqlAdvisorAgent;
    @Mock
    private ReviewRecordRepository reviewRecordRepository;

    private GraphBuilder graphBuilder;

    @Test
    void testConstruction() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer, sqlAdvisorAgent, reviewRecordRepository);
        assertNotNull(graphBuilder);
    }

    @Test
    void testBuildMapperSqlGraph_CreatesReviewRecordForSqlAdvisorIssues() {
        graphBuilder = new GraphBuilder(neo4jGraphDao, writer, sqlAdvisorAgent, reviewRecordRepository);
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
        when(reviewRecordRepository.selectCount(any())).thenReturn(0L);

        SqlAdvisorResult.SqlIssue issue = new SqlAdvisorResult.SqlIssue();
        issue.setIssueType("SELECT_STAR");
        issue.setSeverity("HIGH");
        issue.setDescription("SELECT * 会放大 IO");
        issue.setSuggestion("只查询需要字段");
        SqlAdvisorResult result = new SqlAdvisorResult();
        result.setSummary("存在高风险 SQL");
        result.setOverallRisk("HIGH");
        result.setIssues(List.of(issue));
        when(sqlAdvisorAgent.analyze(eq("project-1"), eq("com.demo.OrderMapper.list"), any(), any()))
                .thenReturn(result);

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

        ArgumentCaptor<ReviewRecord> reviewCaptor = ArgumentCaptor.forClass(ReviewRecord.class);
        verify(reviewRecordRepository).insert(reviewCaptor.capture());
        ReviewRecord review = reviewCaptor.getValue();
        assertEquals("PENDING", review.getStatus());
        assertEquals("SqlStatement", review.getTargetType());
        assertTrue(review.getComment().contains("存在高风险 SQL"));
        assertTrue(review.getComment().contains("SELECT_STAR"));
    }
}
