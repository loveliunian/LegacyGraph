package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Neo4jSyncServiceTest {

    @Mock
    private GraphNodeRepository graphNodeRepository;

    @Mock
    private GraphEdgeRepository graphEdgeRepository;

    @Mock
    private Driver neo4jDriver;

    @Mock
    private Session session;

    @Mock
    private Transaction transaction;

    @Mock
    private org.neo4j.driver.Result result;

    private Neo4jSyncService neo4jSyncService;

    private GraphNode testNode;
    private GraphEdge testEdge;

    @BeforeEach
    void setUp() {
        neo4jSyncService = new Neo4jSyncService(graphNodeRepository, graphEdgeRepository, neo4jDriver);

        testNode = new GraphNode();
        testNode.setId("node-1");
        testNode.setProjectId("project-1");
        testNode.setVersionId("version-1");
        testNode.setNodeType("ApiEndpoint");
        testNode.setNodeKey("GET /api/test");
        testNode.setNodeName("Test API");
        testNode.setDisplayName("Test API");
        testNode.setDescription("Test API endpoint");
        testNode.setConfidence(BigDecimal.ONE);
        testNode.setStatus("CONFIRMED");

        testEdge = new GraphEdge();
        testEdge.setId("edge-1");
        testEdge.setProjectId("project-1");
        testEdge.setVersionId("version-1");
        testEdge.setFromNodeId("node-1");
        testEdge.setToNodeId("node-2");
        testEdge.setEdgeType("CALLS");
        testEdge.setEdgeKey("node-1-calls-node-2");
        testEdge.setConfidence(BigDecimal.ONE);
        testEdge.setStatus("CONFIRMED");
    }

    @Test
    void testSyncGraph_EmptyGraph() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);

        // Since we can't easily mock the entire lambda chain, just verify
        // that the method completes without exceptions when repositories return empty
        LambdaQueryChainWrapper<GraphNode> nodeChain = mock(LambdaQueryChainWrapper.class);
        when(graphNodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(Collections.emptyList());

        LambdaQueryChainWrapper<GraphEdge> edgeChain = mock(LambdaQueryChainWrapper.class);
        when(graphEdgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(Collections.emptyList());

        neo4jSyncService.syncGraph("project-1", "version-1");

        verify(session).run(anyString(), anyMap()); // Delete existing
        verify(session).close();
    }

    @Test
    void testSyncGraph_WithSingleNode() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(session.beginTransaction()).thenReturn(transaction);
        when(transaction.run(anyString(), anyMap())).thenReturn(result);

        LambdaQueryChainWrapper<GraphNode> nodeChain = mock(LambdaQueryChainWrapper.class);
        when(graphNodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(List.of(testNode));

        LambdaQueryChainWrapper<GraphEdge> edgeChain = mock(LambdaQueryChainWrapper.class);
        when(graphEdgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(Collections.emptyList());

        neo4jSyncService.syncGraph("project-1", "version-1");

        verify(transaction).run(contains("CREATE"), anyMap());
        verify(transaction).commit();
        verify(session).close();
    }

    @Test
    void testSyncGraph_WithNodeAndEdge() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(session.beginTransaction()).thenReturn(transaction);
        when(transaction.run(anyString(), anyMap())).thenReturn(result);

        GraphNode node2 = new GraphNode();
        node2.setId("node-2");
        node2.setProjectId("project-1");
        node2.setVersionId("version-1");
        node2.setNodeType("Service");
        node2.setNodeKey("TestService");
        node2.setNodeName("TestService");
        node2.setDisplayName("TestService");
        node2.setConfidence(BigDecimal.ONE);
        node2.setStatus("CONFIRMED");

        LambdaQueryChainWrapper<GraphNode> nodeChain = mock(LambdaQueryChainWrapper.class);
        when(graphNodeRepository.lambdaQuery()).thenReturn(nodeChain);
        when(nodeChain.eq(any(), any())).thenReturn(nodeChain);
        when(nodeChain.list()).thenReturn(List.of(testNode, node2));

        LambdaQueryChainWrapper<GraphEdge> edgeChain = mock(LambdaQueryChainWrapper.class);
        when(graphEdgeRepository.lambdaQuery()).thenReturn(edgeChain);
        when(edgeChain.eq(any(), any())).thenReturn(edgeChain);
        when(edgeChain.list()).thenReturn(List.of(testEdge));

        neo4jSyncService.syncGraph("project-1", "version-1");

        verify(transaction, times(3)).run(anyString(), anyMap()); // 2 nodes + 1 edge
        verify(transaction).run(contains("MATCH"), anyMap()); // The edge query specifically
        verify(transaction, times(2)).commit(); // One for nodes, one for edges
        verify(session).close();
    }

    @Test
    void testSyncDeleteNode() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);

        neo4jSyncService.syncDeleteNode("project-1", "version-1", "node-1");

        verify(session).run(anyString(), anyMap());
        verify(session).close();
    }

    @Test
    void testSyncDeleteNodes_EmptyList() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.beginTransaction()).thenReturn(transaction);

        neo4jSyncService.syncDeleteNodes("project-1", "version-1", Collections.emptyList());

        verify(transaction).commit();
        verify(session).close();
    }

    @Test
    void testSyncDeleteNodes_WithMultipleNodes() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.beginTransaction()).thenReturn(transaction);
        when(transaction.run(anyString(), anyMap())).thenReturn(result);

        neo4jSyncService.syncDeleteNodes("project-1", "version-1", List.of("node-1", "node-2", "node-3"));

        verify(transaction, times(3)).run(anyString(), anyMap());
        verify(transaction).commit();
        verify(session).close();
    }

    @Test
    void testIncrementalSyncNodes_EmptyList() {
        neo4jSyncService.incrementalSyncNodes("project-1", "version-1", Collections.emptyList());

        // Method returns early, so session should not have been created
        verify(neo4jDriver, never()).session();
    }

    @Test
    void testIncrementalSyncNodes_WithNodes() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.beginTransaction()).thenReturn(transaction);

        neo4jSyncService.incrementalSyncNodes("project-1", "version-1", List.of(testNode));

        verify(transaction).run(contains("CREATE"), anyMap());
        verify(transaction).commit();
        verify(session).close();
    }

    @Test
    void testIncrementalSyncNodes_SkipsDeletedNodes() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.beginTransaction()).thenReturn(transaction);

        testNode.setDeleted(1);

        neo4jSyncService.incrementalSyncNodes("project-1", "version-1", List.of(testNode));

        verify(transaction, never()).run(anyString(), anyMap());
        verify(session).close();
    }

    @Test
    void testCreateConstraints() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString())).thenReturn(result);

        neo4jSyncService.createConstraints();

        verify(session, times(7)).run(contains("CREATE CONSTRAINT"));
        verify(session).close();
    }

    @Test
    void testCreateIndexes() {
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString())).thenReturn(result);

        neo4jSyncService.createIndexes();

        verify(session, times(2)).run(contains("CREATE INDEX"));
        verify(session).close();
    }

    @Test
    void testConstructor_InjectionCorrect() {
        Neo4jSyncService service = new Neo4jSyncService(graphNodeRepository, graphEdgeRepository, neo4jDriver);
        assertNotNull(service);
    }
}
