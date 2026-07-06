package io.github.legacygraph.integration.graphify;

import io.github.legacygraph.builder.EvidenceGraphWriter;
import io.github.legacygraph.dto.graph.GraphWriteIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Graphify 导入服务。
 * <p>
 * 负责将 Graphify graph.json 导入到 LegacyGraph 图谱中。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphifyImportService {

    private final GraphifyGraphParser parser;
    private final GraphifyCanonicalMapper mapper;
    private final EvidenceGraphWriter writer;
    private final GraphifyCompatibilityService compatibilityService;

    /**
     * 导入 Graphify graph.json。
     *
     * @param projectId     项目 ID
     * @param versionId     版本 ID
     * @param graphJsonPath graph.json 文件路径
     * @return 导入结果
     * @throws GraphifyImportException 导入失败时抛出
     */
    public ImportResult importGraph(String projectId, String versionId, Path graphJsonPath)
            throws GraphifyImportException {
        List<String> warnings = new ArrayList<>();

        try {
            // 0. 兼容性检查
            log.info("开始兼容性检查: {}", graphJsonPath);
            String jsonContent = java.nio.file.Files.readString(graphJsonPath);
            GraphifyCompatibilityReport compatibilityReport = compatibilityService.inspect(jsonContent);
            
            if (!compatibilityReport.isCanImport()) {
                String errorMsg = String.format("兼容性检查失败: 缺失字段=%s, 不支持字段=%s",
                        compatibilityReport.getMissingRequiredFields(),
                        compatibilityReport.getUnsupportedTopLevelFields());
                log.error(errorMsg);
                throw new GraphifyImportException(errorMsg);
            }
            
            log.info("兼容性检查通过: schemaVersion={}, nodes={}, edges={}",
                    compatibilityReport.getSchemaVersion(),
                    compatibilityReport.getNodeCount(),
                    compatibilityReport.getEdgeCount());
            warnings.addAll(compatibilityReport.getWarnings());

            // 1. 解析 graph.json
            log.info("开始解析 Graphify graph.json: {}", graphJsonPath);
            GraphifyGraphParser.ParseResult parseResult = parser.parse(graphJsonPath);
            warnings.addAll(parseResult.warnings());

            GraphifyGraphJson graph = parseResult.graph();
            log.info("解析完成: {} nodes, {} edges", graph.nodes().size(), graph.resolvedEdges().size());

            // 2. 映射为规范模型
            log.info("开始映射为 LegacyGraph 规范模型");
            GraphifyCanonicalMapper.MapResult mapResult = mapper.map(graph, projectId, versionId);
            warnings.addAll(mapResult.warnings());

            GraphWriteIntent intent = mapResult.intent();
            log.info("映射完成: {} node claims, {} edge claims, {} evidence records",
                    intent.getNodeClaims().size(),
                    intent.getEdgeClaims().size(),
                    intent.getEvidenceRecords().size());

            // 3. 写入图谱
            log.info("开始写入图谱");
            writer.writeIntent(intent);
            log.info("图谱写入完成");

            // 4. 返回结果
            ImportResult result = ImportResult.builder()
                    .success(true)
                    .processedNodes(intent.getNodeClaims().size())
                    .processedEdges(intent.getEdgeClaims().size())
                    .evidenceCount(intent.getEvidenceRecords().size())
                    .warnings(warnings)
                    .build();

            log.info("Graphify 导入成功: {}", result);
            return result;

        } catch (GraphifyGraphParser.GraphifyParseException e) {
            log.error("Graphify 解析失败: {}", e.getMessage());
            throw new GraphifyImportException("解析失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Graphify 导入失败: {}", e.getMessage(), e);
            throw new GraphifyImportException("导入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 导入结果。
     */
    @lombok.Builder
    @lombok.Data
    public static class ImportResult {
        private final boolean success;
        private final int processedNodes;
        private final int processedEdges;
        private final int evidenceCount;
        private final List<String> warnings;
    }

    /**
     * Graphify 导入异常。
     */
    public static class GraphifyImportException extends Exception {
        public GraphifyImportException(String message) {
            super(message);
        }

        public GraphifyImportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
