package io.github.legacygraph.service.systemoverview;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.Report;
import io.github.legacygraph.repository.ReportRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import io.github.legacygraph.util.IdUtil;

/**
 * 扫描完成后的系统关系总结文档生成服务。
 */
@Slf4j
@Service
public class SystemOverviewDocumentService {

    public static final String REPORT_TYPE = "SYSTEM_OVERVIEW";

    private final SystemOverviewService systemOverviewService;
    private final ReportRepository reportRepository;
    private final Path reportRoot;

    public SystemOverviewDocumentService(SystemOverviewService systemOverviewService,
                                         ReportRepository reportRepository,
                                         @Value("${legacy-graph.reports.local-dir:${user.home}/.legacygraph/reports}")
                                         String reportRoot) {
        this.systemOverviewService = systemOverviewService;
        this.reportRepository = reportRepository;
        this.reportRoot = Path.of(reportRoot);
    }

    /**
     * 生成并登记扫描完成后的业务/功能/代码/数据关系 Markdown。
     */
    @Transactional
    public Report generateAfterScan(String projectId, String versionId) throws IOException {
        String markdown = ensureQaFoundationSection(systemOverviewService.generateMarkdown(projectId, versionId));
        Path markdownPath = resolveMarkdownPath(projectId, versionId);
        Files.createDirectories(markdownPath.getParent());
        Files.writeString(markdownPath, markdown, StandardCharsets.UTF_8);

        LocalDateTime now = LocalDateTime.now();
        Report report = findExistingReport(projectId, versionId);
        boolean inserting = report == null;
        if (inserting) {
            report = new Report();
            report.setId(IdUtil.fastUUID());
            report.setGeneratedAt(now);
        }

        report.setProjectId(projectId);
        report.setVersionId(versionId);
        report.setReportType(REPORT_TYPE);
        report.setReportName("系统关系总览报告 - " + normalizeVersion(versionId));
        report.setStatus("COMPLETED");
        report.setFilePath(markdownPath.toString());
        report.setCompletedAt(now);
        report.setErrorMessage(null);
        report.setDeleted(0);

        if (inserting) {
            reportRepository.insert(report);
        } else {
            reportRepository.updateById(report);
        }
        log.info("System overview markdown generated: projectId={}, versionId={}, path={}",
                projectId, versionId, markdownPath);
        return report;
    }

    private Report findExistingReport(String projectId, String versionId) {
        List<Report> reports = reportRepository.selectList(new LambdaQueryWrapper<Report>()
                .eq(Report::getProjectId, projectId)
                .eq(Report::getVersionId, versionId)
                .eq(Report::getReportType, REPORT_TYPE)
                .orderByDesc(Report::getGeneratedAt));
        return reports == null || reports.isEmpty() ? null : reports.get(0);
    }

    private Path resolveMarkdownPath(String projectId, String versionId) {
        return reportRoot
                .resolve(safeSegment(projectId))
                .resolve(safeSegment(normalizeVersion(versionId)))
                .resolve("system-overview.md");
    }

    private String ensureQaFoundationSection(String markdown) {
        String content = markdown == null ? "" : markdown;
        if (content.contains("QA 文档基础")) {
            return content;
        }
        return content + """

                ## 4. QA 文档基础

                - 本文档是扫描完成后沉淀的业务/功能/代码/数据关系结论，可作为后续 QA 文档生成的事实基础。
                - 后续 QA 文档应优先引用本报告中的业务域、功能、Controller/API、代码模块、数据表与核心贯穿链路。
                - 未出现在本报告中的关系仍应回到 Claim、证据或图谱查询确认，避免把推断当成已确认事实。
                """;
    }

    private String normalizeVersion(String versionId) {
        return versionId == null || versionId.isBlank() ? "default" : versionId;
    }

    private String safeSegment(String value) {
        return value == null || value.isBlank()
                ? "default"
                : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
