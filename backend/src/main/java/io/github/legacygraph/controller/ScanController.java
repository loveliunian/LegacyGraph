package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.CreateScanVersionRequest;
import io.github.legacygraph.dto.ScanProgressResponse;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.service.report.ScanPerformanceReportService;
import io.github.legacygraph.service.scan.ScanVersionService;
import io.github.legacygraph.task.ProjectScanner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 扫描管理控制器
 * 管理知识图谱扫描任务，支持创建扫描版本、启动/暂停/取消/恢复扫描，查询扫描进度
 * 一个项目可以有多个扫描版本，每次扫描生成一个版本，保存不同时间点的知识图谱快照
 */
@RestController
@RequestMapping("/lg/projects/{projectId}/scan-versions")
@Tag(name = "扫描管理", description = "创建扫描版本、启动扫描、暂停/取消/恢复扫描、查询扫描进度")
public class ScanController {

    private final ScanVersionService scanVersionService;
    private final ProjectScanner projectScanner;
    private final ScanPerformanceReportService scanPerformanceReportService;
    private final io.github.legacygraph.tenant.TenantQuotaManager quotaManager;

    /**
     * 构造函数注入
     * @param scanVersionService 扫描版本服务
     * @param projectScanner 项目扫描器
     */
    public ScanController(ScanVersionService scanVersionService, ProjectScanner projectScanner,
                          ScanPerformanceReportService scanPerformanceReportService,
                          io.github.legacygraph.tenant.TenantQuotaManager quotaManager) {
        this.scanVersionService = scanVersionService;
        this.projectScanner = projectScanner;
        this.scanPerformanceReportService = scanPerformanceReportService;
        this.quotaManager = quotaManager;
    }

    /**
     * 创建扫描版本
     * 在指定项目下创建一个新的扫描版本，用于后续知识图谱扫描
     * @param projectId 项目ID
     * @param request 创建扫描版本请求
     * @return 新创建的扫描版本ID
     */
    @PostMapping
    @Operation(summary = "创建扫描版本", description = "在指定项目下创建一个新的扫描版本，用于后续知识图谱扫描")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "创建成功，返回扫描版本ID"),
            @ApiResponse(responseCode = "400", description = "创建失败")
    })
    public Result<String> create(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "创建扫描版本请求", required = true)
            @RequestBody CreateScanVersionRequest request) {
        // 配额检查：扫描版本数
        if (!quotaManager.checkQuota(projectId, io.github.legacygraph.tenant.TenantQuotaManager.QuotaType.SCAN_VERSIONS, 1)) {
            return Result.error("扫描版本数已达配额上限，请清理历史版本后再试");
        }
        ScanVersion version = scanVersionService.createScanVersion(projectId, request);
        quotaManager.incrementUsage(projectId, io.github.legacygraph.tenant.TenantQuotaManager.QuotaType.SCAN_VERSIONS, 1);
        return Result.success(version.getId());
    }

    /**
     * 查询扫描进度
     * 获取当前扫描任务的进度信息，包括已处理数量、总数量、当前状态
     * @param versionId 扫描版本ID
     * @return 扫描进度响应
     */
    @GetMapping("/{versionId}/progress")
    @Operation(summary = "查询扫描进度", description = "获取指定扫描版本的当前扫描进度和状态信息")
    public Result<ScanProgressResponse> progress(
            @Parameter(description = "扫描版本ID", required = true)
            @PathVariable String versionId) {
        ScanProgressResponse response = scanVersionService.getScanProgress(versionId);
        return Result.success(response);
    }

    /**
     * 启动扫描
     * 异步启动指定扫描版本的完整知识图谱扫描，包括代码解析和数据库结构分析
     * @param projectId 项目ID
     * @param versionId 扫描版本ID
     * @param baseDir 本地代码基础目录（可选，用于本地代码扫描）
     * @return 成功结果
     */
    @PostMapping("/{versionId}/start")
    @Operation(summary = "启动扫描", description = "异步启动指定扫描版本的完整知识图谱扫描")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "启动成功"),
            @ApiResponse(responseCode = "400", description = "启动失败")
    })
    public Result<Void> start(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "扫描版本ID", required = true)
            @PathVariable String versionId,
            @Parameter(description = "本地代码基础目录，可选，用于本地代码扫描")
            @RequestParam(required = false) String baseDir) {
        scanVersionService.updateScanStatus(versionId, "RUNNING");
        // 异步启动完整扫描
        projectScanner.startFullScan(projectId, versionId, baseDir);
        return Result.success();
    }

    /**
     * 暂停扫描
     * 暂停正在进行的扫描任务，可以后续从暂停处恢复继续执行
     * @param projectId 项目ID
     * @param versionId 扫描版本ID
     * @return 成功结果
     */
    @PostMapping("/{versionId}/pause")
    @Operation(summary = "暂停扫描", description = "暂停正在进行的扫描任务，可以后续从暂停处恢复")
    public Result<Void> pause(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "扫描版本ID", required = true)
            @PathVariable String versionId) {
        projectScanner.requestCancel(versionId);
        scanVersionService.updateScanStatus(versionId, "PAUSED");
        return Result.success();
    }

    /**
     * 取消扫描
     * 取消正在进行的扫描任务，将状态标记为已取消，无法恢复
     * @param projectId 项目ID
     * @param versionId 扫描版本ID
     * @return 成功结果
     */
    @PostMapping("/{versionId}/cancel")
    @Operation(summary = "取消扫描", description = "取消正在进行的扫描任务，标记为已取消，无法恢复")
    public Result<Void> cancel(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "扫描版本ID", required = true)
            @PathVariable String versionId) {
        projectScanner.requestCancel(versionId);
        scanVersionService.updateScanStatus(versionId, "CANCELLED");
        return Result.success();
    }

    /**
     * 恢复暂停的扫描
     * 从暂停处恢复继续执行扫描任务
     * @param projectId 项目ID
     * @param versionId 扫描版本ID
     * @param baseDir 本地代码基础目录（可选）
     * @return 成功结果
     */
    @PostMapping("/{versionId}/resume")
    @Operation(summary = "恢复暂停的扫描", description = "从暂停处恢复继续执行已暂停的扫描任务")
    public Result<Void> resume(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "扫描版本ID", required = true)
            @PathVariable String versionId,
            @Parameter(description = "本地代码基础目录，可选")
            @RequestParam(required = false) String baseDir) {
        scanVersionService.updateScanStatus(versionId, "RUNNING");
        projectScanner.resumeFullScan(projectId, versionId, baseDir);
        return Result.success();
    }

    /**
     * 删除扫描版本
     * 删除指定的扫描版本及其关联的所有扫描任务数据
     * @param projectId 项目ID
     * @param versionId 扫描版本ID
     * @return 成功结果
     */
    @DeleteMapping("/{versionId}")
    @Operation(summary = "删除扫描版本", description = "删除指定的扫描版本及其关联的所有扫描任务数据")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "扫描版本不存在")
    })
    public Result<Void> deleteVersion(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "扫描版本ID", required = true)
            @PathVariable String versionId) {
        projectScanner.requestCancel(versionId);
        scanVersionService.deleteScanVersion(versionId);
        return Result.success();
    }

    /**
     * 获取扫描日志
     * 返回指定扫描版本的所有扫描任务日志记录
     */
    @GetMapping("/{versionId}/logs")
    @Operation(summary = "获取扫描日志", description = "获取指定扫描版本的所有扫描任务日志记录")
    public Result<List<Map<String, Object>>> getLogs(
            @PathVariable String projectId,
            @PathVariable String versionId) {
        return Result.success(scanVersionService.getScanLogs(versionId));
    }

    /**
     * 获取扫描性能报告
     * 返回 Markdown 格式的扫描性能分析报告
     */
    @GetMapping("/{versionId}/performance-report")
    @Operation(summary = "获取扫描性能报告", description = "返回 Markdown 格式的扫描性能分析报告，包含各阶段耗时和汇总")
    public Result<String> performanceReport(
            @PathVariable String projectId,
            @PathVariable String versionId) {
        return Result.success(scanPerformanceReportService.generateMarkdown(projectId, versionId));
    }
}
