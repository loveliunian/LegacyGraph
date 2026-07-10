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
import java.util.List;

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
 *   <li>每次发布覆盖旧文件，向量化前先按 sourceUri 删除旧向量</li>
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
     * 发布全部扫描产物到 {projectRoot}/docs/legacygraph/ 并向量化。
     *
     * <p>扫描完成（基础扫描或 AI 编排）后调用。每个产物独立 try/catch，单文件失败不影响其他产物。</p>
     */
    public void publish(String projectId, String versionId) {
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
        log.info("ScanArtifactPublisher: publishing artifacts to {} for projectId={}, versionId={}",
                docsDir, projectId, versionId);

        publishSystemOverview(projectId, versionId, docsDir);
        publishScanPerformanceReport(projectId, versionId, docsDir);
        publishCodeUnderstandingReport(projectId, versionId, docsDir);
        publishExternalToolEvidence(projectId, versionId, docsDir);
        publishGraphQualityReport(projectId, versionId);
        // 质量评估之后执行边补全（传递闭包 + 规则校验），单独 try/catch，失败不阻塞
        completeEdges(projectId, versionId);
        // 边补全之后执行社区检测，将结果写入 Package 节点 properties.community
        detectCommunities(projectId);
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

    private void publishSystemOverview(String projectId, String versionId, Path docsDir) {
        try {
            // generateAfterScan 内部会写到自己的 reportRoot，这里我们再复制一份到 docs/legacygraph
            // 为避免双写，直接调用 generateMarkdown 并手动落盘 + 向量化
            String markdown = systemOverviewDocumentService.generateMarkdownContent(projectId, versionId);
            if (markdown == null || markdown.isBlank()) {
                return;
            }
            Path file = docsDir.resolve("system-overview.md");
            Files.writeString(file, markdown, StandardCharsets.UTF_8);
            vectorize(projectId, versionId, "system-overview.md", markdown);
            log.info("Published system-overview.md to {}", file);
        } catch (Exception e) {
            log.warn("Failed to publish system-overview.md: {}", e.getMessage());
        }
    }

    private void publishScanPerformanceReport(String projectId, String versionId, Path docsDir) {
        try {
            String markdown = scanPerformanceReportService.generateMarkdown(projectId, versionId);
            if (markdown == null || markdown.isBlank()) {
                return;
            }
            Path file = docsDir.resolve("scan-performance-report.md");
            Files.writeString(file, markdown, StandardCharsets.UTF_8);
            vectorize(projectId, versionId, "scan-performance-report.md", markdown);
            log.info("Published scan-performance-report.md to {}", file);
        } catch (Exception e) {
            log.warn("Failed to publish scan-performance-report.md: {}", e.getMessage());
        }
    }

    private void publishCodeUnderstandingReport(String projectId, String versionId, Path docsDir) {
        try {
            String markdown = codeUnderstandingReportService.generateAggregatedMarkdown(projectId, versionId);
            if (markdown == null || markdown.isBlank()) {
                return;
            }
            Path file = docsDir.resolve("code-understanding-report.md");
            Files.writeString(file, markdown, StandardCharsets.UTF_8);
            vectorize(projectId, versionId, "code-understanding-report.md", markdown);
            log.info("Published code-understanding-report.md to {}", file);
        } catch (Exception e) {
            log.warn("Failed to publish code-understanding-report.md: {}", e.getMessage());
        }
    }

    private void publishExternalToolEvidence(String projectId, String versionId, Path docsDir) {
        try {
            String markdown = externalToolEvidenceExporter.exportMarkdown(projectId, versionId);
            if (markdown == null || markdown.isBlank()) {
                return;
            }
            Path file = docsDir.resolve("external-tool-evidence.md");
            Files.writeString(file, markdown, StandardCharsets.UTF_8);
            vectorize(projectId, versionId, "external-tool-evidence.md", markdown);
            log.info("Published external-tool-evidence.md to {}", file);
        } catch (Exception e) {
            log.warn("Failed to publish external-tool-evidence.md: {}", e.getMessage());
        }
    }

    // ──────────── 向量化 ────────────

    /**
     * 把文档切片向量化到 pgvector，sourceUri 用相对 docs/legacygraph/{filename} 便于溯源。
     * 重新发布时先删旧向量（embedDocument 内部按 contentSha256 去重，但旧版本内容 hash 变化时残留）。
     */
    private void vectorize(String projectId, String versionId, String fileName, String content) {
        if (!vectorizationService.isAvailable()) {
            log.debug("VectorizationService not available, skip vectorizing {}", fileName);
            return;
        }
        String sourceUri = DOCS_SUBDIR + "/" + fileName;
        try {
            vectorizationService.deleteBySourceUri(sourceUri);
            int stored = vectorizationService.embedDocument(
                    projectId, versionId, CHUNK_TYPE, sourceUri,
                    content, CHUNK_MAX_CHARS, CHUNK_OVERLAP, "bge-m3");
            log.info("Vectorized {}: {} chunks", fileName, stored);
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
