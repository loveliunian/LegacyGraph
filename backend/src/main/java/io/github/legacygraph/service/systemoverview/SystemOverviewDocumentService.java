package io.github.legacygraph.service.systemoverview;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final Neo4jGraphDao graphDao;
    private final Path reportRoot;

    public SystemOverviewDocumentService(SystemOverviewService systemOverviewService,
                                         ReportRepository reportRepository,
                                         Neo4jGraphDao graphDao,
                                         @Value("${legacygraph.reports.local-dir:${user.home}/.legacygraph/reports}")
                                         String reportRoot) {
        this.systemOverviewService = systemOverviewService;
        this.reportRepository = reportRepository;
        this.graphDao = graphDao;
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

    /**
     * 生成系统总览 Markdown 内容（不落盘、不写库）。
     *
     * <p>与 {@link #generateAfterScan(String, String)} 共用同一份内容逻辑
     * （{@link SystemOverviewService#generateMarkdown} + QA 文档基础章节），
     * 但仅返回字符串，供 {@code ScanArtifactPublisher} 自行落盘到 docs/legacygraph 并向量化。</p>
     */
    public String generateMarkdownContent(String projectId, String versionId) {
        try {
            String markdown = systemOverviewService.generateMarkdown(projectId, versionId);
            return ensureQaFoundationSection(markdown);
        } catch (Exception e) {
            log.warn("Failed to generate markdown content: projectId={}, versionId={}: {}",
                    projectId, versionId, e.getMessage());
            return null;
        }
    }

    // ──────────── 章节级增量生成 ────────────

    /**
     * 增量生成系统总览 Markdown — 仅重写受影响模块的章节，保留未变更章节。
     *
     * <p>尝试从已落盘的报告文件读取旧文档内容，然后委托
     * {@link #generateIncrementalMarkdown(String, String, Set, String)}。</p>
     *
     * @param projectId          项目 ID
     * @param versionId          版本 ID
     * @param affectedModuleNames 受影响模块名集合（如包名 {@code com.example.order}）
     * @return 完整 Markdown 文档；旧文档不存在时退化为全量生成
     */
    public String generateIncrementalMarkdown(String projectId, String versionId,
                                               Set<String> affectedModuleNames) {
        String oldMarkdown = readExistingMarkdown(projectId, versionId);
        return generateIncrementalMarkdown(projectId, versionId, affectedModuleNames, oldMarkdown);
    }

    /**
     * 增量生成系统总览 Markdown — 仅重写受影响模块的章节，保留未变更章节。
     *
     * <p>策略：
     * <ol>
     *   <li>生成新全量内容作为替换来源</li>
     *   <li>旧文档为 null/blank 时直接返回新内容（全量生成）</li>
     *   <li>按 {@code ##} 标题分割新旧文档为章节</li>
     *   <li>匹配受影响模块名到旧文档章节标题</li>
     *   <li>受影响章节用新内容替换，未受影响章节保留旧内容</li>
     *   <li>无法匹配任何章节时退化为全量生成</li>
     * </ol>
     *
     * @param projectId          项目 ID
     * @param versionId          版本 ID
     * @param affectedModuleNames 受影响模块名集合
     * @param oldMarkdown        旧文档内容，null/blank 时退化为全量生成
     * @return 完整 Markdown 文档
     */
    public String generateIncrementalMarkdown(String projectId, String versionId,
                                               Set<String> affectedModuleNames, String oldMarkdown) {
        String newMarkdown = generateMarkdownContent(projectId, versionId);
        if (newMarkdown == null || newMarkdown.isBlank()) {
            log.warn("Incremental generation: new markdown is empty, returning old content");
            return oldMarkdown;
        }

        if (oldMarkdown == null || oldMarkdown.isBlank()) {
            log.debug("Incremental generation: no old markdown, doing full generation");
            return newMarkdown;
        }

        if (affectedModuleNames == null || affectedModuleNames.isEmpty()) {
            log.debug("Incremental generation: no affected modules, doing full generation");
            return newMarkdown;
        }

        LinkedHashMap<String, String> oldSections = splitBySections(oldMarkdown);
        LinkedHashMap<String, String> newSections = splitBySections(newMarkdown);

        if (oldSections.isEmpty() || newSections.isEmpty()) {
            log.debug("Incremental generation: cannot parse sections, doing full generation");
            return newMarkdown;
        }

        // 匹配受影响模块名到旧文档章节标题
        Set<String> affectedSectionTitles = new HashSet<>();
        for (String moduleName : affectedModuleNames) {
            String matched = matchSection(moduleName, oldSections.keySet());
            if (matched != null) {
                affectedSectionTitles.add(matched);
            }
        }

        if (affectedSectionTitles.isEmpty()) {
            log.debug("Incremental generation: no section matched affected modules, doing full generation");
            return newMarkdown;
        }

        // 所有旧章节都受影响 → 退化为全量生成（增量无意义）
        if (affectedSectionTitles.size() >= oldSections.size()) {
            log.debug("Incremental generation: all sections affected, doing full generation");
            return newMarkdown;
        }

        // 合并：头部用新内容（更新生成时间等），受影响章节用新内容，其余保留旧内容
        String newHeader = extractHeader(newMarkdown);
        StringBuilder result = new StringBuilder(newHeader);

        // 遍历旧文档章节（保序），受影响章节替换为新内容
        for (Map.Entry<String, String> entry : oldSections.entrySet()) {
            String title = entry.getKey();
            if (affectedSectionTitles.contains(title)) {
                String newSection = newSections.get(title);
                result.append(newSection != null ? newSection : entry.getValue());
            } else {
                result.append(entry.getValue());
            }
        }

        // 追加新文档中新增的章节（旧文档没有的）
        for (Map.Entry<String, String> entry : newSections.entrySet()) {
            if (!oldSections.containsKey(entry.getKey())) {
                result.append(entry.getValue());
            }
        }

        log.info("Incremental generation: {} sections updated, {} sections preserved",
                affectedSectionTitles.size(), oldSections.size() - affectedSectionTitles.size());
        return result.toString();
    }

    /**
     * 从受影响节点 ID 集合提取模块/包名。
     *
     * <p>查询 Neo4j 获取节点的 sourcePath / className / nodeKey，
     * 按优先级提取模块名：
     * <ol>
     *   <li>Package 节点 → nodeKey（即包名）</li>
     *   <li>其他节点 → className 去掉类名后的包路径</li>
     *   <li>回退 → sourcePath 转换为包名</li>
     * </ol>
     *
     * @param projectId      项目 ID（保留以便未来过滤，当前 findNodesByIds 不需要）
     * @param versionId      版本 ID（同上）
     * @param affectedNodeIds 受影响节点 ID 集合
     * @return 模块名集合，无数据时返回空集合
     */
    public Set<String> extractModuleNames(String projectId, String versionId,
                                          Set<String> affectedNodeIds) {
        if (affectedNodeIds == null || affectedNodeIds.isEmpty()) {
            return Collections.emptySet();
        }
        try {
            List<GraphNode> nodes = graphDao.findNodesByIds(new ArrayList<>(affectedNodeIds));
            if (nodes == null || nodes.isEmpty()) {
                return Collections.emptySet();
            }
            Set<String> moduleNames = new java.util.LinkedHashSet<>();
            for (GraphNode node : nodes) {
                String moduleName = extractModuleNameFromNode(node);
                if (moduleName != null && !moduleName.isBlank()) {
                    moduleNames.add(moduleName);
                }
            }
            return moduleNames;
        } catch (Exception e) {
            log.warn("Failed to extract module names from affected nodes: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    // ──────────── 章节级增量辅助方法 ────────────

    /**
     * 按 {@code ##} 标题分割 Markdown 文档为章节。
     *
     * <p>第一个 {@code ##} 之前的内容（文档头部，含 {@code #} 一级标题和元数据）不包含在返回 map 中。
     * 使用 {@link #extractHeader(String)} 单独提取头部。</p>
     *
     * @param markdown 完整 Markdown 文档
     * @return {@link LinkedHashMap} 保序：key = 标题行（含 {@code ##} 前缀），value = 章节内容（从标题行到下一个 {@code ##} 或文档末尾）
     */
    LinkedHashMap<String, String> splitBySections(String markdown) {
        LinkedHashMap<String, String> sections = new LinkedHashMap<>();
        if (markdown == null || markdown.isBlank()) {
            return sections;
        }

        String[] lines = markdown.split("\n", -1);
        StringBuilder currentSection = null;
        String currentTitle = null;

        for (String line : lines) {
            if (line.startsWith("## ")) {
                // 新章节开始 — 保存上一章节
                if (currentTitle != null && currentSection != null) {
                    sections.put(currentTitle, currentSection.toString());
                }
                currentTitle = line;
                currentSection = new StringBuilder();
                currentSection.append(line).append("\n");
            } else if (currentSection != null) {
                currentSection.append(line).append("\n");
            }
            // currentSection == null 时处于头部，跳过
        }
        // 保存最后一个章节
        if (currentTitle != null && currentSection != null) {
            sections.put(currentTitle, currentSection.toString());
        }
        return sections;
    }

    /**
     * 提取文档头部 — 第一个 {@code ##} 标题之前的全部内容（含一级标题和元数据）。
     *
     * @param markdown 完整 Markdown 文档
     * @return 头部内容字符串
     */
    String extractHeader(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        int idx = markdown.indexOf("\n## ");
        if (idx < 0) {
            // 没有任何 ## 章节
            return markdown.startsWith("## ") ? "" : markdown;
        }
        return markdown.substring(0, idx + 1);
    }

    /**
     * 匹配模块名到文档章节标题。
     *
     * <p>匹配策略（大小写不敏感）：
     * <ol>
     *   <li>完整模块名出现在章节标题中（如 {@code com.example.order} 匹配 {@code "## 模块: com.example.order"}）</li>
     *   <li>模块名最后一段出现在章节标题中（如 {@code com.example.order} 的 {@code order} 匹配 {@code "## 模块: order"}）</li>
     * </ol>
     *
     * @param moduleName    模块名（如 {@code com.example.order} 或 {@code order}）
     * @param sectionTitles 章节标题集合（含 {@code ##} 前缀）
     * @return 匹配到的章节标题，无匹配返回 {@code null}
     */
    String matchSection(String moduleName, Set<String> sectionTitles) {
        if (moduleName == null || moduleName.isBlank() || sectionTitles == null || sectionTitles.isEmpty()) {
            return null;
        }
        String normalized = moduleName.toLowerCase();
        String lastSegment = extractLastSegment(moduleName);
        String normalizedSegment = lastSegment != null ? lastSegment.toLowerCase() : null;

        for (String title : sectionTitles) {
            if (title == null) {
                continue;
            }
            String normalizedTitle = title.toLowerCase();
            if (normalizedTitle.contains(normalized)) {
                return title;
            }
            if (normalizedSegment != null && normalizedSegment.length() >= 2
                    && normalizedTitle.contains(normalizedSegment)) {
                return title;
            }
        }
        return null;
    }

    private String extractLastSegment(String moduleName) {
        if (moduleName == null) {
            return null;
        }
        int lastDot = moduleName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < moduleName.length() - 1) {
            return moduleName.substring(lastDot + 1);
        }
        return moduleName;
    }

    private String extractModuleNameFromNode(GraphNode node) {
        if (node == null) {
            return null;
        }
        // Package 节点直接用 nodeKey（即包名）
        if ("Package".equals(node.getNodeType()) && node.getNodeKey() != null) {
            return node.getNodeKey();
        }
        // 从 className 提取包名（去掉最后的类名）
        if (node.getClassName() != null && !node.getClassName().isBlank()) {
            String className = node.getClassName();
            int lastDot = className.lastIndexOf('.');
            if (lastDot > 0) {
                return className.substring(0, lastDot);
            }
            return className;
        }
        // 从 sourcePath 提取包名
        if (node.getSourcePath() != null && !node.getSourcePath().isBlank()) {
            return extractPackageFromPath(node.getSourcePath());
        }
        // 回退到 nodeKey
        return node.getNodeKey();
    }

    private String extractPackageFromPath(String sourcePath) {
        String path = sourcePath.replace('\\', '/');
        // 去掉文件名，保留目录
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0) {
            path = path.substring(0, lastSlash);
        }
        // 去掉 src/main/java 或 src/test/java 前缀
        for (String prefix : new String[]{"src/main/java/", "src/test/java/"}) {
            int idx = path.indexOf(prefix);
            if (idx >= 0) {
                path = path.substring(idx + prefix.length());
                break;
            }
        }
        return path.replace('/', '.');
    }

    private String readExistingMarkdown(String projectId, String versionId) {
        try {
            Path markdownPath = resolveMarkdownPath(projectId, versionId);
            if (Files.exists(markdownPath)) {
                return Files.readString(markdownPath);
            }
        } catch (Exception e) {
            log.warn("Failed to read existing markdown for incremental generation: {}", e.getMessage());
        }
        return null;
    }
}
