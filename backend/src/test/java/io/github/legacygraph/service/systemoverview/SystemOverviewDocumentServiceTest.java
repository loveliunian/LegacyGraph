package io.github.legacygraph.service.systemoverview;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.Report;
import io.github.legacygraph.repository.ReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        Neo4jGraphDao graphDao = mock(Neo4jGraphDao.class);
        SystemOverviewDocumentService service = new SystemOverviewDocumentService(
                systemOverviewService, reportRepository, graphDao, reportRoot.toString());

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
        Neo4jGraphDao graphDao = mock(Neo4jGraphDao.class);
        SystemOverviewDocumentService service = new SystemOverviewDocumentService(
                systemOverviewService, reportRepository, graphDao, reportRoot.toString());

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

    // ==================== 章节级增量生成 ====================

    @Test
    void generateIncrementalMarkdown_fullGenerationWhenNoOldDocument() {
        SystemOverviewService systemOverviewService = mock(SystemOverviewService.class);
        ReportRepository reportRepository = mock(ReportRepository.class);
        Neo4jGraphDao graphDao = mock(Neo4jGraphDao.class);
        SystemOverviewDocumentService service = new SystemOverviewDocumentService(
                systemOverviewService, reportRepository, graphDao, reportRoot.toString());

        String newMarkdown = """
                # 系统关系总览报告

                ## 模块: order
                新订单内容

                ## 模块: user
                新用户内容

                ## QA 文档基础
                QA 基础
                """;
        when(systemOverviewService.generateMarkdown("p1", "v1")).thenReturn(newMarkdown);

        // 旧文档为 null → 应全量生成
        String result = service.generateIncrementalMarkdown("p1", "v1", Set.of("order"), null);

        assertNotNull(result);
        assertTrue(result.contains("新订单内容"), "无旧文档时应返回新内容");
        assertTrue(result.contains("新用户内容"));
        assertTrue(result.contains("QA 文档基础"));
    }

    @Test
    void generateIncrementalMarkdown_partialModuleChangePreservesUnchangedSections() {
        SystemOverviewService systemOverviewService = mock(SystemOverviewService.class);
        ReportRepository reportRepository = mock(ReportRepository.class);
        Neo4jGraphDao graphDao = mock(Neo4jGraphDao.class);
        SystemOverviewDocumentService service = new SystemOverviewDocumentService(
                systemOverviewService, reportRepository, graphDao, reportRoot.toString());

        // 新生成的 markdown（模拟本次扫描结果）
        String newMarkdown = """
                # 系统关系总览报告

                ## 模块: order
                新订单内容

                ## 模块: user
                新用户内容

                ## 模块: payment
                新支付内容

                ## QA 文档基础
                QA 基础
                """;
        when(systemOverviewService.generateMarkdown("p1", "v1")).thenReturn(newMarkdown);

        // 旧文档内容
        String oldMarkdown = """
                # 系统关系总览报告

                ## 模块: order
                旧订单内容

                ## 模块: user
                旧用户内容

                ## 模块: payment
                旧支付内容

                ## QA 文档基础
                旧 QA 基础
                """;

        // 仅 order 模块受影响
        String result = service.generateIncrementalMarkdown("p1", "v1", Set.of("order"), oldMarkdown);

        assertNotNull(result);
        // order 章节应为新内容
        assertTrue(result.contains("新订单内容"), "受影响模块的章节应更新为新内容");
        // user 和 payment 章节应保留旧内容
        assertTrue(result.contains("旧用户内容"), "未受影响模块的章节应保留旧内容");
        assertTrue(result.contains("旧支付内容"), "未受影响模块的章节应保留旧内容");
        // 不应包含新用户和新支付内容
        assertFalse(result.contains("新用户内容"), "未受影响模块不应包含新内容");
        assertFalse(result.contains("新支付内容"), "未受影响模块不应包含新内容");
    }

    @Test
    void generateIncrementalMarkdown_allModulesChangedDegradesToFullGeneration() {
        SystemOverviewService systemOverviewService = mock(SystemOverviewService.class);
        ReportRepository reportRepository = mock(ReportRepository.class);
        Neo4jGraphDao graphDao = mock(Neo4jGraphDao.class);
        SystemOverviewDocumentService service = new SystemOverviewDocumentService(
                systemOverviewService, reportRepository, graphDao, reportRoot.toString());

        // mock 不含 QA 文档基础，generateMarkdownContent 会通过 ensureQaFoundationSection 追加
        String newMarkdown = """
                # 系统关系总览报告

                ## 模块: order
                新订单内容

                ## 模块: user
                新用户内容

                ## 模块: payment
                新支付内容
                """;
        when(systemOverviewService.generateMarkdown("p1", "v1")).thenReturn(newMarkdown);

        String oldMarkdown = """
                # 系统关系总览报告

                ## 模块: order
                旧订单内容

                ## 模块: user
                旧用户内容

                ## 模块: payment
                旧支付内容
                """;

        // 所有模块都受影响 → 所有旧章节均被匹配 → 退化为全量生成
        String result = service.generateIncrementalMarkdown("p1", "v1",
                Set.of("order", "user", "payment"), oldMarkdown);

        assertNotNull(result);
        // 所有章节都应为新内容
        assertTrue(result.contains("新订单内容"), "全量重写时所有章节应为新内容");
        assertTrue(result.contains("新用户内容"));
        assertTrue(result.contains("新支付内容"));
        // 不应包含任何旧模块内容
        assertFalse(result.contains("旧订单内容"), "全量重写时不应保留旧内容");
        assertFalse(result.contains("旧用户内容"));
        assertFalse(result.contains("旧支付内容"));
        // ensureQaFoundationSection 追加的 QA 章节也应存在（来自全量生成的新内容）
        assertTrue(result.contains("QA 文档基础"), "全量生成应包含 QA 文档基础章节");
    }

    @Test
    void generateIncrementalMarkdown_emptyAffectedModulesDoesFullGeneration() {
        SystemOverviewService systemOverviewService = mock(SystemOverviewService.class);
        ReportRepository reportRepository = mock(ReportRepository.class);
        Neo4jGraphDao graphDao = mock(Neo4jGraphDao.class);
        SystemOverviewDocumentService service = new SystemOverviewDocumentService(
                systemOverviewService, reportRepository, graphDao, reportRoot.toString());

        String newMarkdown = """
                # 系统关系总览报告

                ## 模块: order
                新订单内容

                ## QA 文档基础
                QA 基础
                """;
        when(systemOverviewService.generateMarkdown("p1", "v1")).thenReturn(newMarkdown);

        String oldMarkdown = """
                # 系统关系总览报告

                ## 模块: order
                旧订单内容

                ## QA 文档基础
                旧 QA 基础
                """;

        // 受影响模块为空 → 全量生成
        String result = service.generateIncrementalMarkdown("p1", "v1", Set.of(), oldMarkdown);

        assertNotNull(result);
        assertTrue(result.contains("新订单内容"), "空受影响模块集应全量生成");
    }

    @Test
    void generateIncrementalMarkdown_noSectionMatchDoesFullGeneration() {
        SystemOverviewService systemOverviewService = mock(SystemOverviewService.class);
        ReportRepository reportRepository = mock(ReportRepository.class);
        Neo4jGraphDao graphDao = mock(Neo4jGraphDao.class);
        SystemOverviewDocumentService service = new SystemOverviewDocumentService(
                systemOverviewService, reportRepository, graphDao, reportRoot.toString());

        String newMarkdown = """
                # 系统关系总览报告

                ## 模块: order
                新订单内容

                ## QA 文档基础
                QA 基础
                """;
        when(systemOverviewService.generateMarkdown("p1", "v1")).thenReturn(newMarkdown);

        String oldMarkdown = """
                # 系统关系总览报告

                ## 模块: order
                旧订单内容

                ## QA 文档基础
                旧 QA 基础
                """;

        // 受影响模块名无法匹配任何章节 → 全量生成
        String result = service.generateIncrementalMarkdown("p1", "v1", Set.of("nonexistent"), oldMarkdown);

        assertNotNull(result);
        assertTrue(result.contains("新订单内容"), "无匹配章节时应全量生成");
    }

    // ==================== 辅助方法测试 ====================

    @Test
    void splitBySections_splitsMarkdownBySectionHeaders() {
        SystemOverviewService systemOverviewService = mock(SystemOverviewService.class);
        ReportRepository reportRepository = mock(ReportRepository.class);
        Neo4jGraphDao graphDao = mock(Neo4jGraphDao.class);
        SystemOverviewDocumentService service = new SystemOverviewDocumentService(
                systemOverviewService, reportRepository, graphDao, reportRoot.toString());

        String markdown = """
                # 系统关系总览报告

                ## 模块: order
                订单内容

                ## 模块: user
                用户内容

                ## QA 文档基础
                QA 内容
                """;

        LinkedHashMap<String, String> sections = service.splitBySections(markdown);

        assertEquals(3, sections.size());
        assertTrue(sections.containsKey("## 模块: order"));
        assertTrue(sections.containsKey("## 模块: user"));
        assertTrue(sections.containsKey("## QA 文档基础"));
        assertTrue(sections.get("## 模块: order").contains("订单内容"));
        assertTrue(sections.get("## 模块: user").contains("用户内容"));
    }

    @Test
    void matchSection_matchesModuleNameToSectionTitle() {
        SystemOverviewService systemOverviewService = mock(SystemOverviewService.class);
        ReportRepository reportRepository = mock(ReportRepository.class);
        Neo4jGraphDao graphDao = mock(Neo4jGraphDao.class);
        SystemOverviewDocumentService service = new SystemOverviewDocumentService(
                systemOverviewService, reportRepository, graphDao, reportRoot.toString());

        Set<String> titles = Set.of("## 模块: order", "## 模块: user", "## QA 文档基础");

        // 完整模块名匹配
        assertEquals("## 模块: order", service.matchSection("com.example.order", titles));
        // 最后一段匹配
        assertEquals("## 模块: order", service.matchSection("order", titles));
        // 无匹配
        assertNull(service.matchSection("nonexistent", titles));
    }

    @Test
    void extractModuleNames_extractsFromAffectedNodes() {
        SystemOverviewService systemOverviewService = mock(SystemOverviewService.class);
        ReportRepository reportRepository = mock(ReportRepository.class);
        Neo4jGraphDao graphDao = mock(Neo4jGraphDao.class);
        SystemOverviewDocumentService service = new SystemOverviewDocumentService(
                systemOverviewService, reportRepository, graphDao, reportRoot.toString());

        GraphNode pkgNode = new GraphNode();
        pkgNode.setId("node-1");
        pkgNode.setNodeType("Package");
        pkgNode.setNodeKey("com.example.order");

        GraphNode classNode = new GraphNode();
        classNode.setId("node-2");
        classNode.setNodeType("Service");
        classNode.setClassName("com.example.user.UserService");
        classNode.setSourcePath("src/main/java/com/example/user/UserService.java");

        when(graphDao.findNodesByIds(any())).thenReturn(List.of(pkgNode, classNode));

        Set<String> moduleNames = service.extractModuleNames("p1", "v1", Set.of("node-1", "node-2"));

        assertEquals(2, moduleNames.size());
        assertTrue(moduleNames.contains("com.example.order"), "Package 节点应提取 nodeKey 作为模块名");
        assertTrue(moduleNames.contains("com.example.user"), "Service 节点应从 className 提取包名");
    }

    @Test
    void extractModuleNames_emptyInputReturnsEmptySet() {
        SystemOverviewService systemOverviewService = mock(SystemOverviewService.class);
        ReportRepository reportRepository = mock(ReportRepository.class);
        Neo4jGraphDao graphDao = mock(Neo4jGraphDao.class);
        SystemOverviewDocumentService service = new SystemOverviewDocumentService(
                systemOverviewService, reportRepository, graphDao, reportRoot.toString());

        Set<String> result = service.extractModuleNames("p1", "v1", Set.of());
        assertTrue(result.isEmpty());

        result = service.extractModuleNames("p1", "v1", null);
        assertTrue(result.isEmpty());
    }

    @Test
    void extractModuleNames_sourcePathFallback() {
        SystemOverviewService systemOverviewService = mock(SystemOverviewService.class);
        ReportRepository reportRepository = mock(ReportRepository.class);
        Neo4jGraphDao graphDao = mock(Neo4jGraphDao.class);
        SystemOverviewDocumentService service = new SystemOverviewDocumentService(
                systemOverviewService, reportRepository, graphDao, reportRoot.toString());

        GraphNode node = new GraphNode();
        node.setId("node-1");
        node.setNodeType("Controller");
        node.setSourcePath("src/main/java/com/example/payment/PaymentController.java");

        when(graphDao.findNodesByIds(any())).thenReturn(List.of(node));

        Set<String> moduleNames = service.extractModuleNames("p1", "v1", Set.of("node-1"));

        assertEquals(1, moduleNames.size());
        assertTrue(moduleNames.contains("com.example.payment"),
                "应从 sourcePath 提取包名（去掉 src/main/java 前缀后转换路径分隔符）");
    }
}
