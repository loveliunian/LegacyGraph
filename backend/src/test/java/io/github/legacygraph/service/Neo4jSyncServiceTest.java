package io.github.legacygraph.service;

import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.dao.Neo4jGraphDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Neo4jSyncServiceTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    private Neo4jSyncService neo4jSyncService;

    private GraphNode testNode;

    @BeforeEach
    void setUp() {
        neo4jSyncService = new Neo4jSyncService(neo4jGraphDao);

        testNode = new GraphNode();
        testNode.setId("node-1");
        testNode.setProjectId("project-1");
        testNode.setVersionId("version-1");
        testNode.setNodeType("ApiEndpoint");
        testNode.setNodeKey("GET /api/test");
        testNode.setNodeName("Test API");
        testNode.setConfidence(BigDecimal.ONE);
        testNode.setStatus("CONFIRMED");
    }

    @Test
    void testSyncGraph_DelegatesToDeleteGraph() {
        neo4jSyncService.syncGraph("project-1", "version-1");
        verify(neo4jGraphDao).deleteGraph("project-1", "version-1");
    }

    @Test
    void testSyncDeleteNode() {
        neo4jSyncService.syncDeleteNode("project-1", "version-1", "node-1");
        verify(neo4jGraphDao).deleteNode("project-1", "version-1", "node-1");
    }

    @Test
    void testSyncDeleteNodes_WithMultipleNodes() {
        neo4jSyncService.syncDeleteNodes("project-1", "version-1", List.of("node-1", "node-2", "node-3"));
        verify(neo4jGraphDao, times(3)).deleteNode(eq("project-1"), eq("version-1"), anyString());
    }

    @Test
    void testIncrementalSyncNodes_EmptyList() {
        neo4jSyncService.incrementalSyncNodes("project-1", "version-1", Collections.emptyList());
        verify(neo4jGraphDao, never()).createNode(any());
    }

    @Test
    void testIncrementalSyncNodes_WithNodes() {
        neo4jSyncService.incrementalSyncNodes("project-1", "version-1", List.of(testNode));
        verify(neo4jGraphDao).createNode(testNode);
    }

    @Test
    void testIncrementalSyncNodes_SkipsDeletedNodes() {
        testNode.setDeleted(1);
        neo4jSyncService.incrementalSyncNodes("project-1", "version-1", List.of(testNode));
        verify(neo4jGraphDao, never()).createNode(any());
    }

    @Test
    void testCreateConstraints() {
        neo4jSyncService.createConstraints();
        verify(neo4jGraphDao).createConstraints();
    }

    @Test
    void testCreateIndexes() {
        neo4jSyncService.createIndexes();
        verify(neo4jGraphDao).createIndexes();
    }

    @Test
    void testConstructor_InjectionCorrect() {
        Neo4jSyncService service = new Neo4jSyncService(neo4jGraphDao);
        assertNotNull(service);
    }
}
