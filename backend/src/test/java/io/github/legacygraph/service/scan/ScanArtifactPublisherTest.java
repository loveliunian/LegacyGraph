package io.github.legacygraph.service.scan;

import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.service.qa.VectorizationService;
import io.github.legacygraph.service.report.ScanPerformanceReportService;
import io.github.legacygraph.service.systemoverview.SystemOverviewDocumentService;
import io.github.legacygraph.understanding.CodeUnderstandingReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScanArtifactPublisher 单元测试 — 验证 publish() 执行顺序、失败不阻塞、产物落盘。
 *
 * <p>mock 策略：所有依赖均 mock，fallbackReportRoot 指向 {@code @TempDir} 避免写到 user.home，
 * codeRepoRepository 返回空列表走回退目录，vectorizationService 标记为不可用跳过向量化。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScanArtifactPublisher 扫描产物发布测试")
class ScanArtifactPublisherTest {

    @Mock private CodeRepoRepository codeRepoRepository;
    @Mock private SystemOverviewDocumentService systemOverviewDocumentService;
    @Mock private ScanPerformanceReportService scanPerformanceReportService;
    @Mock private CodeUnderstandingReportService codeUnderstandingReportService;
    @Mock private ExternalToolEvidenceExporter externalToolEvidenceExporter;
    @Mock private VectorizationService vectorizationService;
    @Mock private GraphQualityAssessor graphQualityAssessor;
    @Mock private EdgeCompletionService edgeCompletionService;
    @Mock private CommunityDetectionService communityDetectionService;

    @TempDir
    Path tempDir;

    private ScanArtifactPublisher newPublisher() {
        ScanArtifactPublisher publisher = new ScanArtifactPublisher(
                codeRepoRepository, systemOverviewDocumentService, scanPerformanceReportService,
                codeUnderstandingReportService, externalToolEvidenceExporter, vectorizationService,
                graphQualityAssessor, edgeCompletionService, communityDetectionService);
        // fallbackReportRoot（@Value 字段）指向 @TempDir，避免写到 user.home
        ReflectionTestUtils.setField(publisher, "fallbackReportRoot", tempDir.toString());
        return publisher;
    }

    /** 无 repo → 走 fallbackDocsDir；向量化标记为不可用。lenient 避免未被使用时报错。 */
    private void stubDocsDir() {
        lenient().when(codeRepoRepository.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(vectorizationService.isAvailable()).thenReturn(false);
    }

    @Test
    @DisplayName("publish 顺序：边补全 → 社区检测 → 质量评估 → 系统总览")
    void publishOrderCompleteEdgesBeforeOverview() throws Exception {
        stubDocsDir();
        when(edgeCompletionService.completeAll(eq("p1"), eq("v1")))
                .thenReturn(new EdgeCompletionService.CompletionReport("p1", "v1"));
        lenient().when(communityDetectionService.detectCommunities("p1"))
                .thenReturn(Map.of("pkg-a", "c1"));
        when(systemOverviewDocumentService.generateMarkdownContent("p1", "v1"))
                .thenReturn("# overview");

        ScanArtifactPublisher publisher = newPublisher();
        publisher.publish("p1", "v1");

        InOrder order = inOrder(edgeCompletionService, communityDetectionService,
                graphQualityAssessor, systemOverviewDocumentService);
        order.verify(edgeCompletionService).completeAll("p1", "v1");
        order.verify(communityDetectionService).detectCommunities("p1");
        order.verify(graphQualityAssessor).assessAndReport("p1", "v1");
        order.verify(systemOverviewDocumentService).generateAfterScan("p1", "v1");
        order.verify(systemOverviewDocumentService).generateMarkdownContent("p1", "v1");
    }

    @Test
    @DisplayName("边补全失败不阻塞后续社区检测与质量评估")
    void edgeCompletionFailureDoesNotBlock() {
        stubDocsDir();
        when(edgeCompletionService.completeAll("p1", "v1"))
                .thenThrow(new RuntimeException("boom"));

        ScanArtifactPublisher publisher = newPublisher();
        publisher.publish("p1", "v1");

        // completeEdges 内部 try/catch 吞掉异常，后续步骤仍被调用
        verify(communityDetectionService).detectCommunities("p1");
        verify(graphQualityAssessor).assessAndReport("p1", "v1");
    }

    @Test
    @DisplayName("system-overview.md 内容非空时落盘到 docs/legacygraph")
    void systemOverviewWrittenWhenMarkdownNonEmpty() throws Exception {
        stubDocsDir();
        when(edgeCompletionService.completeAll("p1", "v1"))
                .thenReturn(new EdgeCompletionService.CompletionReport("p1", "v1"));
        when(systemOverviewDocumentService.generateMarkdownContent("p1", "v1"))
                .thenReturn("# 系统总览\n\n非空内容");

        ScanArtifactPublisher publisher = newPublisher();
        publisher.publish("p1", "v1");

        Path overviewFile = tempDir.resolve("p1").resolve(ScanArtifactPublisher.DOCS_SUBDIR)
                .resolve("system-overview.md");
        assertThat(Files.exists(overviewFile)).as("system-overview.md 应已落盘").isTrue();
        String content = Files.readString(overviewFile);
        assertThat(content).contains("系统总览", "非空内容").isNotEmpty();
    }

    // ──────────── 增量 / 全量向量化 ────────────

    /**
     * 公共桩：走 fallbackDocsDir，向量化可用，仅 system-overview 产出非空内容（其余服务默认返回 null 跳过）。
     */
    private void stubVectorizableSystemOverview(String overviewMarkdown) {
        lenient().when(codeRepoRepository.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(edgeCompletionService.completeAll(eq("p1"), eq("v1")))
                .thenReturn(new EdgeCompletionService.CompletionReport("p1", "v1"));
        lenient().when(vectorizationService.isAvailable()).thenReturn(true);
        when(systemOverviewDocumentService.generateMarkdownContent("p1", "v1"))
                .thenReturn(overviewMarkdown);
    }

    @Test
    @DisplayName("增量模式 publish(..., true) 调用 embedDocumentIncremental，不调用全量 embedDocument / 删除")
    void incrementalModeCallsEmbedDocumentIncremental() {
        String markdown = "# overview\n\nincremental content";
        stubVectorizableSystemOverview(markdown);

        ScanArtifactPublisher publisher = newPublisher();
        publisher.publish("p1", "v1", true);

        String expectedSourceUri = ScanArtifactPublisher.DOCS_SUBDIR + "/system-overview.md";
        verify(vectorizationService).embedDocumentIncremental(
                eq("p1"), eq("v1"), eq(ScanArtifactPublisher.CHUNK_TYPE), eq(expectedSourceUri),
                eq(markdown), eq(1000), eq(100), eq("bge-m3"));
        verify(vectorizationService, never()).embedDocument(
                any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        verify(vectorizationService, never()).deleteBySourceUriAndVersion(any(), any());
        verify(vectorizationService, never()).deleteBySourceUri(any());
    }

    @Test
    @DisplayName("全量模式 publish(...) 默认调用 embedDocument，且先 deleteBySourceUriAndVersion 后 embedDocument")
    void fullModeCallsEmbedDocumentAfterDeleteBySourceUriAndVersion() {
        String markdown = "# overview\n\nfull content";
        stubVectorizableSystemOverview(markdown);

        ScanArtifactPublisher publisher = newPublisher();
        publisher.publish("p1", "v1"); // 默认全量

        String expectedSourceUri = ScanArtifactPublisher.DOCS_SUBDIR + "/system-overview.md";
        InOrder order = inOrder(vectorizationService);
        order.verify(vectorizationService).deleteBySourceUriAndVersion(eq(expectedSourceUri), eq("v1"));
        order.verify(vectorizationService).embedDocument(
                eq("p1"), eq("v1"), eq(ScanArtifactPublisher.CHUNK_TYPE), eq(expectedSourceUri),
                eq(markdown), eq(1000), eq(100), eq("bge-m3"));

        // 确认已迁移到 version 精确删除，不再使用跨版本的 deleteBySourceUri
        verify(vectorizationService, never()).deleteBySourceUri(any());
        verify(vectorizationService, never()).embedDocumentIncremental(
                any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("全量模式 deleteBySourceUriAndVersion 携带 versionId，避免误删其他版本向量")
    void fullModeDeleteScopedByVersionId() {
        stubVectorizableSystemOverview("# overview\n\nscope");

        ScanArtifactPublisher publisher = newPublisher();
        publisher.publish("p1", "v1"); // 全量

        String expectedSourceUri = ScanArtifactPublisher.DOCS_SUBDIR + "/system-overview.md";
        // 必须带 versionId=v1，证明是 version 级精确删除
        verify(vectorizationService).deleteBySourceUriAndVersion(eq(expectedSourceUri), eq("v1"));
        verify(vectorizationService, never()).deleteBySourceUriAndVersion(eq(expectedSourceUri), eq("v2"));
    }

    // ──────────── 章节级增量文档生成 ────────────

    /**
     * 公共桩：走 fallbackDocsDir，向量化标记为不可用，edgeCompletion 不抛异常，
     * extractModuleNames 返回受影响模块名集合。
     */
    private void stubIncrementalContext(Set<String> affectedNodeIds, Set<String> moduleNames) {
        lenient().when(codeRepoRepository.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(edgeCompletionService.completeAll(eq("p1"), eq("v1")))
                .thenReturn(new EdgeCompletionService.CompletionReport("p1", "v1"));
        lenient().when(vectorizationService.isAvailable()).thenReturn(false);
        lenient().when(systemOverviewDocumentService.extractModuleNames("p1", "v1", affectedNodeIds))
                .thenReturn(moduleNames);
    }

    @Test
    @DisplayName("增量模式 publish(projectId, versionId, changedPaths, affectedNodeIds) 调用 generateIncrementalMarkdown")
    void incrementalModeCallsGenerateIncrementalMarkdown() {
        Set<String> affectedNodeIds = Set.of("node-1", "node-2");
        Set<String> moduleNames = Set.of("com.example.order");
        Set<String> changedPaths = Set.of("src/main/java/com/example/order/OrderService.java");
        stubIncrementalContext(affectedNodeIds, moduleNames);
        when(systemOverviewDocumentService.generateIncrementalMarkdown(
                eq("p1"), eq("v1"), eq(moduleNames), any()))
                .thenReturn("# 系统总览\n\n## com.example.order\n\n增量更新内容");

        ScanArtifactPublisher publisher = newPublisher();
        publisher.publish("p1", "v1", changedPaths, affectedNodeIds);

        // 增量模式下必须调用 generateIncrementalMarkdown
        verify(systemOverviewDocumentService).generateIncrementalMarkdown(
                eq("p1"), eq("v1"), eq(moduleNames), any());
        // 增量模式下不应调用全量 generateMarkdownContent
        verify(systemOverviewDocumentService, never()).generateMarkdownContent(any(), any());
    }

    @Test
    @DisplayName("全量模式 publish(projectId, versionId) 调用 generateMarkdownContent，不调用增量方法")
    void fullModeCallsGenerateMarkdownContent() {
        stubDocsDir();
        when(edgeCompletionService.completeAll(eq("p1"), eq("v1")))
                .thenReturn(new EdgeCompletionService.CompletionReport("p1", "v1"));
        when(systemOverviewDocumentService.generateMarkdownContent("p1", "v1"))
                .thenReturn("# 系统总览\n\n全量内容");

        ScanArtifactPublisher publisher = newPublisher();
        publisher.publish("p1", "v1"); // 全量

        verify(systemOverviewDocumentService).generateMarkdownContent("p1", "v1");
        verify(systemOverviewDocumentService, never()).generateIncrementalMarkdown(
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("增量模式 publish(...) 调用 generateIncrementalAggregatedMarkdown")
    void incrementalModeCallsGenerateIncrementalAggregatedMarkdown() {
        Set<String> affectedNodeIds = Set.of("node-1", "node-2");
        Set<String> moduleNames = Set.of("com.example.order");
        Set<String> changedPaths = Set.of("src/main/java/com/example/order/OrderService.java");
        stubIncrementalContext(affectedNodeIds, moduleNames);
        // system-overview 增量返回非空内容避免提前 return
        lenient().when(systemOverviewDocumentService.generateIncrementalMarkdown(
                eq("p1"), eq("v1"), eq(moduleNames), any()))
                .thenReturn("# 系统总览\n\n增量内容");
        when(codeUnderstandingReportService.generateIncrementalAggregatedMarkdown(
                eq("p1"), eq("v1"), eq(affectedNodeIds), any()))
                .thenReturn("# 代码理解聚合报告\n\n## 功能点：下单\n\n增量报告内容");

        ScanArtifactPublisher publisher = newPublisher();
        publisher.publish("p1", "v1", changedPaths, affectedNodeIds);

        // 增量模式下必须调用 generateIncrementalAggregatedMarkdown
        verify(codeUnderstandingReportService).generateIncrementalAggregatedMarkdown(
                eq("p1"), eq("v1"), eq(affectedNodeIds), any());
        // 增量模式下不应调用全量 generateAggregatedMarkdown
        verify(codeUnderstandingReportService, never()).generateAggregatedMarkdown(any(), any());
    }

    @Test
    @DisplayName("全量模式 publish(projectId, versionId) 调用 generateAggregatedMarkdown，不调用增量方法")
    void fullModeCallsGenerateAggregatedMarkdown() {
        stubDocsDir();
        when(edgeCompletionService.completeAll(eq("p1"), eq("v1")))
                .thenReturn(new EdgeCompletionService.CompletionReport("p1", "v1"));
        when(codeUnderstandingReportService.generateAggregatedMarkdown("p1", "v1"))
                .thenReturn("# 代码理解聚合报告\n\n全量报告内容");

        ScanArtifactPublisher publisher = newPublisher();
        publisher.publish("p1", "v1"); // 全量

        verify(codeUnderstandingReportService).generateAggregatedMarkdown("p1", "v1");
        verify(codeUnderstandingReportService, never()).generateIncrementalAggregatedMarkdown(
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("增量模式 extractModuleNames 被调用以从 affectedNodeIds 提取模块名")
    void incrementalModeCallsExtractModuleNames() {
        Set<String> affectedNodeIds = Set.of("node-1", "node-2");
        Set<String> moduleNames = Set.of("com.example.order", "com.example.payment");
        Set<String> changedPaths = Set.of("src/Foo.java");
        stubIncrementalContext(affectedNodeIds, moduleNames);
        when(systemOverviewDocumentService.generateIncrementalMarkdown(
                eq("p1"), eq("v1"), eq(moduleNames), any()))
                .thenReturn("# 系统总览\n\n增量内容");

        ScanArtifactPublisher publisher = newPublisher();
        publisher.publish("p1", "v1", changedPaths, affectedNodeIds);

        // 增量模式必须从 affectedNodeIds 提取模块名
        verify(systemOverviewDocumentService).extractModuleNames("p1", "v1", affectedNodeIds);
    }

    @Test
    @DisplayName("增量模式受影响模块名为空时退化为全量 generateMarkdownContent")
    void incrementalModeWithEmptyModuleNamesFallsBackToFull() {
        Set<String> affectedNodeIds = Set.of("node-1");
        Set<String> changedPaths = Set.of("src/Foo.java");
        // extractModuleNames 返回空集合
        stubIncrementalContext(affectedNodeIds, Collections.emptySet());
        when(systemOverviewDocumentService.generateMarkdownContent("p1", "v1"))
                .thenReturn("# 系统总览\n\n全量降级内容");

        ScanArtifactPublisher publisher = newPublisher();
        publisher.publish("p1", "v1", changedPaths, affectedNodeIds);

        // 模块名为空时不应调用增量生成，应走全量
        verify(systemOverviewDocumentService, never()).generateIncrementalMarkdown(
                any(), any(), any(), any());
        verify(systemOverviewDocumentService).generateMarkdownContent("p1", "v1");
    }

    @Test
    @DisplayName("changedFilePaths 为空时退化为全量模式，不调用 extractModuleNames")
    void emptyChangedFilePathsFallsBackToFull() {
        stubDocsDir();
        when(edgeCompletionService.completeAll(eq("p1"), eq("v1")))
                .thenReturn(new EdgeCompletionService.CompletionReport("p1", "v1"));
        when(systemOverviewDocumentService.generateMarkdownContent("p1", "v1"))
                .thenReturn("# 系统总览\n\n全量内容");

        ScanArtifactPublisher publisher = newPublisher();
        publisher.publish("p1", "v1", Collections.emptySet(), Set.of("node-1"));

        // changedFilePaths 为空 → 全量模式，不调用 extractModuleNames
        verify(systemOverviewDocumentService, never()).extractModuleNames(any(), any(), any());
        verify(systemOverviewDocumentService).generateMarkdownContent("p1", "v1");
    }

    @Test
    @DisplayName("增量模式读取已存在的旧文档并传给 generateIncrementalMarkdown")
    void incrementalModeReadsExistingFileAndPassesToIncrementalGenerator() throws Exception {
        Set<String> affectedNodeIds = Set.of("node-1");
        Set<String> moduleNames = Set.of("com.example.order");
        Set<String> changedPaths = Set.of("src/Foo.java");
        stubIncrementalContext(affectedNodeIds, moduleNames);

        // 预先写入旧文档
        Path docsDir = tempDir.resolve("p1").resolve(ScanArtifactPublisher.DOCS_SUBDIR);
        Files.createDirectories(docsDir);
        String oldContent = "# 系统总览\n\n## 旧内容\n\n旧章节";
        Files.writeString(docsDir.resolve("system-overview.md"), oldContent);

        when(systemOverviewDocumentService.generateIncrementalMarkdown(
                eq("p1"), eq("v1"), eq(moduleNames), eq(oldContent)))
                .thenReturn("# 系统总览\n\n## 增量更新");

        ScanArtifactPublisher publisher = newPublisher();
        publisher.publish("p1", "v1", changedPaths, affectedNodeIds);

        // 验证旧文档内容被读取并传给增量生成方法
        verify(systemOverviewDocumentService).generateIncrementalMarkdown(
                eq("p1"), eq("v1"), eq(moduleNames), eq(oldContent));
    }
}
