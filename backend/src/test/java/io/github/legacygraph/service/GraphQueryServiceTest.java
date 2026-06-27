package io.github.legacygraph.service;

import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphQueryServiceTest {

    @Mock
    private GraphNodeRepository graphNodeRepository;

    @Mock
    private GraphEdgeRepository graphEdgeRepository;

    @Mock
    private Driver neo4jDriver;

    @Mock
    private Session session;

    @Mock
    private Result result;

    private GraphQueryService graphQueryService;

    @BeforeEach
    void setUp() {
        graphQueryService = new GraphQueryService(graphNodeRepository, graphEdgeRepository, neo4jDriver);
    }

    @Test
    void testGetApiCallChain_ReturnsEmptyWhenNoResults() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), any(Map.class))).thenReturn(result);
        when(result.hasNext()).thenReturn(false);

        List<Map<String, Object>> result = graphQueryService.getApiCallChain("version-1", "GET /api/test");

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(session).close();
    }

    @Test
    void testGetTableImpact_ReturnsEmptyWhenNoResults() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), any(Map.class))).thenReturn(result);
        when(result.hasNext()).thenReturn(false);

        List<Map<String, Object>> result = graphQueryService.getTableImpact("version-1", "users");

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(session).close();
    }

    @Test
    void testGetFeatureView_ReturnsEmptyStructureWhenNoResults() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), any(Map.class))).thenReturn(result);
        when(result.hasNext()).thenReturn(false);

        Map<String, Object> result = graphQueryService.getFeatureView("version-1", "user-module");

        assertNotNull(result);
        assertEquals("version-1", result.get("versionId"));
        assertEquals("user-module", result.get("module"));
        assertTrue(((List<?>) result.get("nodes")).isEmpty());
        assertTrue(((List<?>) result.get("edges")).isEmpty());
        assertEquals(0, result.get("nodeCount"));
        assertEquals(0, result.get("edgeCount"));
        verify(session).close();
    }

    @Test
    void testGetBusinessView_ReturnsEmptyStructureWhenNoResults() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), any(Map.class))).thenReturn(result);
        when(result.hasNext()).thenReturn(false);

        Map<String, Object> result = graphQueryService.getBusinessView("version-1", "order-domain");

        assertNotNull(result);
        assertEquals("version-1", result.get("versionId"));
        assertEquals("order-domain", result.get("domain"));
        assertTrue(((List<?>) result.get("nodes")).isEmpty());
        assertTrue(((List<?>) result.get("edges")).isEmpty());
        assertEquals(0, result.get("nodeCount"));
        assertEquals(0, result.get("edgeCount"));
        verify(session).close();
    }

    @Test
    void testConstructor_InjectsDependenciesCorrectly() {
        GraphQueryService service = new GraphQueryService(graphNodeRepository, graphEdgeRepository, neo4jDriver);
        assertNotNull(service);
    }
}
