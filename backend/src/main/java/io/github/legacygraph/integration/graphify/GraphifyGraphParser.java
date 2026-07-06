package io.github.legacygraph.integration.graphify;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Graphify graph.json 解析器。
 * <p>
 * 规则：
 * <ul>
 *   <li>文件必须存在且文件名为 {@code graph.json}</li>
 *   <li>文件大小 ≤ 50 MB</li>
 *   <li>使用 Jackson 解析</li>
 *   <li>{@code nodes} 为空时报错</li>
 *   <li>{@code links/edges} 都为空时允许导入孤立节点，但返回 warning</li>
 *   <li>所有 {@code source_file} 归一为 {@code /} 分隔</li>
 * </ul>
 */
@Slf4j
@Component
public class GraphifyGraphParser {

    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024; // 50 MB
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析 Graphify graph.json 文件。
     *
     * @param graphJsonPath graph.json 文件路径
     * @return 解析结果，包含 graph DTO 和 warnings
     * @throws GraphifyParseException 解析失败时抛出
     */
    public ParseResult parse(Path graphJsonPath) throws GraphifyParseException {
        // 1. 文件名校验
        if (!"graph.json".equals(graphJsonPath.getFileName().toString())) {
            throw new GraphifyParseException("文件名必须是 graph.json，实际为: " + graphJsonPath.getFileName());
        }

        // 2. 文件存在性校验
        if (!Files.exists(graphJsonPath)) {
            throw new GraphifyParseException("文件不存在: " + graphJsonPath);
        }

        // 3. 文件大小校验
        try {
            long size = Files.size(graphJsonPath);
            if (size > MAX_FILE_SIZE_BYTES) {
                throw new GraphifyParseException(
                        String.format("文件大小 %d MB 超过限制 %d MB", size / (1024 * 1024), MAX_FILE_SIZE_BYTES / (1024 * 1024)));
            }
        } catch (IOException e) {
            throw new GraphifyParseException("无法读取文件大小: " + e.getMessage());
        }

        // 4. JSON 解析
        GraphifyGraphJson json;
        try {
            String content = Files.readString(graphJsonPath);
            json = objectMapper.readValue(content, GraphifyGraphJson.class);
        } catch (IOException e) {
            throw new GraphifyParseException("JSON 解析失败: " + e.getMessage());
        }

        // 5. nodes 校验
        if (json.nodes() == null || json.nodes().isEmpty()) {
            throw new GraphifyParseException("nodes 为空或不存在");
        }

        // 6. edges/links 校验
        List<String> warnings = new ArrayList<>();
        List<GraphifyGraphJson.Edge> edges = json.resolvedEdges();
        if (edges.isEmpty()) {
            warnings.add("links 和 edges 都为空，仅导入孤立节点");
        }

        // 7. source_file 归一化
        List<GraphifyGraphJson.Node> normalizedNodes = json.nodes().stream()
                .map(node -> new GraphifyGraphJson.Node(
                        node.id(),
                        node.label(),
                        node.fileType(),
                        normalizePath(node.sourceFile()),
                        node.sourceLocation(),
                        node.community(),
                        node.communityName(),
                        node.normLabel()
                ))
                .toList();

        List<GraphifyGraphJson.Edge> normalizedEdges = edges.stream()
                .map(edge -> new GraphifyGraphJson.Edge(
                        edge.source(),
                        edge.target(),
                        edge.relation(),
                        edge.confidence(),
                        edge.confidenceScore(),
                        normalizePath(edge.sourceFile()),
                        edge.sourceLocation()
                ))
                .toList();

        // 8. 构造归一化后的 graph DTO
        GraphifyGraphJson normalizedGraph = new GraphifyGraphJson(
                json.directed(),
                normalizedNodes,
                json.edges() != null ? normalizedEdges : null,
                json.links() != null ? normalizedEdges : null,
                json.hyperedges(),
                json.builtAtCommit()
        );

        log.info("Graphify graph.json 解析成功: {} nodes, {} edges, {} warnings",
                normalizedNodes.size(), normalizedEdges.size(), warnings.size());

        return new ParseResult(normalizedGraph, warnings);
    }

    /**
     * 归一化路径：将 Windows 路径分隔符转换为 Unix 格式。
     */
    private String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        return path.replace('\\', '/');
    }

    /**
     * 解析结果。
     */
    public record ParseResult(GraphifyGraphJson graph, List<String> warnings) {}

    /**
     * Graphify 解析异常。
     */
    public static class GraphifyParseException extends Exception {
        public GraphifyParseException(String message) {
            super(message);
        }
    }
}
