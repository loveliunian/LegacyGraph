package io.github.legacygraph.builder;

import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class FrontendGraphBuilderTest {

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

    private FrontendGraphBuilder frontendGraphBuilder;

    @Test
    void testConstruction() {
        frontendGraphBuilder = new FrontendGraphBuilder(graphNodeRepository, graphEdgeRepository,
                evidenceRepository, nodeEvidenceRepository, edgeEvidenceRepository);
        assertNotNull(frontendGraphBuilder);
    }
}
