package io.github.legacygraph.graphify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.integration.graphify.GraphifyCompatibilityReport;
import io.github.legacygraph.integration.graphify.GraphifyCompatibilityService;
import io.github.legacygraph.integration.graphify.GraphifySchemaVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GraphifyCompatibilityService}.
 */
class GraphifyCompatibilityServiceTest {

    private GraphifyCompatibilityService service;

    @BeforeEach
    void setUp() {
        service = new GraphifyCompatibilityService();
    }

    @Test
    @DisplayName("Accepts links format and reports correct schema version")
    void shouldAcceptLinksFormat() {
        String json = """
            {
                "nodes": [{"id": "A"}, {"id": "B"}],
                "links": [{"source": "A", "target": "B"}]
            }
            """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.getSchemaVersion()).isEqualTo(GraphifySchemaVersion.V0_9_NETWORKX_LINKS);
        assertThat(report.isCanImport()).isTrue();
        assertThat(report.getNodeCount()).isEqualTo(2);
        assertThat(report.getEdgeCount()).isEqualTo(1);
        assertThat(report.getMissingRequiredFields()).isEmpty();
    }

    @Test
    @DisplayName("Accepts edges format and reports correct schema version")
    void shouldAcceptEdgesFormat() {
        String json = """
            {
                "nodes": [{"id": "X"}, {"id": "Y"}, {"id": "Z"}],
                "edges": [{"source": "X", "target": "Y"}, {"source": "Y", "target": "Z"}]
            }
            """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.getSchemaVersion()).isEqualTo(GraphifySchemaVersion.V0_9_NETWORKX_EDGES);
        assertThat(report.isCanImport()).isTrue();
        assertThat(report.getNodeCount()).isEqualTo(3);
        assertThat(report.getEdgeCount()).isEqualTo(2);
        assertThat(report.getMissingRequiredFields()).isEmpty();
    }

    @Test
    @DisplayName("Rejects when nodes field is missing")
    void shouldRejectWhenNodesMissing() {
        String json = """
            {
                "links": [{"source": "A", "target": "B"}]
            }
            """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.isCanImport()).isFalse();
        assertThat(report.getMissingRequiredFields()).contains("nodes");
    }

    @Test
    @DisplayName("Rejects when both links and edges are missing")
    void shouldRejectWhenLinksAndEdgesMissing() {
        String json = """
            {
                "nodes": [{"id": "A"}]
            }
            """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.isCanImport()).isFalse();
        assertThat(report.getMissingRequiredFields()).anyMatch(f -> f.contains("links") || f.contains("edges"));
    }

    @Test
    @DisplayName("Unknown top-level fields go into warnings without blocking import")
    void shouldReportUnknownFieldsInWarnings() {
        String json = """
            {
                "nodes": [{"id": "A"}],
                "links": [{"source": "A", "target": "B"}],
                "customField": "some value",
                "experimentalData": [1, 2, 3]
            }
            """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.isCanImport()).isTrue();
        assertThat(report.getUnsupportedTopLevelFields()).containsExactlyInAnyOrder("customField", "experimentalData");
        assertThat(report.getWarnings()).hasSize(2);
        assertThat(report.getWarnings()).anyMatch(w -> w.contains("customField"));
        assertThat(report.getWarnings()).anyMatch(w -> w.contains("experimentalData"));
    }

    @Test
    @DisplayName("Counts hyperedges when present")
    void shouldCountHyperedges() {
        String json = """
            {
                "nodes": [{"id": "A"}, {"id": "B"}, {"id": "C"}],
                "links": [{"source": "A", "target": "B"}],
                "hyperedges": [{"members": ["A", "B", "C"]}]
            }
            """;

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.getHyperedgeCount()).isEqualTo(1);
        assertThat(report.isCanImport()).isTrue();
    }

    @Test
    @DisplayName("Handles invalid JSON gracefully")
    void shouldHandleInvalidJson() {
        String json = "not valid json at all";

        GraphifyCompatibilityReport report = service.inspect(json);

        assertThat(report.isCanImport()).isFalse();
        assertThat(report.getSchemaVersion()).isEqualTo(GraphifySchemaVersion.UNKNOWN);
    }
}
