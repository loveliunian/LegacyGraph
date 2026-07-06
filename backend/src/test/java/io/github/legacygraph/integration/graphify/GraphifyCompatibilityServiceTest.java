package io.github.legacygraph.integration.graphify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Graphify 兼容性检查服务测试。
 */
class GraphifyCompatibilityServiceTest {

    private final GraphifyCompatibilityService service = new GraphifyCompatibilityService();

    @Test
    void acceptsPayloadWithLinks() {
        String json = """
                {
                  "nodes": [{"id": "a", "label": "A"}],
                  "links": [{"source": "a", "target": "b", "relation": "calls"}]
                }
                """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.isCanImport()).isTrue();
        assertThat(report.getSchemaVersion()).isEqualTo(GraphifySchemaVersion.V0_9_NETWORKX_LINKS);
        assertThat(report.getNodeCount()).isEqualTo(1);
        assertThat(report.getEdgeCount()).isEqualTo(1);
        assertThat(report.getMissingRequiredFields()).isEmpty();
    }

    @Test
    void acceptsPayloadWithEdges() {
        String json = """
                {
                  "nodes": [{"id": "a", "label": "A"}],
                  "edges": [{"source": "a", "target": "b", "relation": "calls"}]
                }
                """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.isCanImport()).isTrue();
        assertThat(report.getSchemaVersion()).isEqualTo(GraphifySchemaVersion.V0_9_NETWORKX_EDGES);
        assertThat(report.getNodeCount()).isEqualTo(1);
        assertThat(report.getEdgeCount()).isEqualTo(1);
        assertThat(report.getMissingRequiredFields()).isEmpty();
    }

    @Test
    void rejectsPayloadWithoutNodes() {
        String json = """
                {
                  "links": [{"source": "a", "target": "b", "relation": "calls"}]
                }
                """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.isCanImport()).isFalse();
        assertThat(report.getMissingRequiredFields()).contains("nodes");
    }

    @Test
    void rejectsPayloadWithEmptyNodes() {
        String json = """
                {
                  "nodes": [],
                  "links": [{"source": "a", "target": "b", "relation": "calls"}]
                }
                """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.isCanImport()).isFalse();
        assertThat(report.getMissingRequiredFields()).contains("nodes");
    }

    @Test
    void rejectsPayloadWithoutLinksOrEdges() {
        String json = """
                {
                  "nodes": [{"id": "a", "label": "A"}]
                }
                """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.isCanImport()).isFalse();
        assertThat(report.getMissingRequiredFields()).contains("links 或 edges");
    }

    @Test
    void warnsOnUnknownFields() {
        String json = """
                {
                  "nodes": [{"id": "a", "label": "A"}],
                  "links": [{"source": "a", "target": "b", "relation": "calls"}],
                  "custom_field": "unknown",
                  "metadata": {}
                }
                """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.isCanImport()).isTrue();
        assertThat(report.getUnsupportedTopLevelFields()).containsExactlyInAnyOrder("custom_field", "metadata");
        assertThat(report.getWarnings()).hasSize(2);
    }

    @Test
    void detectsSchemaVersionLinks() {
        String json = """
                {
                  "nodes": [{"id": "a", "label": "A"}],
                  "links": [{"source": "a", "target": "b", "relation": "calls"}]
                }
                """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.getSchemaVersion()).isEqualTo(GraphifySchemaVersion.V0_9_NETWORKX_LINKS);
    }

    @Test
    void detectsSchemaVersionEdges() {
        String json = """
                {
                  "nodes": [{"id": "a", "label": "A"}],
                  "edges": [{"source": "a", "target": "b", "relation": "calls"}]
                }
                """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.getSchemaVersion()).isEqualTo(GraphifySchemaVersion.V0_9_NETWORKX_EDGES);
    }

    @Test
    void rejectsInvalidJson() {
        String json = "not valid json";

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.isCanImport()).isFalse();
        assertThat(report.getMissingRequiredFields()).anyMatch(s -> s.contains("JSON 解析失败"));
    }

    @Test
    void countsHyperedges() {
        String json = """
                {
                  "nodes": [{"id": "a", "label": "A"}],
                  "links": [{"source": "a", "target": "b", "relation": "calls"}],
                  "hyperedges": [{"nodes": ["a", "b", "c"]}]
                }
                """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.isCanImport()).isTrue();
        assertThat(report.getHyperedgeCount()).isEqualTo(1);
    }
}
