package io.github.legacygraph.builder;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class FrontendGraphBuilderTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private EvidenceRepository evidenceRepository;
    @Mock
    private NodeEvidenceRepository nodeEvidenceRepository;
    @Mock
    private EdgeEvidenceRepository edgeEvidenceRepository;

    private FrontendGraphBuilder frontendGraphBuilder;

    @Test
    void testConstruction() {
        frontendGraphBuilder = new FrontendGraphBuilder(neo4jGraphDao,
                evidenceRepository, nodeEvidenceRepository, edgeEvidenceRepository);
        assertNotNull(frontendGraphBuilder);
    }
}
