package io.github.legacygraph.controller;

import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.eval.GraphifyQualityResult;
import io.github.legacygraph.eval.GraphifyQualityService;
import io.github.legacygraph.eval.GraphCompletenessAuditService;
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
@RequestMapping("/lg/projects/{projectId}")
@RequiredArgsConstructor
public class GraphifyController {

    private final GraphifyRunner graphifyRunner;
    private final GraphifyImportService graphifyImportService;
    private final GraphifyQualityService graphifyQualityService;
    private final GraphCompletenessAuditService completenessAuditService;

    /**
     * POST /api/lg/projects/{projectId}/graphify/analyze
     * 执行 Graphify 分析并导入结果。
     *
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @param sourceDir 源代码目录
     * @return 导入结果
     */
    @PostMapping("/graphify/analyze")
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
     * POST /api/lg/projects/{projectId}/scan-versions/{versionId}/graphify/import
     * 导入已有 Graphify graph.json。
     */
    @PostMapping("/scan-versions/{versionId}/graphify/import")
    public Result<GraphifyImportService.ImportResult> importGraph(
            @PathVariable String projectId,
            @PathVariable String versionId,
            @RequestBody ImportRequest request) {
        try {
            Path graphJsonPath = resolveGraphJsonPath(request.getProjectRoot(), request.getGraphJsonPath());
            GraphifyImportService.ImportResult importResult =
                    graphifyImportService.importGraph(projectId, versionId, graphJsonPath);
            return Result.success(importResult);
        } catch (GraphifyImportService.GraphifyImportException e) {
            log.error("Graphify 导入失败", e);
            return Result.error("Graphify 导入失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("Graphify 导入处理失败", e);
            return Result.error("Graphify 导入处理失败: " + e.getMessage());
        }
    }

    /**
     * POST /api/lg/projects/{projectId}/scan-versions/{versionId}/graphify/run
     * 执行 Graphify 分析并导入生成的 graph.json。
     */
    @PostMapping("/scan-versions/{versionId}/graphify/run")
    public Result<GraphifyImportService.ImportResult> runAndImport(
            @PathVariable String projectId,
            @PathVariable String versionId,
            @RequestBody RunRequest request) {
        return runAndImport(projectId, versionId, request.getProjectRoot());
    }

    private Result<GraphifyImportService.ImportResult> runAndImport(String projectId, String versionId, String sourceDir) {
        if (sourceDir == null || sourceDir.isBlank()) {
            return Result.error("projectRoot 不能为空");
        }

        try {
            if (!graphifyRunner.isAvailable()) {
                return Result.error("Graphify 工具不可用，请检查配置");
            }

            GraphifyRunResult runResult = graphifyRunner.run(Path.of(sourceDir));
            if (!runResult.isSuccess()) {
                String errorMsg = runResult.getStderr() != null && !runResult.getStderr().isBlank()
                        ? runResult.getStderr()
                        : "退出码: " + runResult.getExitCode();
                return Result.error("Graphify 分析失败: " + errorMsg);
            }

            Path graphJsonPath = runResult.getGraphJsonPath() != null
                    ? runResult.getGraphJsonPath()
                    : runResult.getOutputDir().resolve("graph.json");
            GraphifyImportService.ImportResult importResult =
                    graphifyImportService.importGraph(projectId, versionId, graphJsonPath);
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

    private Path resolveGraphJsonPath(String projectRoot, String graphJsonPath) {
        if (graphJsonPath == null || graphJsonPath.isBlank()) {
            throw new IllegalArgumentException("graphJsonPath 不能为空");
        }
        Path path = Path.of(graphJsonPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        if (projectRoot == null || projectRoot.isBlank()) {
            throw new IllegalArgumentException("相对 graphJsonPath 需要提供 projectRoot");
        }
        return Path.of(projectRoot).resolve(path).normalize();
    }

    /**
     * GET /api/lg/projects/{projectId}/graphify/status
     * 检查 Graphify 工具状态。
     *
     * @param projectId 项目ID
     * @return 工具状态
     */
    @GetMapping("/graphify/status")
    public Result<GraphifyStatusResponse> getStatus(@PathVariable String projectId) {
        boolean available = graphifyRunner.isAvailable();

        GraphifyStatusResponse response = GraphifyStatusResponse.builder()
                .available(available)
                .message(available ? "Graphify 工具可用" : "Graphify 工具未安装或不可用")
                .build();

        return Result.success(response);
    }

    /**
     * GET /api/lg/projects/{projectId}/graphify/quality
     * 查询 Graphify 导入质量评估（Benchmark 结果 + Release Gate 状态）。
     *
     * @param projectId 项目ID
     * @param versionId 扫描版本ID，可选；为空时取项目最新扫描版本
     * @return 质量评估结果
     */
    @GetMapping("/graphify/quality")
    public Result<GraphifyQualityResult> getQuality(
            @PathVariable String projectId,
            @RequestParam(required = false) String versionId) {
        return Result.success(graphifyQualityService.getQuality(projectId, versionId));
    }

    /**
     * GET /api/lg/projects/{projectId}/graphify/audit
     * 图谱完整性审计 — 7 项端到端质量指标。
     *
     * @param projectId 项目ID
     * @param versionId 扫描版本ID，可选；为空时取项目最新扫描版本
     * @return 审计结果（含 7 项指标）
     */
    @GetMapping("/graphify/audit")
    public Result<GraphCompletenessAuditService.AuditResult> getAudit(
            @PathVariable String projectId,
            @RequestParam(required = false) String versionId) {
        return Result.success(completenessAuditService.audit(projectId, versionId));
    }

    @lombok.Builder
    @lombok.Data
    public static class GraphifyStatusResponse {
        private final boolean available;
        private final String message;
    }

    @lombok.Data
    public static class ImportRequest {
        private String graphJsonPath;
        private String projectRoot;
    }

    @lombok.Data
    public static class RunRequest {
        private String projectRoot;
        private String postgresDsn;
        private Boolean force;
    }
}
