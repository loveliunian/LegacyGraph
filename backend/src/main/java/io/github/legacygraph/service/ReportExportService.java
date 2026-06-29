package io.github.legacygraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import io.github.legacygraph.dto.report.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 报告导出服务
 * 支持 Markdown、PDF、Excel 三种格式导出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportExportService {

    private final ObjectMapper objectMapper;
    private final ReportingService reportingService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 导出报告类型枚举
     */
    public enum ReportType {
        MIGRATION_READINESS("迁移就绪度报告"),
        CONFIDENCE_TREND("置信度趋势报告"),
        TEST_COVERAGE("测试覆盖率报告"),
        GRAPH_QUALITY("图谱质量报告");

        private final String displayName;

        ReportType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * 导出格式枚举
     */
    public enum ExportFormat {
        MD, PDF, EXCEL
    }

    /**
     * 通用导出方法
     */
    public byte[] exportReport(String projectId, String versionId, ReportType reportType, ExportFormat format) {
        log.info("Exporting report: projectId={}, versionId={}, reportType={}, format={}",
                projectId, versionId, reportType, format);

        return switch (format) {
            case MD -> exportToMarkdown(projectId, versionId, reportType);
            case PDF -> exportToPdf(projectId, versionId, reportType);
            case EXCEL -> exportToExcel(projectId, versionId, reportType);
        };
    }

    /**
     * 导出为 Markdown 格式
     */
    public byte[] exportToMarkdown(String projectId, String versionId, ReportType reportType) {
        String markdown = switch (reportType) {
            case MIGRATION_READINESS -> generateMigrationMarkdown(projectId);
            case CONFIDENCE_TREND -> generateConfidenceTrendMarkdown(projectId, versionId);
            case TEST_COVERAGE -> generateTestCoverageMarkdown(projectId, versionId);
            case GRAPH_QUALITY -> generateGraphQualityMarkdown(projectId, versionId);
        };
        return markdown.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 导出为 PDF 格式
     */
    public byte[] exportToPdf(String projectId, String versionId, ReportType reportType) {
        String markdown = switch (reportType) {
            case MIGRATION_READINESS -> generateMigrationMarkdown(projectId);
            case CONFIDENCE_TREND -> generateConfidenceTrendMarkdown(projectId, versionId);
            case TEST_COVERAGE -> generateTestCoverageMarkdown(projectId, versionId);
            case GRAPH_QUALITY -> generateGraphQualityMarkdown(projectId, versionId);
        };

        String html = convertMarkdownToHtml(markdown, reportType.getDisplayName());
        return convertHtmlToPdf(html);
    }

    /**
     * 导出为 Excel 格式
     */
    public byte[] exportToExcel(String projectId, String versionId, ReportType reportType) {
        try (Workbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle dataStyle = createDataStyle(workbook);

            switch (reportType) {
                case MIGRATION_READINESS -> createMigrationExcel(workbook, projectId, headerStyle, dataStyle);
                case CONFIDENCE_TREND -> createConfidenceTrendExcel(workbook, projectId, versionId, headerStyle, dataStyle);
                case TEST_COVERAGE -> createTestCoverageExcel(workbook, projectId, versionId, headerStyle, dataStyle);
                case GRAPH_QUALITY -> createGraphQualityExcel(workbook, projectId, versionId, headerStyle, dataStyle);
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            log.error("Failed to export Excel report", e);
            throw new RuntimeException("Excel导出失败", e);
        }
    }

    /**
     * 生成迁移就绪度报告 Markdown
     */
    private String generateMigrationMarkdown(String projectId) {
        MigrationReadinessReport report = reportingService.generateMigrationReport(projectId);
        StringBuilder sb = new StringBuilder();

        sb.append("# 迁移就绪度报告\n\n");
        sb.append(String.format("**项目ID:** %s\n", projectId));
        sb.append(String.format("**生成时间:** %s\n\n", LocalDateTime.now().format(DATE_FORMATTER)));

        sb.append("## 📊 总体评分\n\n");
        sb.append(String.format("| 指标 | 分值 |\n"));
        sb.append(String.format("|------|------|\n"));
        sb.append(String.format("| **整体得分** | %.2f / 100 |\n", report.getOverallScore()));
        sb.append(String.format("| 架构理解得分 | %.2f / 100 |\n", report.getArchitectureUnderstandingScore()));
        sb.append(String.format("| 业务知识得分 | %.2f / 100 |\n", report.getBusinessKnowledgeScore()));
        sb.append(String.format("| 测试覆盖得分 | %.2f / 100 |\n", report.getTestCoverageScore()));
        sb.append(String.format("| 平均置信度 | %.2f %% |\n", report.getConfidenceLevel()));
        sb.append("\n");

        sb.append("## 📈 节点统计\n\n");
        sb.append(String.format("| 类别 | 总数 | 已确认 | 待确认 |\n"));
        sb.append(String.format("|------|------|--------|--------|\n"));
        sb.append(String.format("| 节点 | %d | %d | %d |\n",
                report.getTotalNodes(), report.getConfirmedNodes(), report.getPendingNodes()));
        sb.append(String.format("| 关系 | %d | %d | %d |\n",
                report.getTotalEdges(), report.getConfirmedEdges(), report.getPendingEdges()));
        sb.append("\n");

        sb.append("## 📋 节点类型统计\n\n");
        sb.append(String.format("| 节点类型 | 显示名称 | 总数 | 已确认 | 平均置信度 |\n"));
        sb.append(String.format("|----------|----------|------|--------|----------|\n"));
        for (MigrationReadinessReport.NodeTypeStat stat : report.getNodeTypeStats()) {
            sb.append(String.format("| %s | %s | %d | %d | %.2f %% |\n",
                    stat.getNodeType(), stat.getDisplayName(), stat.getTotal(),
                    stat.getConfirmed(), stat.getAverageConfidence().multiply(BigDecimal.valueOf(100))));
        }
        sb.append("\n");

        sb.append("## ⚠️ 风险项\n\n");
        if (report.getRiskItems().isEmpty()) {
            sb.append("✅ 当前无风险项\n\n");
        } else {
            sb.append(String.format("| 风险类型 | 描述 | 影响节点 | 风险等级 |\n"));
            sb.append(String.format("|----------|------|----------|--------|\n"));
            for (MigrationReadinessReport.RiskItem risk : report.getRiskItems()) {
                sb.append(String.format("| %s | %s | %s | %.2f %% |\n",
                        risk.getRiskType(), risk.getDescription(),
                        risk.getAffectedNodeName(), risk.getRiskLevel().multiply(BigDecimal.valueOf(100))));
            }
        }
        sb.append("\n");

        sb.append("## 💡 建议\n\n");
        if (report.getRecommendations().isEmpty()) {
            sb.append("✅ 当前系统状态良好，无特殊建议\n\n");
        } else {
            for (String rec : report.getRecommendations()) {
                sb.append(String.format("- %s\n", rec));
            }
        }
        sb.append("\n");

        sb.append("---\n");
        sb.append("*由 LegacyGraph 自动生成*");

        return sb.toString();
    }

    /**
     * 生成置信度趋势报告 Markdown
     */
    private String generateConfidenceTrendMarkdown(String projectId, String versionId) {
        ConfidenceTrendReport report = reportingService.generateConfidenceTrend(projectId, versionId);
        StringBuilder sb = new StringBuilder();

        sb.append("# 置信度趋势报告\n\n");
        sb.append(String.format("**项目ID:** %s\n", projectId));
        sb.append(String.format("**版本ID:** %s\n", versionId));
        sb.append(String.format("**生成时间:** %s\n\n", LocalDateTime.now().format(DATE_FORMATTER)));

        sb.append("## 📊 趋势概览\n\n");
        String trendEmoji = switch (report.getTrendDirection()) {
            case "UP" -> "📈";
            case "DOWN" -> "📉";
            default -> "➡️";
        };
        sb.append(String.format("**趋势方向:** %s %s\n\n", trendEmoji, report.getTrendDirection()));
        sb.append(String.format("| 指标 | 值 |\n"));
        sb.append(String.format("|------|----|\n"));
        sb.append(String.format("| 起始平均置信度 | %.2f %% |\n", report.getStartingAverageConfidence().multiply(BigDecimal.valueOf(100))));
        sb.append(String.format("| 当前平均置信度 | %.2f %% |\n", report.getEndingAverageConfidence().multiply(BigDecimal.valueOf(100))));
        sb.append(String.format("| 总体改进 | %.2f %% |\n", report.getTotalImprovement().multiply(BigDecimal.valueOf(100))));
        sb.append("\n");

        sb.append("## 📅 每日数据\n\n");
        sb.append(String.format("| 日期 | 平均置信度 | 已确认节点数 | 新增节点数 |\n"));
        sb.append(String.format("|------|----------|------------|----------|\n"));
        for (ConfidenceTrendReport.DailyData data : report.getDailyData()) {
            sb.append(String.format("| %s | %.2f %% | %d | %d |\n",
                    data.getDate(), data.getAverageConfidence().multiply(BigDecimal.valueOf(100)),
                    data.getConfirmedNodes(), data.getNewNodes()));
        }
        sb.append("\n");

        sb.append("---\n");
        sb.append("*由 LegacyGraph 自动生成*");

        return sb.toString();
    }

    /**
     * 生成测试覆盖率报告 Markdown
     */
    private String generateTestCoverageMarkdown(String projectId, String versionId) {
        TestCoverageReport report = reportingService.generateTestCoverageReport(projectId, versionId);
        StringBuilder sb = new StringBuilder();

        sb.append("# 测试覆盖率报告\n\n");
        sb.append(String.format("**项目ID:** %s\n", projectId));
        sb.append(String.format("**版本ID:** %s\n", versionId));
        sb.append(String.format("**生成时间:** %s\n\n", LocalDateTime.now().format(DATE_FORMATTER)));

        sb.append("## 📊 覆盖统计\n\n");
        sb.append(String.format("| 维度 | 总数 | 已覆盖 | 覆盖率 |\n"));
        sb.append(String.format("|------|------|--------|-------|\n"));
        sb.append(String.format("| 节点 | %d | %d | %.2f %% |\n",
                report.getTotalNodes(), report.getCoveredNodes(), report.getCoveragePercentage()));
        sb.append(String.format("| 关系 | %d | %d | %.2f %% |\n",
                report.getTotalEdges(), report.getCoveredEdges(), report.getEdgeCoveragePercentage()));
        sb.append("\n");

        sb.append("## ⚠️ 高置信度待覆盖节点\n\n");
        if (report.getHighConfidenceUncovered().isEmpty()) {
            sb.append("✅ 所有高置信度节点都已被测试覆盖\n\n");
        } else {
            sb.append(String.format("| 节点名称 | 节点类型 | 置信度 |\n"));
            sb.append(String.format("|----------|----------|--------|\n"));
            for (TestCoverageReport.UncoveredItem item : report.getHighConfidenceUncovered()) {
                sb.append(String.format("| %s | %s | %.2f %% |\n",
                        item.getNodeName(), item.getNodeType(), item.getConfidence().multiply(BigDecimal.valueOf(100))));
            }
        }
        sb.append("\n");

        sb.append("---\n");
        sb.append("*由 LegacyGraph 自动生成*");

        return sb.toString();
    }

    /**
     * 生成图谱质量报告 Markdown
     */
    private String generateGraphQualityMarkdown(String projectId, String versionId) {
        GraphQualityReport report = reportingService.generateGraphQualityReport(projectId, versionId);
        StringBuilder sb = new StringBuilder();

        sb.append("# 图谱质量报告\n\n");
        sb.append(String.format("**项目ID:** %s\n", projectId));
        sb.append(String.format("**版本ID:** %s\n", versionId));
        sb.append(String.format("**生成时间:** %s\n\n", LocalDateTime.now().format(DATE_FORMATTER)));

        sb.append("## 📊 质量统计\n\n");
        sb.append(String.format("| 指标 | 值 |\n"));
        sb.append(String.format("|------|----|\n"));
        sb.append(String.format("| 节点总数 | %d |\n", report.getTotalNodes()));
        sb.append(String.format("| 关系总数 | %d |\n", report.getTotalEdges()));
        sb.append(String.format("| 不连通组件数 | %d |\n", report.getDisconnectedComponents()));
        sb.append(String.format("| 平均节点度数 | %.2f |\n", report.getAverageNodeDegree()));
        sb.append(String.format("| 平均置信度 | %.2f %% |\n", report.getAverageConfidence().multiply(BigDecimal.valueOf(100))));
        sb.append(String.format("| 图密度 | %.2f %% |\n", report.getDensity().multiply(BigDecimal.valueOf(100))));
        sb.append(String.format("| 重复候选占比 | %.2f %% |\n", report.getDuplicateCandidateRatio().multiply(BigDecimal.valueOf(100))));
        sb.append("\n");

        sb.append("## ⚠️ 质量问题\n\n");
        if (report.getQualityIssues().isEmpty()) {
            sb.append("✅ 当前无严重质量问题\n\n");
        } else {
            sb.append(String.format("| 问题类型 | 节点名称 | 描述 | 影响 |\n"));
            sb.append(String.format("|----------|----------|------|------|\n"));
            for (GraphQualityReport.QualityIssue issue : report.getQualityIssues()) {
                sb.append(String.format("| %s | %s | %s | %.2f |\n",
                        issue.getIssueType(), issue.getNodeName(), issue.getDescription(), issue.getImpact().multiply(BigDecimal.valueOf(100))));
            }
        }
        sb.append("\n");

        sb.append("---\n");
        sb.append("*由 LegacyGraph 自动生成*");

        return sb.toString();
    }

    /**
     * Markdown 转 HTML
     */
    private String convertMarkdownToHtml(String markdown, String title) {
        MutableDataSet options = new MutableDataSet();
        com.vladsch.flexmark.util.ast.Node document = Parser.builder(options).build().parse(markdown);
        String htmlContent = HtmlRenderer.builder(options).build().render(document);

        return String.format("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>%s</title>
                    <style>
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                            margin: 40px;
                            line-height: 1.6;
                            color: #333;
                        }
                        h1 { color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
                        h2 { color: #34495e; margin-top: 30px; }
                        table { width: 100%%; border-collapse: collapse; margin: 20px 0; }
                        th, td { border: 1px solid #ddd; padding: 12px; text-align: left; }
                        th { background-color: #3498db; color: white; }
                        tr:nth-child(even) { background-color: #f8f9fa; }
                        tr:hover { background-color: #e8f4f8; }
                        code { background-color: #f8f9fa; padding: 2px 6px; border-radius: 4px; }
                        .footer { margin-top: 40px; padding-top: 20px; border-top: 1px solid #ddd; color: #999; font-size: 0.9em; }
                    </style>
                </head>
                <body>
                    %s
                    <div class="footer">
                        <p>由 LegacyGraph 自动生成</p>
                    </div>
                </body>
                </html>
                """, title, htmlContent);
    }

    /**
     * HTML 转 PDF
     */
    private byte[] convertHtmlToPdf(String html) {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        } catch (Exception e) {
            log.error("Failed to convert HTML to PDF", e);
            throw new RuntimeException("PDF导出失败", e);
        }
    }

    /**
     * 创建迁移就绪度 Excel 报告
     */
    private void createMigrationExcel(Workbook workbook, String projectId, CellStyle headerStyle, CellStyle dataStyle) {
        MigrationReadinessReport report = reportingService.generateMigrationReport(projectId);

        Sheet sheet = workbook.createSheet("迁移就绪度报告");
        int rowNum = 0;

        Row headerRow = sheet.createRow(rowNum++);
        createCell(headerRow, 0, "迁移就绪度报告", headerStyle);

        Row infoRow = sheet.createRow(rowNum++);
        createCell(infoRow, 0, "项目ID", headerStyle);
        createCell(infoRow, 1, projectId, dataStyle);

        rowNum++;
        Row scoreTitleRow = sheet.createRow(rowNum++);
        createCell(scoreTitleRow, 0, "总体评分", headerStyle);

        String[] scoreHeaders = {"整体得分", "架构理解得分", "业务知识得分", "测试覆盖得分", "平均置信度"};
        Row scoreHeaderRow = sheet.createRow(rowNum++);
        for (int i = 0; i < scoreHeaders.length; i++) {
            createCell(scoreHeaderRow, i, scoreHeaders[i], headerStyle);
        }

        Row scoreDataRow = sheet.createRow(rowNum++);
        createCell(scoreDataRow, 0, String.format("%.2f", report.getOverallScore()), dataStyle);
        createCell(scoreDataRow, 1, String.format("%.2f", report.getArchitectureUnderstandingScore()), dataStyle);
        createCell(scoreDataRow, 2, String.format("%.2f", report.getBusinessKnowledgeScore()), dataStyle);
        createCell(scoreDataRow, 3, String.format("%.2f", report.getTestCoverageScore()), dataStyle);
        createCell(scoreDataRow, 4, String.format("%.2f", report.getConfidenceLevel()), dataStyle);

        rowNum++;
        Row nodeStatsTitleRow = sheet.createRow(rowNum++);
        createCell(nodeStatsTitleRow, 0, "节点统计", headerStyle);

        String[] nodeHeaders = {"类别", "总数", "已确认", "待确认"};
        Row nodeHeaderRow = sheet.createRow(rowNum++);
        for (int i = 0; i < nodeHeaders.length; i++) {
            createCell(nodeHeaderRow, i, nodeHeaders[i], headerStyle);
        }

        Row nodeDataRow = sheet.createRow(rowNum++);
        createCell(nodeDataRow, 0, "节点", dataStyle);
        createCell(nodeDataRow, 1, String.valueOf(report.getTotalNodes()), dataStyle);
        createCell(nodeDataRow, 2, String.valueOf(report.getConfirmedNodes()), dataStyle);
        createCell(nodeDataRow, 3, String.valueOf(report.getPendingNodes()), dataStyle);

        Row edgeDataRow = sheet.createRow(rowNum++);
        createCell(edgeDataRow, 0, "关系", dataStyle);
        createCell(edgeDataRow, 1, String.valueOf(report.getTotalEdges()), dataStyle);
        createCell(edgeDataRow, 2, String.valueOf(report.getConfirmedEdges()), dataStyle);
        createCell(edgeDataRow, 3, String.valueOf(report.getPendingEdges()), dataStyle);

        rowNum++;
        Row typeStatsTitleRow = sheet.createRow(rowNum++);
        createCell(typeStatsTitleRow, 0, "节点类型统计", headerStyle);

        String[] typeHeaders = {"节点类型", "显示名称", "总数", "已确认", "平均置信度"};
        Row typeHeaderRow = sheet.createRow(rowNum++);
        for (int i = 0; i < typeHeaders.length; i++) {
            createCell(typeHeaderRow, i, typeHeaders[i], headerStyle);
        }

        for (MigrationReadinessReport.NodeTypeStat stat : report.getNodeTypeStats()) {
            Row row = sheet.createRow(rowNum++);
            createCell(row, 0, stat.getNodeType(), dataStyle);
            createCell(row, 1, stat.getDisplayName(), dataStyle);
            createCell(row, 2, String.valueOf(stat.getTotal()), dataStyle);
            createCell(row, 3, String.valueOf(stat.getConfirmed()), dataStyle);
            createCell(row, 4, String.format("%.2f", stat.getAverageConfidence().multiply(BigDecimal.valueOf(100))), dataStyle);
        }

        rowNum++;
        Row riskTitleRow = sheet.createRow(rowNum++);
        createCell(riskTitleRow, 0, "风险项", headerStyle);

        if (!report.getRiskItems().isEmpty()) {
            String[] riskHeaders = {"风险类型", "描述", "影响节点", "风险等级"};
            Row riskHeaderRow = sheet.createRow(rowNum++);
            for (int i = 0; i < riskHeaders.length; i++) {
                createCell(riskHeaderRow, i, riskHeaders[i], headerStyle);
            }

            for (MigrationReadinessReport.RiskItem risk : report.getRiskItems()) {
                Row row = sheet.createRow(rowNum++);
                createCell(row, 0, risk.getRiskType(), dataStyle);
                createCell(row, 1, risk.getDescription(), dataStyle);
                createCell(row, 2, risk.getAffectedNodeName(), dataStyle);
                createCell(row, 3, String.format("%.2f", risk.getRiskLevel().multiply(BigDecimal.valueOf(100))), dataStyle);
            }
        } else {
            Row row = sheet.createRow(rowNum++);
            createCell(row, 0, "当前无风险项", dataStyle);
        }

        rowNum++;
        Row recTitleRow = sheet.createRow(rowNum++);
        createCell(recTitleRow, 0, "建议", headerStyle);

        if (!report.getRecommendations().isEmpty()) {
            for (String rec : report.getRecommendations()) {
                Row row = sheet.createRow(rowNum++);
                createCell(row, 0, "- " + rec, dataStyle);
            }
        } else {
            Row row = sheet.createRow(rowNum++);
            createCell(row, 0, "当前系统状态良好，无特殊建议", dataStyle);
        }

        for (int i = 0; i < 5; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    /**
     * 创建置信度趋势 Excel 报告
     */
    private void createConfidenceTrendExcel(Workbook workbook, String projectId, String versionId,
                                            CellStyle headerStyle, CellStyle dataStyle) {
        ConfidenceTrendReport report = reportingService.generateConfidenceTrend(projectId, versionId);

        Sheet sheet = workbook.createSheet("置信度趋势报告");
        int rowNum = 0;

        Row headerRow = sheet.createRow(rowNum++);
        createCell(headerRow, 0, "置信度趋势报告", headerStyle);

        rowNum++;
        Row infoRow = sheet.createRow(rowNum++);
        createCell(infoRow, 0, "项目ID", headerStyle);
        createCell(infoRow, 1, projectId, dataStyle);

        Row versionRow = sheet.createRow(rowNum++);
        createCell(versionRow, 0, "版本ID", headerStyle);
        createCell(versionRow, 1, versionId, dataStyle);

        Row trendRow = sheet.createRow(rowNum++);
        createCell(trendRow, 0, "趋势方向", headerStyle);
        createCell(trendRow, 1, report.getTrendDirection(), dataStyle);

        rowNum++;
        String[] summaryHeaders = {"指标", "值"};
        Row summaryHeaderRow = sheet.createRow(rowNum++);
        for (int i = 0; i < summaryHeaders.length; i++) {
            createCell(summaryHeaderRow, i, summaryHeaders[i], headerStyle);
        }

        Object[][] summaryData = {
                {"起始平均置信度", String.format("%.2f %%", report.getStartingAverageConfidence().multiply(BigDecimal.valueOf(100)))},
                {"当前平均置信度", String.format("%.2f %%", report.getEndingAverageConfidence().multiply(BigDecimal.valueOf(100)))},
                {"总体改进", String.format("%.2f %%", report.getTotalImprovement().multiply(BigDecimal.valueOf(100)))}
        };

        for (Object[] data : summaryData) {
            Row row = sheet.createRow(rowNum++);
            createCell(row, 0, (String) data[0], dataStyle);
            createCell(row, 1, (String) data[1], dataStyle);
        }

        rowNum++;
        Row dailyTitleRow = sheet.createRow(rowNum++);
        createCell(dailyTitleRow, 0, "每日数据", headerStyle);

        String[] dailyHeaders = {"日期", "平均置信度", "已确认节点数", "新增节点数"};
        Row dailyHeaderRow = sheet.createRow(rowNum++);
        for (int i = 0; i < dailyHeaders.length; i++) {
            createCell(dailyHeaderRow, i, dailyHeaders[i], headerStyle);
        }

        for (ConfidenceTrendReport.DailyData data : report.getDailyData()) {
            Row row = sheet.createRow(rowNum++);
            createCell(row, 0, data.getDate().toString(), dataStyle);
            createCell(row, 1, String.format("%.2f %%", data.getAverageConfidence().multiply(BigDecimal.valueOf(100))), dataStyle);
            createCell(row, 2, String.valueOf(data.getConfirmedNodes()), dataStyle);
            createCell(row, 3, String.valueOf(data.getNewNodes()), dataStyle);
        }

        for (int i = 0; i < 4; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    /**
     * 创建测试覆盖率 Excel 报告
     */
    private void createTestCoverageExcel(Workbook workbook, String projectId, String versionId,
                                         CellStyle headerStyle, CellStyle dataStyle) {
        TestCoverageReport report = reportingService.generateTestCoverageReport(projectId, versionId);

        Sheet sheet = workbook.createSheet("测试覆盖率报告");
        int rowNum = 0;

        Row headerRow = sheet.createRow(rowNum++);
        createCell(headerRow, 0, "测试覆盖率报告", headerStyle);

        rowNum++;
        Row coverHeaderRow = sheet.createRow(rowNum++);
        createCell(coverHeaderRow, 0, "覆盖统计", headerStyle);

        String[] coverHeaders = {"维度", "总数", "已覆盖", "覆盖率"};
        Row coverHeaderRow2 = sheet.createRow(rowNum++);
        for (int i = 0; i < coverHeaders.length; i++) {
            createCell(coverHeaderRow2, i, coverHeaders[i], headerStyle);
        }

        Row nodeCoverRow = sheet.createRow(rowNum++);
        createCell(nodeCoverRow, 0, "节点", dataStyle);
        createCell(nodeCoverRow, 1, String.valueOf(report.getTotalNodes()), dataStyle);
        createCell(nodeCoverRow, 2, String.valueOf(report.getCoveredNodes()), dataStyle);
        createCell(nodeCoverRow, 3, String.format("%.2f %%", report.getCoveragePercentage()), dataStyle);

        Row edgeCoverRow = sheet.createRow(rowNum++);
        createCell(edgeCoverRow, 0, "关系", dataStyle);
        createCell(edgeCoverRow, 1, String.valueOf(report.getTotalEdges()), dataStyle);
        createCell(edgeCoverRow, 2, String.valueOf(report.getCoveredEdges()), dataStyle);
        createCell(edgeCoverRow, 3, String.format("%.2f %%", report.getEdgeCoveragePercentage()), dataStyle);

        if (!report.getHighConfidenceUncovered().isEmpty()) {
            rowNum++;
            Row uncoverTitleRow = sheet.createRow(rowNum++);
            createCell(uncoverTitleRow, 0, "高置信度待覆盖节点", headerStyle);

            String[] uncoverHeaders = {"节点名称", "节点类型", "置信度"};
            Row uncoverHeaderRow = sheet.createRow(rowNum++);
            for (int i = 0; i < uncoverHeaders.length; i++) {
                createCell(uncoverHeaderRow, i, uncoverHeaders[i], headerStyle);
            }

            for (TestCoverageReport.UncoveredItem item : report.getHighConfidenceUncovered()) {
                Row row = sheet.createRow(rowNum++);
                createCell(row, 0, item.getNodeName(), dataStyle);
                createCell(row, 1, item.getNodeType(), dataStyle);
                createCell(row, 2, String.format("%.2f %%", item.getConfidence().multiply(BigDecimal.valueOf(100))), dataStyle);
            }
        }

        for (int i = 0; i < 4; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    /**
     * 创建图谱质量 Excel 报告
     */
    private void createGraphQualityExcel(Workbook workbook, String projectId, String versionId,
                                          CellStyle headerStyle, CellStyle dataStyle) {
        GraphQualityReport report = reportingService.generateGraphQualityReport(projectId, versionId);

        Sheet sheet = workbook.createSheet("图谱质量报告");
        int rowNum = 0;

        Row headerRow = sheet.createRow(rowNum++);
        createCell(headerRow, 0, "图谱质量报告", headerStyle);

        rowNum++;
        Row qualityHeaderRow = sheet.createRow(rowNum++);
        createCell(qualityHeaderRow, 0, "质量统计", headerStyle);

        String[] qualityHeaders = {"指标", "值"};
        Row qualityHeaderRow2 = sheet.createRow(rowNum++);
        for (int i = 0; i < qualityHeaders.length; i++) {
            createCell(qualityHeaderRow2, i, qualityHeaders[i], headerStyle);
        }

        Object[][] qualityData = {
                {"节点总数", String.valueOf(report.getTotalNodes())},
                {"关系总数", String.valueOf(report.getTotalEdges())},
                {"不连通组件数", String.valueOf(report.getDisconnectedComponents())},
                {"平均置信度", String.format("%.2f %%", report.getAverageConfidence().multiply(BigDecimal.valueOf(100)))},
                {"平均节点度数", String.valueOf(report.getAverageNodeDegree())},
                {"图密度", String.format("%.2f %%", report.getDensity().multiply(BigDecimal.valueOf(100)))},
                {"重复候选占比", String.format("%.2f %%", report.getDuplicateCandidateRatio().multiply(BigDecimal.valueOf(100)))}
        };

        for (Object[] data : qualityData) {
            Row row = sheet.createRow(rowNum++);
            createCell(row, 0, (String) data[0], dataStyle);
            createCell(row, 1, (String) data[1], dataStyle);
        }

        if (!report.getQualityIssues().isEmpty()) {
            rowNum++;
            Row problemTitleRow = sheet.createRow(rowNum++);
            createCell(problemTitleRow, 0, "质量问题列表", headerStyle);

            String[] problemHeaders = {"问题类型", "节点名称", "描述", "影响"};
            Row problemHeaderRow = sheet.createRow(rowNum++);
            for (int i = 0; i < problemHeaders.length; i++) {
                createCell(problemHeaderRow, i, problemHeaders[i], headerStyle);
            }

            for (GraphQualityReport.QualityIssue issue : report.getQualityIssues()) {
                Row row = sheet.createRow(rowNum++);
                createCell(row, 0, issue.getIssueType(), dataStyle);
                createCell(row, 1, issue.getNodeName(), dataStyle);
                createCell(row, 2, issue.getDescription(), dataStyle);
                createCell(row, 3, String.format("%.2f", issue.getImpact().multiply(BigDecimal.valueOf(100))), dataStyle);
            }
        }

        for (int i = 0; i < 4; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private void createCell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }
}