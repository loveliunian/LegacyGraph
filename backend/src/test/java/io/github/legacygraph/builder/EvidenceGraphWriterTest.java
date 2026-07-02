package io.github.legacygraph.builder;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.EvidenceRecord;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.entity.Evidence;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.NodeEvidence;
import io.github.legacygraph.llm.SecretScanService;
import io.github.legacygraph.repository.EdgeEvidenceRepository;
import io.github.legacygraph.repository.EvidenceRepository;
import io.github.legacygraph.repository.NodeEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvidenceGraphWriterTest {

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
    void aiNodeWithoutSourcePathStillCreatesEvidenceAndStaysPending() {
        // MERGE 化后 upsertNode 单次 mergeNode 完成去重+创建；此处模拟本次新建（created=true）
        when(neo4jGraphDao.mergeNode(any(GraphNode.class))).thenAnswer(invocation ->
                new Neo4jGraphDao.NodeUpsert(invocation.getArgument(0), true));

        EvidenceGraphWriter writer = new EvidenceGraphWriter(
                neo4jGraphDao, evidenceRepository, nodeEvidenceRepository, edgeEvidenceRepository,
                new SecretScanService(), pgEvidenceTxExecutor);

        GraphNode node = writer.upsertNode(GraphNodeClaim.builder()
                .projectId("project-1")
                .versionId("v1")
                .nodeType("Feature")
                .nodeKey("create-order")
                .nodeName("创建订单")
                .displayName("创建订单")
                .sourceType("DOC_AI")
                .confidence(BigDecimal.valueOf(0.9))
                .status("CONFIRMED")
                .build());

        assertEquals("PENDING_CONFIRM", node.getStatus());

        ArgumentCaptor<Evidence> evidenceCaptor = ArgumentCaptor.forClass(Evidence.class);
        verify(evidenceRepository).insert(evidenceCaptor.capture());
        assertEquals("project-1", evidenceCaptor.getValue().getProjectId());
        assertEquals("v1", evidenceCaptor.getValue().getVersionId());
        assertEquals("ai", evidenceCaptor.getValue().getEvidenceType());
        verify(nodeEvidenceRepository).insert(any(NodeEvidence.class));
    }

    @Test
    void attachEvidenceInfersProjectAndVersionFromNode() {
        GraphNode node = new GraphNode();
        node.setId("node-1");
        node.setProjectId("project-1");
        node.setVersionId("v1");
        when(neo4jGraphDao.findNodeById("node-1")).thenReturn(Optional.of(node));

        EvidenceGraphWriter writer = new EvidenceGraphWriter(
                neo4jGraphDao, evidenceRepository, nodeEvidenceRepository, edgeEvidenceRepository,
                new SecretScanService(), pgEvidenceTxExecutor);

        writer.attachEvidence("node-1", EvidenceRecord.builder()
                .evidenceType("doc")
                .sourceName("需求文档")
                .build(), EvidenceGraphWriter.EvidenceRole.PRIMARY_SOURCE);

        ArgumentCaptor<Evidence> evidenceCaptor = ArgumentCaptor.forClass(Evidence.class);
        verify(evidenceRepository).insert(evidenceCaptor.capture());
        assertEquals("project-1", evidenceCaptor.getValue().getProjectId());
        assertEquals("v1", evidenceCaptor.getValue().getVersionId());
    }
}
