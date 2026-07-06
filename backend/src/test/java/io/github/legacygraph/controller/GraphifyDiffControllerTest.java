package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.graphify.GraphifyDiff;
import io.github.legacygraph.graphify.GraphifyDiffService;
import io.github.legacygraph.graphify.GraphifyImportJob;
import io.github.legacygraph.graphify.GraphifyImportJobRepository;
import io.github.legacygraph.graphify.GraphifyImportSnapshotService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphifyDiffControllerTest {

    @Test
    void diffShouldUseGraphifyKeysFromNeo4jSnapshots() {
        GraphifyImportJobRepository repository = new GraphifyImportJobRepository();
        repository.save(importedJob("job-old", "proj-1", "ver-1", "commit-a", "0.9.7"));
        repository.save(importedJob("job-new", "proj-1", "ver-2", "commit-b", "0.9.7"));

        Neo4jGraphDao graphDao = mock(Neo4jGraphDao.class);
        List<String> sourceTypes = List.of("GRAPHIFY_AST", "GRAPHIFY_SEMANTIC");
        when(graphDao.queryNodeKeysBySourceTypes("proj-1", "ver-1", sourceTypes))
            .thenReturn(Set.of("node:A"));
        when(graphDao.queryNodeKeysBySourceTypes("proj-1", "ver-2", sourceTypes))
            .thenReturn(Set.of("node:A", "node:B"));
        when(graphDao.queryEdgeKeysBySourceTypes("proj-1", "ver-1", sourceTypes))
            .thenReturn(Set.of("edge:A>B"));
        when(graphDao.queryEdgeKeysBySourceTypes("proj-1", "ver-2", sourceTypes))
            .thenReturn(Set.of("edge:A>B", "edge:B>C"));

        GraphifyImportSnapshotService snapshotService = new GraphifyImportSnapshotService(repository, graphDao);
        GraphifyDiffController controller = new GraphifyDiffController(new GraphifyDiffService(), snapshotService);

        Result<GraphifyDiff> response = controller.diff("proj-1", "ver-1", "ver-2");

        assertThat(response.getData()).isNotNull();
        assertThat(response.getData().addedNodes()).containsExactly("node:B");
        assertThat(response.getData().removedNodes()).isEmpty();
        assertThat(response.getData().addedEdges()).containsExactly("edge:B>C");
        assertThat(response.getData().removedEdges()).isEmpty();
        assertThat(response.getData().driftType()).isEqualTo(GraphifyDiff.DriftType.SOURCE_CODE_CHANGE);
    }

    private GraphifyImportJob importedJob(String jobId,
                                          String projectId,
                                          String versionId,
                                          String sourceCommit,
                                          String graphifyVersion) {
        return GraphifyImportJob.builder()
            .jobId(jobId)
            .projectId(projectId)
            .versionId(versionId)
            .sourceCommit(sourceCommit)
            .graphifyVersion(graphifyVersion)
            .status(GraphifyImportJob.Status.IMPORTED)
            .finishedAt(LocalDateTime.now())
            .build();
    }
}
