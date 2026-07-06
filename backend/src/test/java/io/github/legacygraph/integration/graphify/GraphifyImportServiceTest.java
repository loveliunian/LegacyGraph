package io.github.legacygraph.integration.graphify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.builder.EvidenceGraphWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Graphify 导入服务测试。
 */
class GraphifyImportServiceTest {

    private GraphifyImportService buildService(EvidenceGraphWriter writer) {
        GraphifyGraphParser parser = new GraphifyGraphParser();
        GraphifyCanonicalMapper mapper = new GraphifyCanonicalMapper(new ObjectMapper());
        GraphifyCompatibilityService compatibilityService = new GraphifyCompatibilityService();
        return new GraphifyImportService(parser, mapper, writer, compatibilityService);
    }

    @Test
    void importsValidGraph(@TempDir Path tempDir) throws IOException, GraphifyImportService.GraphifyImportException {
        Path graphJson = tempDir.resolve("graph.json");
        Files.writeString(graphJson, """
                {
                  "directed": true,
                  "nodes": [
                    {"id": "n1", "label": "Controller", "file_type": "code", "source_file": "a.java"}
                  ],
                  "links": [
                    {"source": "n1", "target": "n1", "relation": "calls", "confidence": "EXTRACTED"}
                  ]
                }
                """);

        EvidenceGraphWriter writer = mock(EvidenceGraphWriter.class);
        GraphifyImportService service = buildService(writer);

        GraphifyImportService.ImportResult result = service.importGraph("proj1", "v1", graphJson);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getProcessedNodes()).isEqualTo(1);
        assertThat(result.getProcessedEdges()).isEqualTo(1);
        assertThat(result.getEvidenceCount()).isEqualTo(2); // 1 node + 1 edge
        assertThat(result.getWarnings()).isEmpty();

        verify(writer, times(1)).writeIntent(any());
    }

    @Test
    void rejectsInvalidJson(@TempDir Path tempDir) throws IOException {
        Path graphJson = tempDir.resolve("graph.json");
        Files.writeString(graphJson, "not valid json");

        EvidenceGraphWriter writer = mock(EvidenceGraphWriter.class);
        GraphifyImportService service = buildService(writer);

        assertThatThrownBy(() -> service.importGraph("proj1", "v1", graphJson))
                .isInstanceOf(GraphifyImportService.GraphifyImportException.class)
                .hasMessageContaining("兼容性检查失败");
    }

    @Test
    void rejectsPayloadMissingNodes(@TempDir Path tempDir) throws IOException {
        Path graphJson = tempDir.resolve("graph.json");
        Files.writeString(graphJson, """
                {
                  "links": [{"source": "a", "target": "b", "relation": "calls"}]
                }
                """);

        EvidenceGraphWriter writer = mock(EvidenceGraphWriter.class);
        GraphifyImportService service = buildService(writer);

        assertThatThrownBy(() -> service.importGraph("proj1", "v1", graphJson))
                .isInstanceOf(GraphifyImportService.GraphifyImportException.class)
                .hasMessageContaining("兼容性检查失败")
                .hasMessageContaining("nodes");
    }

    @Test
    void rejectsPayloadWithoutLinksOrEdges(@TempDir Path tempDir) throws IOException {
        Path graphJson = tempDir.resolve("graph.json");
        Files.writeString(graphJson, """
                {
                  "nodes": [{"id": "n1", "label": "Node1"}]
                }
                """);

        EvidenceGraphWriter writer = mock(EvidenceGraphWriter.class);
        GraphifyImportService service = buildService(writer);

        assertThatThrownBy(() -> service.importGraph("proj1", "v1", graphJson))
                .isInstanceOf(GraphifyImportService.GraphifyImportException.class)
                .hasMessageContaining("兼容性检查失败")
                .hasMessageContaining("links 或 edges");
    }
}
