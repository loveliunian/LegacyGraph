package io.github.legacygraph.integration.graphify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Graphify graph.json 的 Java DTO。
 * <p>
 * 兼容 NetworkX node-link 格式，支持 {@code links} 和 {@code edges} 两种边字段名。
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphifyGraphJson(
        @JsonProperty("directed") Boolean directed,
        @JsonProperty("nodes") List<Node> nodes,
        @JsonProperty("edges") List<Edge> edges,
        @JsonProperty("links") List<Edge> links,
        @JsonProperty("hyperedges") List<Map<String, Object>> hyperedges,
        @JsonProperty("built_at_commit") String builtAtCommit
) {

    /**
     * 解析后的边列表：优先 {@code edges}，回退 {@code links}。
     */
    public List<Edge> resolvedEdges() {
        if (edges != null && !edges.isEmpty()) {
            return edges;
        }
        return links != null ? links : List.of();
    }

    /**
     * Graphify 节点。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Node(
            @JsonProperty("id") String id,
            @JsonProperty("label") String label,
            @JsonProperty("file_type") String fileType,
            @JsonProperty("source_file") String sourceFile,
            @JsonProperty("source_location") String sourceLocation,
            @JsonProperty("community") Integer community,
            @JsonProperty("community_name") String communityName,
            @JsonProperty("norm_label") String normLabel
    ) {}

    /**
     * Graphify 边（link / edge）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Edge(
            @JsonProperty("source") String source,
            @JsonProperty("target") String target,
            @JsonProperty("relation") String relation,
            @JsonProperty("confidence") String confidence,
            @JsonProperty("confidence_score") Double confidenceScore,
            @JsonProperty("source_file") String sourceFile,
            @JsonProperty("source_location") String sourceLocation
    ) {}
}
