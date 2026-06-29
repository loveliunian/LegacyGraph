package io.github.legacygraph.builder;

import io.github.legacygraph.entity.Evidence;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.*;
import io.github.legacygraph.service.Neo4jSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class GraphBuilderTest {

    @Mock
    private GraphNodeRepository graphNodeRepository;
    @Mock
    private GraphEdgeRepository graphEdgeRepository;
    @Mock
    private EvidenceRepository evidenceRepository;
    @Mock
    private NodeEvidenceRepository nodeEvidenceRepository;
    @Mock
    private EdgeEvidenceRepository edgeEvidenceRepository;
    @Mock
    private Neo4jSyncService neo4jSyncService;

    private GraphBuilder graphBuilder;

    @Test
    void testConstruction() {
        graphBuilder = new GraphBuilder(graphNodeRepository, graphEdgeRepository,
                evidenceRepository, nodeEvidenceRepository, edgeEvidenceRepository,
                neo4jSyncService);
        assertNotNull(graphBuilder);
    }
}
