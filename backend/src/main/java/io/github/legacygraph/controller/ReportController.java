package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.report.ConfidenceTrendReport;
import io.github.legacygraph.dto.report.GraphMetricsReport;
import io.github.legacygraph.dto.report.GraphQualityReport;
import io.github.legacygraph.dto.report.MigrationReadinessReport;
import io.github.legacygraph.dto.report.TestCoverageReport;
import io.github.legacygraph.entity.Report;
import io.github.legacygraph.service.ReportingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 报告控制器
 * 提供各类分析报告的生成和查询
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}")
@Tag(name = "报告生成", description = "迁移就绪度、置信度趋势、测试覆盖率、图谱质量报告")
public class ReportController {

    private final ReportingService reportingService;

    public ReportController(ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    @GetMapping("/reports/list")
    @Operation(summary = "获取报告列表", description = "获取项目的所有报告记录")
    public Result<List<Report>> listReports(@PathVariable String projectId) {
        List<Report> reports = reportingService.listReports(projectId);
        return Result.success(reports);
    }

    @PostMapping("/reports/migration-readiness/generate")
    @Operation(summary = "生成迁移就绪度报告", description = "评估项目迁移准备程度，统计已确认节点和风险项")
    public Result<MigrationReadinessReport> generateMigrationReport(@PathVariable String projectId) {
        MigrationReadinessReport report = reportingService.generateMigrationReport(projectId);
        return Result.success(report);
    }

    @PostMapping("/reports/confidence-trend/generate")
    @Operation(summary = "生成置信度趋势报告", description = "查看置信度随时间变化趋势")
    public Result<ConfidenceTrendReport> generateConfidenceTrend(
            @PathVariable String projectId,
            @RequestParam String versionId) {
        ConfidenceTrendReport report = reportingService.generateConfidenceTrend(projectId, versionId);
        return Result.success(report);
    }

    @PostMapping("/reports/test-coverage/generate")
    @Operation(summary = "生成测试覆盖率报告", description = "统计哪些节点已经被测试覆盖")
    public Result<TestCoverageReport> generateTestCoverage(
            @PathVariable String projectId,
            @RequestParam String versionId) {
        TestCoverageReport report = reportingService.generateTestCoverageReport(projectId, versionId);
        return Result.success(report);
    }

    @PostMapping("/reports/graph-quality/generate")
    @Operation(summary = "生成图谱质量报告", description = "评估图谱整体质量，识别问题节点")
    public Result<GraphQualityReport> generateGraphQuality(
            @PathVariable String projectId,
            @RequestParam String versionId) {
        GraphQualityReport report = reportingService.generateGraphQualityReport(projectId, versionId);
        return Result.success(report);
    }

    @GetMapping("/reports/graph-metrics")
    @Operation(summary = "获取图谱质量度量汇总",
            description = "一次性返回覆盖率、证据完备度、待审核比例、测试通过率、运行时验证比例")
    public Result<GraphMetricsReport> getGraphMetrics(
            @PathVariable String projectId,
            @RequestParam String versionId) {
        GraphMetricsReport report = reportingService.generateGraphMetrics(projectId, versionId);
        return Result.success(report);
    }

    @GetMapping("/reports/{reportId}/download")
    @Operation(summary = "下载报告", description = "下载报告文件，支持MD/PDF/Excel/JSON格式")
    public org.springframework.http.ResponseEntity<byte[]> downloadReport(
            @PathVariable String projectId,
            @PathVariable String reportId,
            @RequestParam(defaultValue = "MD") String format) {
        try {
            byte[] data = reportingService.exportReport(reportId, format);

            String contentType;
            String extension;
            switch (format.toUpperCase()) {
                case "PDF":
                    contentType = "application/pdf";
                    extension = "pdf";
                    break;
                case "EXCEL":
                case "XLSX":
                    contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                    extension = "xlsx";
                    break;
                case "JSON":
                    contentType = "application/json";
                    extension = "json";
                    break;
                default:
                    contentType = "text/markdown";
                    extension = "md";
            }

            return org.springframework.http.ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=report-" + reportId + "." + extension)
                    .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                    .body(data);
        } catch (Exception e) {
            log.error("报告下载失败", e);
            return org.springframework.http.ResponseEntity.internalServerError().build();
        }
    }
}
