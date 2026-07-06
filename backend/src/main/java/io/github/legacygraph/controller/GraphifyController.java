package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.integration.graphify.GraphifyImportService;
import io.github.legacygraph.integration.graphify.GraphifyRunner;
import io.github.legacygraph.integration.graphify.GraphifyRunResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;

/**
 * Graphify 集成控制器。
 * <p>
 * 提供手动触发 Graphify 分析和导入的 API。
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/graphify")
@RequiredArgsConstructor
public class GraphifyController {

    private final GraphifyRunner graphifyRunner;
    private final GraphifyImportService graphifyImportService;

    /**
     * POST /api/lg/projects/{projectId}/graphify/analyze
     * 执行 Graphify 分析并导入结果。
     *
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @param sourceDir 源代码目录
     * @return 导入结果
     */
    @PostMapping("/analyze")
    public Result<GraphifyImportService.ImportResult> analyzeAndImport(
            @PathVariable String projectId,
            @RequestParam String versionId,
            @RequestParam String sourceDir) {

        log.info("手动触发 Graphify 分析: projectId={}, versionId={}, sourceDir={}",
                projectId, versionId, sourceDir);

        try {
            // 1. 检查 Graphify 是否可用
            if (!graphifyRunner.isAvailable()) {
                return Result.error("Graphify 工具不可用，请检查配置");
            }

            // 2. 执行 Graphify 分析
            Path sourcePath = Path.of(sourceDir);
            GraphifyRunResult runResult = graphifyRunner.run(sourcePath);

            if (!runResult.isSuccess()) {
                String errorMsg = runResult.getStderr() != null && !runResult.getStderr().isBlank()
                        ? runResult.getStderr()
                        : "退出码: " + runResult.getExitCode();
                log.error("Graphify 分析失败: {}", errorMsg);
                return Result.error("Graphify 分析失败: " + errorMsg);
            }

            // 3. 导入结果
            Path graphJsonPath = runResult.getOutputDir().resolve("graph.json");
            GraphifyImportService.ImportResult importResult =
                    graphifyImportService.importGraph(projectId, versionId, graphJsonPath);

            log.info("Graphify 分析并导入成功: {}", importResult);
            return Result.success(importResult);

        } catch (GraphifyRunner.GraphifyRunException e) {
            log.error("Graphify 运行失败", e);
            return Result.error("Graphify 运行失败: " + e.getMessage());
        } catch (GraphifyImportService.GraphifyImportException e) {
            log.error("Graphify 导入失败", e);
            return Result.error("Graphify 导入失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("Graphify 处理失败", e);
            return Result.error("Graphify 处理失败: " + e.getMessage());
        }
    }

    /**
     * GET /api/lg/projects/{projectId}/graphify/status
     * 检查 Graphify 工具状态。
     *
     * @param projectId 项目ID
     * @return 工具状态
     */
    @GetMapping("/status")
    public Result<GraphifyStatusResponse> getStatus(@PathVariable String projectId) {
        boolean available = graphifyRunner.isAvailable();

        GraphifyStatusResponse response = GraphifyStatusResponse.builder()
                .available(available)
                .message(available ? "Graphify 工具可用" : "Graphify 工具未安装或不可用")
                .build();

        return Result.success(response);
    }

    @lombok.Builder
    @lombok.Data
    public static class GraphifyStatusResponse {
        private final boolean available;
        private final String message;
    }
}
