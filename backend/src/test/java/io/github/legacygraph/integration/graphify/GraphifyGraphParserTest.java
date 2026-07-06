package io.github.legacygraph.integration.graphify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Graphify graph.json 解析器测试。
 */
class GraphifyGraphParserTest {

    private final GraphifyGraphParser parser = new GraphifyGraphParser();

    @Test
    void parsesLinksFormat() throws IOException, GraphifyGraphParser.GraphifyParseException {
        // 读取 links 格式的样例
        String content = new String(getClass().getResourceAsStream("/graphify/graphify-node-link-sample.json").readAllBytes());

        Path tempDir = Files.createTempDirectory("graphify-test");
        Path graphJson = tempDir.resolve("graph.json");
        Files.writeString(graphJson, content);

        GraphifyGraphParser.ParseResult result = parser.parse(graphJson);

        assertThat(result.graph().nodes()).hasSize(2);
        assertThat(result.graph().resolvedEdges()).hasSize(1);
        assertThat(result.graph().resolvedEdges().getFirst().relation()).isEqualTo("calls");
        assertThat(result.graph().builtAtCommit()).isEqualTo("31211a0e7c512d63972b4f0438877d3777ae0e85");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void parsesEdgesFormat() throws IOException, GraphifyGraphParser.GraphifyParseException {
        // 读取 edges 格式的样例
        String content = new String(getClass().getResourceAsStream("/graphify/graphify-edges-sample.json").readAllBytes());

        Path tempDir = Files.createTempDirectory("graphify-test");
        Path graphJson = tempDir.resolve("graph.json");
        Files.writeString(graphJson, content);

        GraphifyGraphParser.ParseResult result = parser.parse(graphJson);

        assertThat(result.graph().nodes()).hasSize(2);
        assertThat(result.graph().resolvedEdges()).hasSize(1);
        assertThat(result.graph().resolvedEdges().getFirst().relation()).isEqualTo("calls");
        assertThat(result.graph().builtAtCommit()).isEqualTo("31211a0e7c512d63972b4f0438877d3777ae0e85");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void rejectsWrongFileName(@TempDir Path tempDir) {
        Path wrongFile = tempDir.resolve("wrong-name.json");

        assertThatThrownBy(() -> parser.parse(wrongFile))
                .isInstanceOf(GraphifyGraphParser.GraphifyParseException.class)
                .hasMessageContaining("文件名必须是 graph.json");
    }

    @Test
    void rejectsNonExistentFile(@TempDir Path tempDir) {
        Path nonExistent = tempDir.resolve("graph.json");

        assertThatThrownBy(() -> parser.parse(nonExistent))
                .isInstanceOf(GraphifyGraphParser.GraphifyParseException.class)
                .hasMessageContaining("文件不存在");
    }

    @Test
    void rejectsEmptyNodes(@TempDir Path tempDir) throws IOException {
        Path graphJson = tempDir.resolve("graph.json");
        Files.writeString(graphJson, """
                {
                  "nodes": [],
                  "links": []
                }
                """);

        assertThatThrownBy(() -> parser.parse(graphJson))
                .isInstanceOf(GraphifyGraphParser.GraphifyParseException.class)
                .hasMessageContaining("nodes 为空");
    }

    @Test
    void warnsWhenNoEdges(@TempDir Path tempDir) throws IOException, GraphifyGraphParser.GraphifyParseException {
        Path graphJson = tempDir.resolve("graph.json");
        Files.writeString(graphJson, """
                {
                  "nodes": [{"id": "node1", "label": "Node1"}]
                }
                """);

        GraphifyGraphParser.ParseResult result = parser.parse(graphJson);

        assertThat(result.graph().nodes()).hasSize(1);
        assertThat(result.graph().resolvedEdges()).isEmpty();
        assertThat(result.warnings()).containsExactly("links 和 edges 都为空，仅导入孤立节点");
    }

    @Test
    void normalizesWindowsPaths(@TempDir Path tempDir) throws IOException, GraphifyGraphParser.GraphifyParseException {
        Path graphJson = tempDir.resolve("graph.json");
        Files.writeString(graphJson, """
                {
                  "nodes": [
                    {"id": "n1", "label": "Node1", "source_file": "backend\\\\src\\\\main\\\\java\\\\Demo.java"}
                  ],
                  "links": [
                    {"source": "n1", "target": "n2", "relation": "calls", "source_file": "backend\\\\src\\\\main\\\\java\\\\Demo.java"}
                  ]
                }
                """);

        GraphifyGraphParser.ParseResult result = parser.parse(graphJson);

        assertThat(result.graph().nodes().getFirst().sourceFile())
                .isEqualTo("backend/src/main/java/Demo.java");
        assertThat(result.graph().resolvedEdges().getFirst().sourceFile())
                .isEqualTo("backend/src/main/java/Demo.java");
    }
}
