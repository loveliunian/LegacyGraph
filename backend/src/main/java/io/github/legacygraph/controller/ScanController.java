package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.CreateScanVersionRequest;
import io.github.legacygraph.dto.ScanProgressResponse;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.service.ScanVersionService;
import io.github.legacygraph.task.ProjectScanner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController@RequestMapping("/lg/projects/{projectId}/scan-versions")
@Tag(name = "扫描管理", description = "创建扫描版本、启动扫描、查询进度")
public class ScanController {

    private final ScanVersionService scanVersionService;
    private final ProjectScanner projectScanner;

    public ScanController(ScanVersionService scanVersionService, ProjectScanner projectScanner) {
        this.scanVersionService = scanVersionService;
        this.projectScanner = projectScanner;
    }

    @PostMapping
    @Operation(summary = "创建扫描版本")
    public Result<String> create(@PathVariable String projectId, @RequestBody CreateScanVersionRequest request) {
        try {
            ScanVersion version = scanVersionService.createScanVersion(projectId, request);
            return Result.success(version.getId());
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/{versionId}/progress")
    @Operation(summary = "查询扫描进度")
    public Result<ScanProgressResponse> progress(@PathVariable String versionId) {
        try {
            ScanProgressResponse response = scanVersionService.getScanProgress(versionId);
            return Result.success(response);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/{versionId}/start")
    @Operation(summary = "启动扫描")
    public Result<Void> start(
            @PathVariable String projectId,
            @PathVariable String versionId,
            @RequestParam(required = false) String baseDir) {
        try {
            scanVersionService.updateScanStatus(versionId, "RUNNING");
            // 异步启动完整扫描
            projectScanner.startFullScan(projectId, versionId, baseDir);
            return Result.success();
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
