package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphQueryServiceTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @Mock
    private ScanVersionRepository scanVersionRepository;

    @Mock
    private ScanTaskRepository scanTaskRepository;

    @Mock
    private FactRepository factRepository;

    @Mock
    private Driver neo4jDriver;

    @Mock
    private Session session;

    @Mock
    private Result result;

    @Mock
    private Record record;

    @Mock
    private Path path;

    @Mock
    private Node node1;

    @Mock
    private Node node2;

    @Mock
    private Relationship relationship;

    @Mock
    private CacheService cacheService;

    private GraphQueryService graphQueryService;

    @BeforeEach
    void setUp() {
        graphQueryService = new GraphQueryService(
                neo4jGraphDao,
                scanVersionRepository,
                scanTaskRepository,
                factRepository,
                neo4jDriver,
                cacheService
        );
        // 缓存默认未命中：getOrLoad 直接执行 loader（回源），便于测试原始查询逻辑
        lenient().when(cacheService.getOrLoad(anyString(), any(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(3)).get());
    }

    @Test
    void testGetApiCallChain_EmptyResult() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(result.hasNext()).thenReturn(false);

        List<Map<String, Object>> resultList = graphQueryService.getApiCallChain("p1", "v1", "POST /api/test");

        assertNotNull(resultList);
        assertTrue(resultList.isEmpty());
        verify(session, times(1)).run(anyString(), anyMap());
        verify(session, times(1)).close();
    }

    @Test
    void testGetApiCallChain_WithResults() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(result.hasNext()).thenReturn(true, false);
        when(result.next()).thenReturn(record);
        when(record.get("p")).thenReturn(mock(org.neo4j.driver.Value.class));
        when(record.get("p").asPath()).thenReturn(path);
        
        List<Node> nodes = Arrays.asList(node1, node2);
        when(path.nodes()).thenReturn(nodes);
        when(node1.id()).thenReturn(1L);
        when(node1.labels()).thenReturn(Collections.singletonList("ApiEndpoint"));
        when(node1.asMap()).thenReturn(Map.of("nodeKey", "POST /api/test", "displayName", "测试接口"));
        when(node2.id()).thenReturn(2L);
        when(node2.labels()).thenReturn(Collections.singletonList("Service"));
        when(node2.asMap()).thenReturn(Map.of("nodeKey", "TestService", "displayName", "测试服务"));

        List<Map<String, Object>> resultList = graphQueryService.getApiCallChain("p1", "v1", "POST /api/test");

        assertNotNull(resultList);
        assertFalse(resultList.isEmpty());
        verify(session, times(1)).run(anyString(), anyMap());
    }

    @Test
    void testGetTableImpact_EmptyResult() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(result.hasNext()).thenReturn(false);

        List<Map<String, Object>> resultList = graphQueryService.getTableImpact("p1", "v1", "t_user");

        assertNotNull(resultList);
        assertTrue(resultList.isEmpty());
    }

    @Test
    void testGetTableImpact_WithResults() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(result.hasNext()).thenReturn(true, false);
        when(result.next()).thenReturn(record);
        when(record.get("p")).thenReturn(mock(org.neo4j.driver.Value.class));
        when(record.get("p").asPath()).thenReturn(path);

        when(path.nodes()).thenReturn(Arrays.asList(node1, node2));
        when(node1.elementId()).thenReturn("node-1");
        when(node1.labels()).thenReturn(Collections.singletonList("ApiEndpoint"));
        when(node1.asMap()).thenReturn(Map.of("nodeKey", "POST /api/test", "displayName", "测试接口"));
        when(node2.elementId()).thenReturn("node-2");
        when(node2.labels()).thenReturn(Collections.singletonList("Table"));
        when(node2.asMap()).thenReturn(Map.of("nodeName", "t_user", "displayName", "用户表"));

        when(path.relationships()).thenReturn(Collections.singletonList(relationship));
        when(relationship.elementId()).thenReturn("rel-1");
        when(relationship.type()).thenReturn("WRITES");
        when(relationship.startNodeElementId()).thenReturn("node-1");
        when(relationship.endNodeElementId()).thenReturn("node-2");
        when(relationship.asMap()).thenReturn(Map.of("confidence", 0.9));

        List<Map<String, Object>> resultList = graphQueryService.getTableImpact("p1", "v1", "t_user");

        assertNotNull(resultList);
        assertEquals(1, resultList.size());
        assertEquals(2, ((List<?>) resultList.get(0).get("nodes")).size());
        assertEquals(1, ((List<?>) resultList.get(0).get("edges")).size());
    }

    @Test
    void testGetFeatureView_EmptyResult() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(result.hasNext()).thenReturn(false);

        Map<String, Object> resultMap = graphQueryService.getFeatureView("p1", "v1", "user");

        assertNotNull(resultMap);
        assertEquals("user", resultMap.get("module"));
        assertEquals("v1", resultMap.get("versionId"));
        assertEquals("p1", resultMap.get("projectId"));
        assertEquals(0, ((List<?>) resultMap.get("nodes")).size());
        assertEquals(0, ((List<?>) resultMap.get("edges")).size());
    }

    @Test
    void testGetFeatureView_WithResults() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(result.hasNext()).thenReturn(true, false);
        when(result.next()).thenReturn(record);
        when(record.get("p")).thenReturn(mock(org.neo4j.driver.Value.class));
        when(record.get("p").asPath()).thenReturn(path);
        
        List<Node> nodes = Collections.singletonList(node1);
        when(path.nodes()).thenReturn(nodes);
        when(node1.elementId()).thenReturn("node-1");
        when(node1.labels()).thenReturn(Collections.singletonList("Feature"));
        when(node1.asMap()).thenReturn(Map.of("nodeKey", "feature-1", "displayName", "用户功能"));
        
        List<Relationship> relationships = Collections.singletonList(relationship);
        when(path.relationships()).thenReturn(relationships);
        when(relationship.elementId()).thenReturn("rel-1");
        when(relationship.type()).thenReturn("CALLS");
        when(relationship.startNodeElementId()).thenReturn("node-1");
        when(relationship.endNodeElementId()).thenReturn("node-2");
        when(relationship.asMap()).thenReturn(Map.of("confidence", 0.9));

        Map<String, Object> resultMap = graphQueryService.getFeatureView("p1", "v1", "user");

        assertNotNull(resultMap);
        assertEquals(1, ((List<?>) resultMap.get("nodes")).size());
        assertEquals(1, ((List<?>) resultMap.get("edges")).size());
    }
}
