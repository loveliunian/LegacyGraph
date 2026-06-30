package io.github.legacygraph.dao;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Neo4jGraphDaoTest {

    @Mock
    private Driver neo4jDriver;

    @Mock
    private Session session;

    @Mock
    private Result result;

    @Test
    void graphStatsReturnsAllZeroCountersWhenNoRows() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(result.hasNext()).thenReturn(false);

        Neo4jGraphDao dao = new Neo4jGraphDao(neo4jDriver);

        Map<String, Object> stats = dao.graphStats("project-1");

        assertNotNull(stats);
        assertEquals(0L, stats.get("totalNodes"));
        assertEquals(0L, stats.get("confirmedNodes"));
        assertEquals(0L, stats.get("pendingNodes"));
        assertEquals(0.0, stats.get("avgConfidence"));
        assertEquals(0L, stats.get("withEvidenceCount"));
        assertEquals(0L, stats.get("noEvidenceNodes"));
        assertEquals(0L, stats.get("aiOnlyNodes"));
        assertEquals(0L, stats.get("totalEdges"));
        assertEquals(0L, stats.get("confirmedEdges"));
        assertEquals(0L, stats.get("pendingEdges"));
        assertEquals(0L, stats.get("noEvidenceEdges"));
        assertEquals(0L, stats.get("aiOnlyEdges"));
        assertEquals(0L, stats.get("runtimeOnlyEdges"));
    }
}
