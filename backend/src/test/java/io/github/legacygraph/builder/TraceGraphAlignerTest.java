package io.github.legacygraph.builder;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceGraphAlignerTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @Mock
    private EvidenceGraphWriter writer;

    @Test
    void alignPersistsStaticOnlyEdgeStatus() {
        GraphEdge edge = new GraphEdge();
        edge.setId("edge-1");
        edge.setProjectId("project-1");
        edge.setVersionId("v1");
        edge.setEdgeType("CALLS");
        when(neo4jGraphDao.queryEdges("project-1", "v1", null, null, 100))
                .thenReturn(List.of(edge));

        TraceGraphAligner aligner = new TraceGraphAligner(neo4jGraphDao, writer);

        TraceGraphAligner.AlignmentResult result = aligner.align("project-1", "v1", List.of());

        assertEquals(1, result.staticOnlyCount());
        verify(neo4jGraphDao).updateEdge(any(GraphEdge.class));
    }
}
