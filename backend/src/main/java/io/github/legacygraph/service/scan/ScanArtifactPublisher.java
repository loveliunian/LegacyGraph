package io.github.legacygraph.service.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.service.qa.VectorizationService;
import io.github.legacygraph.service.report.ScanPerformanceReportService;
import io.github.legacygraph.service.systemoverview.SystemOverviewDocumentService;
import io.github.legacygraph.understanding.CodeUnderstandingReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 扫描产物发布服务 — 把扫描产生的总结性文档统一发布到项目根目录 /docs/legacygraph/，
 * 供 LegacyGraph QA（向量化到 pgvector）和外部工具（文件可见）双重消费。
 *
 * <p>产物来源：
 * <ul>
 *   <li>{@link SystemOverviewDocumentService} 系统关系总览 Markdown</li>
 *   <li>{@link ScanPerformanceReportService} 扫描性能报告</li>
 *   <li>{@link CodeUnderstandingReportService} 代码理解报告</li>
 *   <li>外部工具证据摘要（从 lg_tool_run / lg_tool_evidence 导出）</li>
 *   <li>{@link GraphQualityAssessor} 图谱质量评估报告</li>
 * </ul>
 *
 * <p>设计约束：
 * <ul>
 *   <li>对被扫描项目源码目录只追加 /docs/legacygraph/ 子目录，不动 .gitignore</li>
 *   <li>发布失败只 warn，不阻塞扫描主流程</li>
 *   <li>每次发布覆盖旧文件，向量化支持全量（按 sourceUri+versionId 删除旧向量）与 chunk 级增量两种模式</li>
 * </ul>
 */
@Slf4j
@Service
public class ScanArtifactPublisher {

    public static final String CHUNK_TYPE = "SCAN_ARTIFACT_DOC";
    public static final String DOCS_SUBDIR = "docs/legacygraph";

    private static final int CHUNK_MAX_CHARS = 1000;
    private static final int CHUNK_OVERLAP = 100;

    private final CodeRepoRepository codeRepoRepository;
    private final SystemOverviewDocumentService systemOverviewDocumentService;
    private final ScanPerformanceReportService scanPerformanceReportService;
    private final CodeUnderstandingReportService codeUnderstandingReportService;
    private final ExternalToolEvidenceExporter externalToolEvidenceExporter;
    private final VectorizationService vectorizationService;
    private final GraphQualityAssessor graphQualityAssessor;
    private final EdgeCompletionService edgeCompletionService;
    private final CommunityDetectionService communityDetectionService;

    /** 当项目根目录无法解析（无 repo / 路径非法）时的回退目录 */
    @Value("${legacy-graph.reports.local-dir:${user.home}/.legacygraph/reports}")
    private String fallbackReportRoot;

    public ScanArtifactPublisher(CodeRepoRepository codeRepoRepository,
                                  SystemOverviewDocumentService systemOverviewDocumentService,
                                  ScanPerformanceReportService scanPerformanceReportService,
                                  CodeUnderstandingReportService codeUnderstandingReportService,
                                  ExternalToolEvidenceExporter externalToolEvidenceExporter,
                                  VectorizationService vectorizationService,
                                  GraphQualityAssessor graphQualityAssessor,
                                  EdgeCompletionService edgeCompletionService,
                                  CommunityDetectionService communityDetectionService) {
        this.codeRepoRepository = codeRepoRepository;
        this.systemOverviewDocumentService = systemOverviewDocumentService;
        this.scanPerformanceReportService = scanPerformanceReportService;
        this.codeUnderstandingReportService = codeUnderstandingReportService;
        this.externalToolEvidenceExporter = externalToolEvidenceExporter;
        this.vectorizationService = vectorizationService;
        this.graphQualityAssessor = graphQualityAssessor;
        this.edgeCompletionService = edgeCompletionService;
        this.communityDetectionService = communityDetectionService;
    }

    /**
     * 发布全部扫描产物到 {projectRoot}/docs/legacygraph/ 并向量化（全量模式）。
     *
     * <p>等价于 {@link #publish(String, String, boolean) publish(projectId, versionId, false)}。
     * 保留以兼容现有 {@code ProjectScanner} / {@code AiScanJobWorker} 调用点。</p>
     */
    public void publish(String projectId, String versionId) {
        publish(projectId, versionId, false);
    }

    /**
     * 发布全部扫描产物到 {projectRoot}/docs/legacygraph/ 并向量化。
     *
     * <p>扫描完成（基础扫描或 AI 编排）后调用。每个产物独立 try/catch，单文件失败不影响其他产物。</p>
     *
     * @param incremental true 时走 chunk 级增量向量化（{@link VectorizationService#embedDocumentIncremental}），
     *                   仅对内容变化的 chunk 重新嵌入；false 时全量重写（先按 sourceUri+versionId 删除旧向量，再 embedDocument）。
     *                   <p>注意：此重载不携带增量上下文（affectedNodeIds / affectedModuleNames），
     *                   system-overview 与 code-understanding-report 将退化为全量章节生成 + 增量向量化。
     *                   如需章节级增量生成，请使用
     *                   {@link #publish(String, String, Set, Set)}。</p>
     */
    public void publish(String projectId, String versionId, boolean incremental) {
        publishInternal(projectId, versionId, incremental, null, null);
    }

    /**
     * 发布全部扫描产物 — 支持章节级增量文档生成。
     *
     * <p>当 {@code changedFilePaths} 非空时判定为增量模式：
     * <ol>
     *   <li>从 {@code affectedNodeIds} 经 {@link SystemOverviewDocumentService#extractModuleNames}
     *       提取受影响模块名</li>
     *   <li>system-overview.md：读取旧文件内容，调用
     *       {@link SystemOverviewDocumentService#generateIncrementalMarkdown(String, String, Set, String)}
     *       仅重写受影响章节</li>
     *   <li>code-understanding-report.md：读取旧文件内容，调用
     *       {@link CodeUnderstandingReportService#generateIncrementalAggregatedMarkdown(String, String, Set, String)}
     *       仅重写受影响功能点章节</li>
     *   <li>向量化走 chunk 级增量（{@link VectorizationService#embedDocumentIncremental}）</li>
     * </ol>
     *
     * <p>当 {@code changedFilePaths} 为 null/空时退化为全量模式，等价于
     * {@link #publish(String, String, boolean) publish(projectId, versionId, false)}。</p>
     *
     * @param projectId         项目 ID
     * @param versionId         版本 ID
     * @param changedFilePaths  本次扫描变更文件路径集合；null/空 → 全量模式
     * @param affectedNodeIds   受影响图谱节点 ID 集合（用于章节级增量生成）；null/空时增量生成退化为全量
     */
    public void publish(String projectId, String versionId,
                        Set<String> changedFilePaths, Set<String> affectedNodeIds) {
        boolean incremental = changedFilePaths != null && !changedFilePaths.isEmpty();
        if (!incremental) {
            publish(projectId, versionId, false);
            return;
        }
        // 增量模式：从受影响节点 ID 提取模块名（用于 system-overview 章节匹配）
        Set<String> affectedModuleNames = (affectedNodeIds == null || affectedNodeIds.isEmpty())
                ? Collections.emptySet()
                : systemOverviewDocumentService.extractModuleNames(projectId, versionId, affectedNodeIds);
        publishInternal(projectId, versionId, true, affectedNodeIds, affectedModuleNames);
    }

    /**
     * 内部统一发布流程 — 由 {@link #publish(String, String, boolean)} 与
     * {@link #publish(String, String, Set, Set)} 共用。
     *
     * <p>当 {@code affectedModuleNames}/{@code affectedNodeIds} 非空且 incremental=true 时，
     * system-overview 与 code-understanding-report 走章节级增量生成；否则走全量生成。</p>
     */
    private void publishInternal(String projectId, String versionId, boolean incremental,
                                  Set<String> affectedNodeIds, Set<String> affectedModuleNames) {
        Path docsDir = resolveDocsDir(projectId);
        if (docsDir == null) {
            log.warn("ScanArtifactPublisher: cannot resolve docs dir for projectId={}, skip publishing", projectId);
            return;
        }
        try {
            Files.createDirectories(docsDir);
        } catch (IOException e) {
            log.warn("ScanArtifactPublisher: failed to create docs dir {}: {}", docsDir, e.getMessage());
            return;
        }
        log.info("ScanArtifactPublisher: publishing artifacts to {} for projectId={}, versionId={}, incremental={}",
                docsDir, projectId, versionId, incremental);

        // 1. 图谱定稿：边补全（传递闭包 + 规则校验），单独 try/catch，失败不阻塞
        completeEdges(projectId, versionId);
        // 2. 社区检测，将结果写入 Package 节点 properties.community
        detectCommunities(projectId);
        // 3. 质量评估（在图谱定稿后，反映最终图谱质量）
        publishGraphQualityReport(projectId, versionId);
        // 4. 分层总结及各产物发布（基于定稿图谱 + 质量报告）
        publishSystemOverview(projectId, versionId, docsDir, incremental, affectedModuleNames);
        publishScanPerformanceReport(projectId, versionId, docsDir, incremental);
        publishCodeUnderstandingReport(projectId, versionId, docsDir, incremental, affectedNodeIds);
        publishExternalToolEvidence(projectId, versionId, docsDir, incremental);
    }

    /**
     * 社区检测 — 扫描完成后对图谱执行标签传播算法，结果写入 Package 节点 properties.community。
     * <p>单独 try/catch，失败仅 warn，不阻塞扫描主流程。</p>
     */
    private void detectCommunities(String projectId) {
        try {
            java.util.Map<String, String> communityMap = communityDetectionService.detectCommunities(projectId);
            if (communityMap != null && !communityMap.isEmpty()) {
                communityDetectionService.writeCommunityToNodes(projectId, communityMap);
                log.info("ScanArtifactPublisher: community detection done projectId={}, communities={}",
                        projectId, new java.util.HashSet<>(communityMap.values()).size());
            }
        } catch (Exception e) {
            log.warn("ScanArtifactPublisher: community detection failed (non-blocking) projectId={}: {}",
                    projectId, e.getMessage());
        }
    }

    /**
     * 边补全 — 传递闭包补全 + 规则校验补全，提高图谱连通性。
     * <p>单独 try/catch，失败仅 warn，不阻塞扫描主流程。</p>
     */
    private void completeEdges(String projectId, String versionId) {
        try {
            EdgeCompletionService.CompletionReport report = edgeCompletionService.completeAll(projectId, versionId);
            log.info("ScanArtifactPublisher: edge completion done projectId={}, versionId={}, transitiveEdges={}, belongsToFixed={}, anomalies={}",
                    projectId, versionId, report.getTransitiveEdgesAdded(),
                    report.getBelongsToFixed(), report.getAnomalies().size());
        } catch (Exception e) {
            log.warn("ScanArtifactPublisher: edge completion failed (non-blocking) projectId={}, versionId={}: {}",
                    projectId, versionId, e.getMessage());
        }
    }

    /**
     * 图谱质量评估 — 单独 try/catch，失败不影响其他产物。
     */
    private void publishGraphQualityReport(String projectId, String versionId) {
        try {
            graphQualityAssessor.assessAndReport(projectId, versionId);
        } catch (Exception e) {
            log.warn("Failed to publish graph-quality-report.md: {}", e.getMessage());
        }
    }

    // ──────────── 各产物发布 ────────────

    private void publishSystemOverview(String projectId, String versionId, Path docsDir,
                                        boolean incremental, Set<String> affectedModuleNames) {
        try {
            // 在图谱定稿（边补全、社区检测、质量评估）后刷新 Report 表登记的正式副本，
            // 避免 reportRoot 与 docs/legacygraph 分别基于定稿前/后的图谱而不一致。
            systemOverviewDocumentService.generateAfterScan(projectId, versionId);

            // docs/legacygraph 是可检索发布副本，使用同一时点重新生成的 Markdown 落盘并向量化。
            Path file = docsDir.resolve("system-overview.md");
            String markdown;
            if (incremental && affectedModuleNames != null && !affectedModuleNames.isEmpty()) {
                // 章节级增量：读取旧文档，仅重写受影响模块章节
                String oldMarkdown = readExistingFile(file);
                markdown = systemOverviewDocumentService.generateIncrementalMarkdown(
                        projectId, versionId, affectedModuleNames, oldMarkdown);
                log.info("ScanArtifactPublisher: system-overview incremental generation, affectedModules={}",
                        affectedModuleNames);
            } else {
                markdown = systemOverviewDocumentService.generateMarkdownContent(projectId, versionId);
            }
            if (markdown == null || markdown.isBlank()) {
                return;
            }
            Files.writeString(file, markdown, StandardCharsets.UTF_8);
            vectorize(projectId, versionId, "system-overview.md", markdown, incremental);
            log.info("Published system-overview.md to {}", file);
        } catch (Exception e) {
            log.warn("Failed to publish system-overview.md: {}", e.getMessage());
        }
    }

    private void publishScanPerformanceReport(String projectId, String versionId, Path docsDir, boolean incremental) {
        try {
            String markdown = scanPerformanceReportService.generateMarkdown(projectId, versionId);
            if (markdown == null || markdown.isBlank()) {
                return;
            }
            Path file = docsDir.resolve("scan-performance-report.md");
            Files.writeString(file, markdown, StandardCharsets.UTF_8);
            vectorize(projectId, versionId, "scan-performance-report.md", markdown, incremental);
            log.info("Published scan-performance-report.md to {}", file);
        } catch (Exception e) {
            log.warn("Failed to publish scan-performance-report.md: {}", e.getMessage());
        }
    }

    private void publishCodeUnderstandingReport(String projectId, String versionId, Path docsDir,
                                                  boolean incremental, Set<String> affectedNodeIds) {
        try {
            Path file = docsDir.resolve("code-understanding-report.md");
            String markdown;
            if (incremental && affectedNodeIds != null && !affectedNodeIds.isEmpty()) {
                // 章节级增量：读取旧报告，仅重写受影响功能点章节
                String oldMarkdown = readExistingFile(file);
                markdown = codeUnderstandingReportService.generateIncrementalAggregatedMarkdown(
                        projectId, versionId, affectedNodeIds, oldMarkdown);
                log.info("ScanArtifactPublisher: code-understanding-report incremental generation, affectedNodes={}",
                        affectedNodeIds.size());
            } else {
                markdown = codeUnderstandingReportService.generateAggregatedMarkdown(projectId, versionId);
            }
            if (markdown == null || markdown.isBlank()) {
                return;
            }
            Files.writeString(file, markdown, StandardCharsets.UTF_8);
            vectorize(projectId, versionId, "code-understanding-report.md", markdown, incremental);
            log.info("Published code-understanding-report.md to {}", file);
        } catch (Exception e) {
            log.warn("Failed to publish code-understanding-report.md: {}", e.getMessage());
        }
    }

    private void publishExternalToolEvidence(String projectId, String versionId, Path docsDir, boolean incremental) {
        try {
            String markdown = externalToolEvidenceExporter.exportMarkdown(projectId, versionId);
            if (markdown == null || markdown.isBlank()) {
                return;
            }
            Path file = docsDir.resolve("external-tool-evidence.md");
            Files.writeString(file, markdown, StandardCharsets.UTF_8);
            vectorize(projectId, versionId, "external-tool-evidence.md", markdown, incremental);
            log.info("Published external-tool-evidence.md to {}", file);
        } catch (Exception e) {
            log.warn("Failed to publish external-tool-evidence.md: {}", e.getMessage());
        }
    }

    // ──────────── 向量化 ────────────

    /**
     * 读取已存在的文档文件内容（用于章节级增量生成时传入旧文档）。
     *
     * <p>文件不存在或读取失败时返回 null，{@code generateIncrementalMarkdown} /
     * {@code generateIncrementalAggregatedMarkdown} 内部会退化为全量生成。</p>
     */
    private String readExistingFile(Path file) {
        if (file == null) {
            return null;
        }
        try {
            if (Files.exists(file)) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Failed to read existing file {}: {}", file, e.getMessage());
        }
        return null;
    }

    /**
     * 把文档切片向量化到 pgvector，sourceUri 用相对 docs/legacygraph/{filename} 便于溯源。
     *
     * <p>两种模式：
     * <ul>
     *   <li>全量（incremental=false）：先按 sourceUri+versionId 精确删除旧向量，再 {@link VectorizationService#embedDocument} 全量嵌入。
     *       用 deleteBySourceUriAndVersion 取代旧 deleteBySourceUri，避免误删其他扫描版本的向量。</li>
     *   <li>增量（incremental=true）：调用 {@link VectorizationService#embedDocumentIncremental}，按 chunk 的 contentSha256
     *       跳过未变化 chunk，不预先删除旧向量。</li>
     * </ul>
     */
    private void vectorize(String projectId, String versionId, String fileName, String content, boolean incremental) {
        if (!vectorizationService.isAvailable()) {
            log.debug("VectorizationService not available, skip vectorizing {}", fileName);
            return;
        }
        String sourceUri = DOCS_SUBDIR + "/" + fileName;
        try {
            int stored;
            if (incremental) {
                stored = vectorizationService.embedDocumentIncremental(
                        projectId, versionId, CHUNK_TYPE, sourceUri,
                        content, CHUNK_MAX_CHARS, CHUNK_OVERLAP, "bge-m3");
            } else {
                vectorizationService.deleteBySourceUriAndVersion(sourceUri, versionId);
                stored = vectorizationService.embedDocument(
                        projectId, versionId, CHUNK_TYPE, sourceUri,
                        content, CHUNK_MAX_CHARS, CHUNK_OVERLAP, "bge-m3");
            }
            log.info("Vectorized {} ({}): {} chunks", fileName, incremental ? "incremental" : "full", stored);
        } catch (Exception e) {
            log.warn("Failed to vectorize {}: {}", fileName, e.getMessage());
        }
    }

    // ──────────── 项目根目录解析 ────────────

    /**
     * 解析项目根目录下的 docs/legacygraph 目录。
     *
     * <p>规则：
     * <ul>
     *   <li>无 repo → 回退 {@code ~/.legacygraph/reports/{projectId}/docs/legacygraph}</li>
     *   <li>单 repo → repo.localPath 的父目录 + docs/legacygraph</li>
     *   <li>多 repo → 所有 localPath 的最长公共父目录 + docs/legacygraph</li>
     *   <li>公共父目录是 user.home 根或 / 时 → 回退到 fallbackReportRoot</li>
     * </ul>
     */
    Path resolveDocsDir(String projectId) {
        List<CodeRepo> repos = codeRepoRepository.selectList(new LambdaQueryWrapper<CodeRepo>()
                .eq(CodeRepo::getProjectId, projectId));

        if (repos.isEmpty()) {
            return fallbackDocsDir(projectId);
        }

        // 收集所有非空 localPath
        List<Path> localPaths = repos.stream()
                .map(CodeRepo::getLocalPath)
                .filter(p -> p != null && !p.isBlank())
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .toList();

        if (localPaths.isEmpty()) {
            return fallbackDocsDir(projectId);
        }

        Path commonParent = longestCommonParent(localPaths);
        if (commonParent == null) {
            return fallbackDocsDir(projectId);
        }

        // 安全检查：公共父目录不能是 / 或 user.home 根（避免误写系统目录）
        String home = System.getProperty("user.home");
        String commonPath = commonParent.toString();
        if (commonPath.equals("/") || commonPath.equals(home) || commonPath.equals(Path.of(home).getRoot().toString())) {
            log.warn("ScanArtifactPublisher: common parent is too broad ({}), using fallback dir", commonPath);
            return fallbackDocsDir(projectId);
        }

        return commonParent.resolve(DOCS_SUBDIR);
    }

    /**
     * 计算多个路径的最长公共父目录。
     */
    private Path longestCommonParent(List<Path> paths) {
        if (paths.isEmpty()) return null;
        Path common = paths.get(0);
        for (int i = 1; i < paths.size(); i++) {
            common = commonParent(common, paths.get(i));
            if (common == null) return null;
        }
        return common;
    }

    private Path commonParent(Path a, Path b) {
        // 取两个路径的公共前缀（父级）
        int minLen = Math.min(a.getNameCount(), b.getNameCount());
        Path common = a.getRoot();
        for (int i = 0; i < minLen; i++) {
            if (a.getName(i).equals(b.getName(i))) {
                common = common.resolve(a.getName(i));
            } else {
                break;
            }
        }
        // 至少要有 2 级父目录，避免直接到根
        if (common == null || common.getNameCount() < 2) {
            return null;
        }
        return common;
    }

    private Path fallbackDocsDir(String projectId) {
        Path root = Path.of(fallbackReportRoot).resolve(projectId).resolve(DOCS_SUBDIR);
        log.info("ScanArtifactPublisher: using fallback docs dir: {}", root);
        return root;
    }
}
