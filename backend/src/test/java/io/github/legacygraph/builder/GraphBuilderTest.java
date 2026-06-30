package io.github.legacygraph.builder;

import io.github.legacygraph.agent.SqlAdvisorAgent;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.SqlAdvisorResult;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphBuilderTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private EvidenceRepository evidenceRepository;
    @Mock
    private NodeEvidenceRepository nodeEvidenceRepository;
    @Mock
    private EdgeEvidenceRepository edgeEvidenceRepository;
    @Mock
    private SqlAdvisorAgent sqlAdvisorAgent;
    @Mock
    private ReviewRecordRepository reviewRecordRepository;

    private GraphBuilder graphBuilder;

    @Test
    void testConstruction() {
        graphBuilder = new GraphBuilder(neo4jGraphDao,
                evidenceRepository, nodeEvidenceRepository, edgeEvidenceRepository,
                sqlAdvisorAgent, reviewRecordRepository);
        assertNotNull(graphBuilder);
    }

    @Test
    void testBuildMapperSqlGraph_CreatesReviewRecordForSqlAdvisorIssues() {
        graphBuilder = new GraphBuilder(neo4jGraphDao,
                evidenceRepository, nodeEvidenceRepository, edgeEvidenceRepository,
                sqlAdvisorAgent, reviewRecordRepository);
        when(neo4jGraphDao.findNode(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(neo4jGraphDao.createNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeEvidenceRepository.selectList(any())).thenReturn(List.of());
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
