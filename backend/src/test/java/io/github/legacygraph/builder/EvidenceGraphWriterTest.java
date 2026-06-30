package io.github.legacygraph.builder;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.EvidenceRecord;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.entity.Evidence;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.NodeEvidence;
import io.github.legacygraph.repository.EdgeEvidenceRepository;
import io.github.legacygraph.repository.EvidenceRepository;
import io.github.legacygraph.repository.NodeEvidenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void aiNodeWithoutSourcePathStillCreatesEvidenceAndStaysPending() {
        when(neo4jGraphDao.findNode("project-1", "v1", "Feature", "create-order"))
                .thenReturn(Optional.empty());
        when(neo4jGraphDao.createNode(any(GraphNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EvidenceGraphWriter writer = new EvidenceGraphWriter(
                neo4jGraphDao, evidenceRepository, nodeEvidenceRepository, edgeEvidenceRepository);

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
                neo4jGraphDao, evidenceRepository, nodeEvidenceRepository, edgeEvidenceRepository);

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
