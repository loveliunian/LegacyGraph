package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImpactSubgraphServiceTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    private ImpactSubgraphService service;

    @BeforeEach
    void setUp() {
        service = new ImpactSubgraphService(neo4jGraphDao);
    }

    @Test
    void extractByNode_missingTarget_returnsEmptyWithMessage() {
        when(neo4jGraphDao.findNodeById("missing")).thenReturn(Optional.empty());
        ImpactSubgraph sg = service.extractByNode("p1", "v1", "missing");
        assertEquals("missing", sg.getTargetNodeId());
        assertTrue(sg.getNodeIds().isEmpty());
        assertTrue(sg.getDependencySummary().contains("不存在"));
    }

    @Test
    void extractByNode_collectsNeighborsAndFiles() {
        GraphNode target = new GraphNode();
        target.setId("svc-1");
        target.setNodeName("TicketService");
        target.setDisplayName("TicketService");
        target.setNodeType("Service");
        target.setSourcePath("src/TicketService.java");
        when(neo4jGraphDao.findNodeById("svc-1")).thenReturn(Optional.of(target));

        GraphEdge edge = new GraphEdge();
        edge.setId("e1");
        edge.setFromNodeId("svc-1");
        edge.setToNodeId("mapper-1");
        edge.setEdgeType("CALLS");
        when(neo4jGraphDao.queryEdges(eq("p1"), eq("v1"), isNull(), isNull(), eq("svc-1"), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(edge));

        GraphNode mapper = new GraphNode();
        mapper.setId("mapper-1");
        mapper.setNodeName("TicketMapper");
        mapper.setNodeType("Mapper");
        mapper.setSourcePath("src/TicketMapper.xml");
        when(neo4jGraphDao.findNodesByIds(anyList())).thenReturn(List.of(target, mapper));

        ImpactSubgraph sg = service.extractByNode("p1", "v1", "svc-1");

        assertEquals("svc-1", sg.getTargetNodeId());
        assertTrue(sg.getNodeIds().contains("svc-1"));
        assertTrue(sg.getNodeIds().contains("mapper-1"));
        assertTrue(sg.getEdgeIds().contains("e1"));
        assertTrue(sg.getImpactedFiles().contains("src/TicketService.java"));
        assertTrue(sg.getImpactedFiles().contains("src/TicketMapper.xml"));
        assertTrue(sg.getDependencySummary().contains("TicketService"));
        assertTrue(sg.getDependencySummary().contains("CALLS"));
    }
}
