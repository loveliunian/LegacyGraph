package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.eval.GraphifyQualityService;
import io.github.legacygraph.integration.graphify.GraphifyImportService;
import io.github.legacygraph.integration.graphify.GraphifyRunResult;
import io.github.legacygraph.integration.graphify.GraphifyRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphifyControllerTest {

    @Test
    void importGraphUsesScanVersionEndpointPayload() throws Exception {
        GraphifyRunner runner = mock(GraphifyRunner.class);
        GraphifyImportService importService = mock(GraphifyImportService.class);
        GraphifyController controller = new GraphifyController(runner, importService, mock(GraphifyQualityService.class));
        GraphifyImportService.ImportResult importResult = GraphifyImportService.ImportResult.builder()
                .success(true)
                .processedNodes(2)
                .processedEdges(1)
                .evidenceCount(3)
                .warnings(List.of())
                .build();
        when(importService.importGraph("project-1", "version-1", Path.of("/repo/graphify-out/graph.json")))
                .thenReturn(importResult);

        GraphifyController.ImportRequest request = new GraphifyController.ImportRequest();
        request.setProjectRoot("/repo");
        request.setGraphJsonPath("graphify-out/graph.json");

        Result<GraphifyImportService.ImportResult> response =
                controller.importGraph("project-1", "version-1", request);

        assertThat(response.getCode()).isEqualTo(0);
        assertThat(response.getData().getProcessedNodes()).isEqualTo(2);
        verify(importService).importGraph("project-1", "version-1", Path.of("/repo/graphify-out/graph.json"));
    }

    @Test
    void runUsesScanVersionEndpointAndImportsGeneratedGraphJson() throws Exception {
        GraphifyRunner runner = mock(GraphifyRunner.class);
        GraphifyImportService importService = mock(GraphifyImportService.class);
        GraphifyController controller = new GraphifyController(runner, importService, mock(GraphifyQualityService.class));
        when(runner.isAvailable()).thenReturn(true);
        when(runner.run(Path.of("/repo"))).thenReturn(GraphifyRunResult.builder()
                .success(true)
                .outputDir(Path.of("/repo/graphify-out"))
                .graphJsonPath(Path.of("/repo/graphify-out/graph.json"))
                .build());
        when(importService.importGraph("project-1", "version-1", Path.of("/repo/graphify-out/graph.json")))
                .thenReturn(GraphifyImportService.ImportResult.builder()
                        .success(true)
                        .processedNodes(1)
                        .processedEdges(1)
                        .evidenceCount(2)
                        .warnings(List.of())
                        .build());

        GraphifyController.RunRequest request = new GraphifyController.RunRequest();
        request.setProjectRoot("/repo");

        Result<GraphifyImportService.ImportResult> response =
                controller.runAndImport("project-1", "version-1", request);

        assertThat(response.getCode()).isEqualTo(0);
        assertThat(response.getData().getProcessedEdges()).isEqualTo(1);
        verify(runner).run(Path.of("/repo"));
        verify(importService).importGraph("project-1", "version-1", Path.of("/repo/graphify-out/graph.json"));
    }
}
