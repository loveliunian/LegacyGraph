package io.github.legacygraph.controller;

import io.github.legacygraph.service.ReportExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 报告导出控制器
 * 提供 MD、PDF、Excel 三种格式的报告导出
 */
@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportExportController {

    private final ReportExportService reportExportService;

    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * 导出迁移就绪度报告
     */
    @GetMapping("/migration/{projectId}")
    public ResponseEntity<byte[]> exportMigrationReport(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "MD") String format) {

        ReportExportService.ExportFormat exportFormat = parseFormat(format);
        ReportExportService.ReportType reportType = ReportExportService.ReportType.MIGRATION_READINESS;

        byte[] data = reportExportService.exportReport(projectId, null, reportType, exportFormat);
        return buildResponse(data, "迁移就绪度报告", exportFormat);
    }

    /**
     * 导出置信度趋势报告
     */
    @GetMapping("/confidence/{projectId}/{versionId}")
    public ResponseEntity<byte[]> exportConfidenceReport(
            @PathVariable String projectId,
            @PathVariable String versionId,
            @RequestParam(defaultValue = "MD") String format) {

        ReportExportService.ExportFormat exportFormat = parseFormat(format);
        ReportExportService.ReportType reportType = ReportExportService.ReportType.CONFIDENCE_TREND;

        byte[] data = reportExportService.exportReport(projectId, versionId, reportType, exportFormat);
        return buildResponse(data, "置信度趋势报告", exportFormat);
    }

    /**
     * 导出测试覆盖率报告
     */
    @GetMapping("/test-coverage/{projectId}/{versionId}")
    public ResponseEntity<byte[]> exportTestCoverageReport(
            @PathVariable String projectId,
            @PathVariable String versionId,
            @RequestParam(defaultValue = "MD") String format) {

        ReportExportService.ExportFormat exportFormat = parseFormat(format);
        ReportExportService.ReportType reportType = ReportExportService.ReportType.TEST_COVERAGE;

        byte[] data = reportExportService.exportReport(projectId, versionId, reportType, exportFormat);
        return buildResponse(data, "测试覆盖率报告", exportFormat);
    }

    /**
     * 导出图谱质量报告
     */
    @GetMapping("/graph-quality/{projectId}/{versionId}")
    public ResponseEntity<byte[]> exportGraphQualityReport(
            @PathVariable String projectId,
            @PathVariable String versionId,
            @RequestParam(defaultValue = "MD") String format) {

        ReportExportService.ExportFormat exportFormat = parseFormat(format);
        ReportExportService.ReportType reportType = ReportExportService.ReportType.GRAPH_QUALITY;

        byte[] data = reportExportService.exportReport(projectId, versionId, reportType, exportFormat);
        return buildResponse(data, "图谱质量报告", exportFormat);
    }

    /**
     * 支持的报告格式列表
     */
    @GetMapping("/formats")
    public ResponseEntity<java.util.Map<String, Object>> getSupportedFormats() {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("formats", java.util.Arrays.stream(ReportExportService.ExportFormat.values())
                .map(Enum::name)
                .toList());
        result.put("reportTypes", java.util.Arrays.stream(ReportExportService.ReportType.values())
                .map(rt -> new java.util.HashMap<String, String>() {{
                    put("code", rt.name());
                    put("name", rt.getDisplayName());
                }})
                .toList());
        return ResponseEntity.ok(result);
    }

    private ReportExportService.ExportFormat parseFormat(String format) {
        try {
            return ReportExportService.ExportFormat.valueOf(format.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid export format: {}, defaulting to MD", format);
            return ReportExportService.ExportFormat.MD;
        }
    }

    private ResponseEntity<byte[]> buildResponse(byte[] data, String reportName, ReportExportService.ExportFormat format) {
        String fileExtension = switch (format) {
            case MD -> "md";
            case PDF -> "pdf";
            case EXCEL -> "xlsx";
        };

        String fileName = String.format("%s_%s.%s", reportName,
                LocalDateTime.now().format(FILE_DATE_FORMAT), fileExtension);

        MediaType mediaType = switch (format) {
            case MD -> MediaType.TEXT_MARKDOWN;
            case PDF -> MediaType.APPLICATION_PDF;
            case EXCEL -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        };

        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        String.format("attachment; filename=\"%s\"; filename*=UTF-8''%s",
                                fileName, encodedFileName))
                .header(HttpHeaders.CONTENT_TYPE, mediaType.toString())
                .contentLength(data.length)
                .body(data);
    }
}
