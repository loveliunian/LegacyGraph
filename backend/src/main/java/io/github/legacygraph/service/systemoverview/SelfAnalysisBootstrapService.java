package io.github.legacygraph.service.systemoverview;

import io.github.legacygraph.integration.graphify.GraphifyImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * 自身分析引导服务 — 把 LegacyGraph 自身的 graphify 扫描结果导入系统图谱。
 * <p>
 * 落地 {@code doc/系统关系总览/04-落地实施计划.md} 阶段4：graphify 自身导入。
 * 将 graphify-out/graph.json（8573 节点）导入 Neo4j，让 QA 可检索自身知识。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfAnalysisBootstrapService {

    private final GraphifyImportService graphifyImportService;

    /**
     * 导入指定 graph.json 到系统图谱。
     */
    public GraphifyImportService.ImportResult bootstrap(String projectId, String versionId, String graphJsonPath) {
        Path path = Path.of(graphJsonPath);
        try {
            log.info("Self-analysis bootstrap: importing {} for projectId={}", path, projectId);
            GraphifyImportService.ImportResult result = graphifyImportService.importGraph(projectId, versionId, path);
            log.info("Self-analysis done: success={}, {} nodes, {} edges",
                    result.isSuccess(), result.getProcessedNodes(), result.getProcessedEdges());
            return result;
        } catch (GraphifyImportService.GraphifyImportException e) {
            log.error("Self-analysis import failed: {}", e.getMessage());
            throw new RuntimeException("自身分析导入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用默认路径 graphify-out/graph.json 导入。
     */
    public GraphifyImportService.ImportResult bootstrapDefault(String projectId, String versionId) {
        return bootstrap(projectId, versionId, "graphify-out/graph.json");
    }
}
