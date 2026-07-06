package io.github.legacygraph.integration.graphify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Graphify JSON 兼容性检查服务。
 * <p>
 * 在导入前检查 JSON 是否符合 Graphify 契约规范。
 * </p>
 */
@Slf4j
@Service
public class GraphifyCompatibilityService {

    private static final Set<String> KNOWN_FIELDS = Set.of(
            "directed", "nodes", "links", "edges", "hyperedges", "built_at_commit"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 检查 JSON 内容是否符合 Graphify 契约。
     *
     * @param jsonContent JSON 字符串
     * @return 兼容性报告
     */
    public GraphifyCompatibilityReport inspect(String jsonContent) {
        List<String> warnings = new ArrayList<>();
        List<String> missingRequiredFields = new ArrayList<>();
        List<String> unsupportedFields = new ArrayList<>();

        // 1. 解析 JSON
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonContent);
        } catch (Exception e) {
            return GraphifyCompatibilityReport.builder()
                    .schemaVersion(GraphifySchemaVersion.UNKNOWN)
                    .canImport(false)
                    .missingRequiredFields(List.of("JSON 解析失败: " + e.getMessage()))
                    .warnings(warnings)
                    .build();
        }

        // 2. 检查必填字段: nodes
        int nodeCount = 0;
        JsonNode nodesNode = root.get("nodes");
        if (nodesNode == null || !nodesNode.isArray() || nodesNode.isEmpty()) {
            missingRequiredFields.add("nodes");
        } else {
            nodeCount = nodesNode.size();
        }

        // 3. 检查必填字段: links 或 edges
        int edgeCount = 0;
        JsonNode linksNode = root.get("links");
        JsonNode edgesNode = root.get("edges");
        
        boolean hasLinks = linksNode != null && linksNode.isArray() && !linksNode.isEmpty();
        boolean hasEdges = edgesNode != null && edgesNode.isArray() && !edgesNode.isEmpty();
        
        if (!hasLinks && !hasEdges) {
            missingRequiredFields.add("links 或 edges");
        } else {
            if (hasLinks) edgeCount = linksNode.size();
            if (hasEdges) edgeCount = edgesNode.size();
        }

        // 4. 检查超边（可选）
        int hyperedgeCount = 0;
        JsonNode hyperedgesNode = root.get("hyperedges");
        if (hyperedgesNode != null && hyperedgesNode.isArray()) {
            hyperedgeCount = hyperedgesNode.size();
        }

        // 5. 检查未知顶层字段
        root.fieldNames().forEachRemaining(fieldName -> {
            if (!KNOWN_FIELDS.contains(fieldName)) {
                unsupportedFields.add(fieldName);
                warnings.add("未知顶层字段: " + fieldName);
            }
        });

        // 6. 检测 schema 版本
        GraphifySchemaVersion schemaVersion = GraphifySchemaVersion.UNKNOWN;
        if (hasLinks) {
            schemaVersion = GraphifySchemaVersion.V0_9_NETWORKX_LINKS;
        } else if (hasEdges) {
            schemaVersion = GraphifySchemaVersion.V0_9_NETWORKX_EDGES;
        }

        // 7. 判断是否可导入
        boolean canImport = missingRequiredFields.isEmpty();

        // 8. 构建报告
        GraphifyCompatibilityReport report = GraphifyCompatibilityReport.builder()
                .schemaVersion(schemaVersion)
                .canImport(canImport)
                .nodeCount(nodeCount)
                .edgeCount(edgeCount)
                .hyperedgeCount(hyperedgeCount)
                .unsupportedTopLevelFields(unsupportedFields)
                .missingRequiredFields(missingRequiredFields)
                .warnings(warnings)
                .build();

        log.info("Graphify 兼容性检查完成: canImport={}, schemaVersion={}, nodes={}, edges={}, warnings={}",
                canImport, schemaVersion, nodeCount, edgeCount, warnings.size());

        return report;
    }
}
