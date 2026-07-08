package io.github.legacygraph.service.systemoverview;

import io.github.legacygraph.entity.Report;
import io.github.legacygraph.repository.ReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemOverviewDocumentServiceTest {

    @TempDir
    Path reportRoot;

    @Test
    void generateAfterScan_writesMarkdownAndRegistersDownloadableReport() throws Exception {
        SystemOverviewService systemOverviewService = mock(SystemOverviewService.class);
        ReportRepository reportRepository = mock(ReportRepository.class);
        SystemOverviewDocumentService service = new SystemOverviewDocumentService(
                systemOverviewService, reportRepository, reportRoot.toString());

        when(systemOverviewService.generateMarkdown("project-1", "version-1"))
                .thenReturn("""
                        # 系统关系总览报告 — 业务/功能/代码/数据

                        ## 1. 业务域映射总表
                        | 业务域 | 功能 | 代码 | 数据表 |
                        |---|---|---|---|
                        | 结算 | 对账 | ReconcileService | settle_bill |
                        """);

        Report report = service.generateAfterScan("project-1", "version-1");

        assertNotNull(report.getId());
        assertEquals("project-1", report.getProjectId());
        assertEquals("version-1", report.getVersionId());
        assertEquals("SYSTEM_OVERVIEW", report.getReportType());
        assertEquals("COMPLETED", report.getStatus());
        assertTrue(report.getReportName().contains("系统关系总览"));
        assertNotNull(report.getFilePath());

        Path markdown = Path.of(report.getFilePath());
        assertTrue(Files.exists(markdown), "扫描完成后应写出可下载的 Markdown 文件");
        String content = Files.readString(markdown);
        assertTrue(content.contains("业务/功能/代码/数据"));
        assertTrue(content.contains("后续 QA 文档"));

        verify(reportRepository).insert(any(Report.class));
    }

    @Test
    void generateAfterScan_doesNotDuplicateQaFoundationSectionWhenNumberChanges() throws Exception {
        SystemOverviewService systemOverviewService = mock(SystemOverviewService.class);
        ReportRepository reportRepository = mock(ReportRepository.class);
        SystemOverviewDocumentService service = new SystemOverviewDocumentService(
                systemOverviewService, reportRepository, reportRoot.toString());

        when(systemOverviewService.generateMarkdown("project-1", "version-1"))
                .thenReturn("""
                        # 系统关系总览报告

                        ## 8. QA 文档基础

                        - 已包含 QA 文档基础。
                        """);

        Report report = service.generateAfterScan("project-1", "version-1");

        String content = Files.readString(Path.of(report.getFilePath()));
        long qaHeadings = content.lines()
                .filter(line -> line.startsWith("## ") && line.contains("QA 文档基础"))
                .count();
        assertEquals(1, qaHeadings);
        verify(reportRepository).insert(any(Report.class));
        verify(reportRepository, never()).updateById(any(Report.class));
    }
}
