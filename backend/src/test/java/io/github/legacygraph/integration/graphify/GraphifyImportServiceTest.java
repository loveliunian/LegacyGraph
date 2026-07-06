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

    @Test
    void importsValidGraph(@TempDir Path tempDir) throws IOException, GraphifyImportService.GraphifyImportException {
        // 准备测试数据
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

        // Mock 依赖
        GraphifyGraphParser parser = new GraphifyGraphParser();
        GraphifyCanonicalMapper mapper = new GraphifyCanonicalMapper(new ObjectMapper());
        EvidenceGraphWriter writer = mock(EvidenceGraphWriter.class);

        GraphifyImportService service = new GraphifyImportService(parser, mapper, writer);

        // 执行导入
        GraphifyImportService.ImportResult result = service.importGraph("proj1", "v1", graphJson);

        // 验证结果
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getProcessedNodes()).isEqualTo(1);
        assertThat(result.getProcessedEdges()).isEqualTo(1);
        assertThat(result.getEvidenceCount()).isEqualTo(2); // 1 node + 1 edge
        assertThat(result.getWarnings()).isEmpty();

        // 验证 writer 被调用
        verify(writer, times(1)).writeIntent(any());
    }

    @Test
    void rejectsInvalidJson(@TempDir Path tempDir) throws IOException {
        Path graphJson = tempDir.resolve("graph.json");
        Files.writeString(graphJson, "not valid json");

        GraphifyGraphParser parser = new GraphifyGraphParser();
        GraphifyCanonicalMapper mapper = new GraphifyCanonicalMapper(new ObjectMapper());
        EvidenceGraphWriter writer = mock(EvidenceGraphWriter.class);

        GraphifyImportService service = new GraphifyImportService(parser, mapper, writer);

        assertThatThrownBy(() -> service.importGraph("proj1", "v1", graphJson))
                .isInstanceOf(GraphifyImportService.GraphifyImportException.class)
                .hasMessageContaining("解析失败");
    }

    @Test
    void rejectsWrongFileName(@TempDir Path tempDir) throws IOException {
        Path wrongFile = tempDir.resolve("wrong.json");
        Files.writeString(wrongFile, "{}");

        GraphifyGraphParser parser = new GraphifyGraphParser();
        GraphifyCanonicalMapper mapper = new GraphifyCanonicalMapper(new ObjectMapper());
        EvidenceGraphWriter writer = mock(EvidenceGraphWriter.class);

        GraphifyImportService service = new GraphifyImportService(parser, mapper, writer);

        assertThatThrownBy(() -> service.importGraph("proj1", "v1", wrongFile))
                .isInstanceOf(GraphifyImportService.GraphifyImportException.class)
                .hasMessageContaining("文件名必须是 graph.json");
    }

    @Test
    void handlesEmptyEdges(@TempDir Path tempDir) throws IOException, GraphifyImportService.GraphifyImportException {
        Path graphJson = tempDir.resolve("graph.json");
        Files.writeString(graphJson, """
                {
                  "nodes": [{"id": "n1", "label": "Node1"}]
                }
                """);

        GraphifyGraphParser parser = new GraphifyGraphParser();
        GraphifyCanonicalMapper mapper = new GraphifyCanonicalMapper(new ObjectMapper());
        EvidenceGraphWriter writer = mock(EvidenceGraphWriter.class);

        GraphifyImportService service = new GraphifyImportService(parser, mapper, writer);

        GraphifyImportService.ImportResult result = service.importGraph("proj1", "v1", graphJson);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getProcessedNodes()).isEqualTo(1);
        assertThat(result.getProcessedEdges()).isEqualTo(0);
        assertThat(result.getWarnings()).contains("links 和 edges 都为空，仅导入孤立节点");
    }
}
