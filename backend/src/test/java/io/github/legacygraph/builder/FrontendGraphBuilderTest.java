package io.github.legacygraph.builder;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.llm.SecretScanService;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
    @Mock
    private PgEvidenceTxExecutor pgEvidenceTxExecutor;

    private FrontendGraphBuilder frontendGraphBuilder;

    @BeforeEach
    void setUp() {
        // Mock PgEvidenceTxExecutor to directly run the Runnable (bypass @Transactional proxy in unit test)
        lenient().doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(pgEvidenceTxExecutor).execute(any(Runnable.class));
    }

    @Test
    void testConstruction() {
        EvidenceGraphWriter writer = new EvidenceGraphWriter(
                neo4jGraphDao, evidenceRepository, nodeEvidenceRepository, edgeEvidenceRepository,
                new SecretScanService(), pgEvidenceTxExecutor);
        frontendGraphBuilder = new FrontendGraphBuilder(neo4jGraphDao, writer);
        assertNotNull(frontendGraphBuilder);
    }
}
