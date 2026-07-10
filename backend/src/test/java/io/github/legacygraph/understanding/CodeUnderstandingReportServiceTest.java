package io.github.legacygraph.understanding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.understanding.CodeUnderstandingTaskResult;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ToolEvidenceEntity;
import io.github.legacygraph.entity.ToolRunEntity;
import io.github.legacygraph.repository.ToolEvidenceRepository;
import io.github.legacygraph.repository.ToolRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * CodeUnderstandingReportService 单元测试 —— 验证 Markdown 报告生成逻辑。
 *
 * <p>测试场景：
 * <ul>
 *   <li>生成的 Markdown 包含工具状态章节</li>
 *   <li>生成的 Markdown 包含证据索引</li>
 *   <li>生成的 Markdown 包含 AI 推断章节</li>
 *   <li>无工具运行时报告包含警告</li>
 * </ul>
 *
 * <p>注意：Spring Boot 4.0 移除了 @MockBean，使用纯 Mockito + @InjectMocks。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CodeUnderstandingReportService 报告生成测试")
class CodeUnderstandingReportServiceTest {

    @Mock
    private ToolRunRepository toolRunRepository;

    @Mock
    private ToolEvidenceRepository toolEvidenceRepository;

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @InjectMocks
    private CodeUnderstandingReportService reportService;

    private CodeUnderstandingTaskResult taskResult;

    @BeforeEach
    void setUp() {
        taskResult = CodeUnderstandingTaskResult.builder()
                .taskId("task-001")
                .status("SUCCESS")
                .reportId("report-001")
                .toolRuns(3)
                .evidenceCount(5)
                .claimCount(2)
                .pendingConfirmCount(1)
                .downloadUrl("/api/projects/proj-1/understanding/reports/task-001/download?format=MD")
                .build();
    }

    /** 构建模拟的 ToolRunEntity */
    private ToolRunEntity createRunEntity(String id, String toolName, String status, Long elapsed) {
        ToolRunEntity run = new ToolRunEntity();
        run.setId(id);
        run.setToolName(toolName);
        run.setOperation("SEARCH_SYMBOL");
        run.setStatus(status);
        run.setElapsedMs(elapsed);
        run.setIndexFreshness("FRESH");
        return run;
    }

    /** 构建模拟的 ToolEvidenceEntity */
    private ToolEvidenceEntity createEvidenceEntity(String type, String path, String symbol, Double confidence) {
        ToolEvidenceEntity ev = new ToolEvidenceEntity();
        ev.setEvidenceType(type);
        ev.setSourcePath(path);
        ev.setSymbolQn(symbol);
        ev.setConfidence(confidence);
        ev.setExcerpt("Sample excerpt for " + type);
        return ev;
    }

    // ========================================================
    // 场景 1：Markdown 包含工具状态章节
    // ========================================================

    @Test
    @DisplayName("生成的 Markdown 应包含「工具运行状态」章节")
    void shouldContainToolStatusSection() {
        // given: 模拟工具运行记录
        List<ToolRunEntity> runs = List.of(
                createRunEntity("run-1", "codebase-memory-mcp", "SUCCESS", 500L),
                createRunEntity("run-2", "codex", "SUCCESS", 1200L),
                createRunEntity("run-3", "local-fallback", "SUCCESS", 100L)
        );
        when(toolRunRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(runs);
        when(toolEvidenceRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        // when
        String markdown = reportService.generateMarkdown(
                "proj-1", "task-001", taskResult, "分析 UserService 的架构");

        // then
        assertThat(markdown).isNotNull();
        assertThat(markdown).contains("## 2. 工具运行状态");
        assertThat(markdown).contains("codebase-memory-mcp");
        assertThat(markdown).contains("codex");
        assertThat(markdown).contains("local-fallback");
        assertThat(markdown).contains("SUCCESS");
    }

    // ========================================================
    // 场景 2：Markdown 包含证据索引
    // ========================================================

    @Test
    @DisplayName("生成的 Markdown 应包含「证据索引」章节，包含工具运行 ID 表格")
    void shouldContainEvidenceIndex() {
        // given: 有工具运行记录
        List<ToolRunEntity> runs = List.of(
                createRunEntity("run-abc", "codebase-memory-mcp", "SUCCESS", 500L)
        );
        when(toolRunRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(runs);
        when(toolEvidenceRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        // when
        String markdown = reportService.generateMarkdown(
                "proj-1", "task-001", taskResult, "分析代码");

        // then
        assertThat(markdown).contains("## 9. 证据索引");
        assertThat(markdown).contains("run-abc");
        assertThat(markdown).contains("codebase-memory-mcp");
    }

    // ========================================================
    // 场景 3：Markdown 包含 AI 推断章节
    // ========================================================

    @Test
    @DisplayName("生成的 Markdown 应包含「AI 推断和待确认候选」章节")
    void shouldContainAiInferenceSection() {
        // given: 有低置信度证据（confidence < 0.85）
        List<ToolRunEntity> runs = List.of(
                createRunEntity("run-1", "codex", "SUCCESS", 800L)
        );
        List<ToolEvidenceEntity> allEvidence = List.of(
                createEvidenceEntity("SUMMARY", "src/main/Analysis.java", "AnalysisResult", 0.7),
                createEvidenceEntity("SOURCE_SNIPPET", "src/main/UserService.java", "UserService", 0.95)
        );
        when(toolRunRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(runs);
        when(toolEvidenceRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(allEvidence);

        // when
        String markdown = reportService.generateMarkdown(
                "proj-1", "task-001", taskResult, "分析代码架构");

        // then: 应包含 AI 推断章节（低置信度证据）
        assertThat(markdown).contains("## 6. AI 推断和待确认候选");
        assertThat(markdown).contains("SUMMARY");
    }

    // ========================================================
    // 场景 4：无工具运行时报告包含警告
    // ========================================================

    @Test
    @DisplayName("无工具运行时，报告应包含警告提示")
    void shouldWarnWhenNoToolRuns() {
        // given: 无工具运行，两个 repo 都返回空
        when(toolRunRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        // when
        String markdown = reportService.generateMarkdown(
                "proj-1", "task-001", taskResult, "分析代码");

        // then
        assertThat(markdown).contains("无工具运行记录，证据不足");
        assertThat(markdown).contains("无证据记录");
    }

    // ========================================================
    // 场景 5：包含已确认事实章节
    // ========================================================

    @Test
    @DisplayName("生成的 Markdown 应包含「已确认事实」章节")
    void shouldContainConfirmedFactsSection() {
        // given: 高置信度证据
        List<ToolRunEntity> runs = List.of(
                createRunEntity("run-1", "codebase-memory-mcp", "SUCCESS", 500L)
        );
        List<ToolEvidenceEntity> confirmedEvidence = List.of(
                createEvidenceEntity("SOURCE_SNIPPET", "src/main/UserService.java",
                        "com.example.UserService", 0.95)
        );
        when(toolRunRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(runs);
        when(toolEvidenceRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(confirmedEvidence);

        // when
        String markdown = reportService.generateMarkdown(
                "proj-1", "task-001", taskResult, "分析");

        // then
        assertThat(markdown).contains("## 5. 已确认事实");
        assertThat(markdown).contains("UserService.java");
    }

    // ========================================================
    // 聚合报告 & 章节级增量
    // ========================================================

    /** 构建模拟的 Feature GraphNode */
    private GraphNode createFeatureNode(String id, String nodeKey, String nodeName,
                                        String displayName, Double confidence, String description) {
        GraphNode node = new GraphNode();
        node.setId(id);
        node.setNodeType(NodeType.Feature.name());
        node.setNodeKey(nodeKey);
        node.setNodeName(nodeName);
        node.setDisplayName(displayName);
        node.setConfidence(confidence != null ? BigDecimal.valueOf(confidence) : null);
        node.setDescription(description);
        node.setProjectId("proj-1");
        node.setVersionId("v1");
        return node;
    }

    // --------------------------------------------------------
    // 场景 6：聚合报告按功能点组织章节
    // --------------------------------------------------------

    @Test
    @DisplayName("generateAggregatedMarkdown 应按功能点组织章节")
    void shouldOrganizeAggregatedReportByFeatures() {
        // given: 两个 Feature 节点
        List<GraphNode> features = List.of(
                createFeatureNode("feat-1", "create-order", "创建订单", "创建订单", 0.9, "下单流程"),
                createFeatureNode("feat-2", "user-login", "用户登录", "用户登录", null, null)
        );
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Feature.name()),
                any(), any(), any(), any(), anyInt())).thenReturn(features);

        // when
        String markdown = reportService.generateAggregatedMarkdown("proj-1", "v1");

        // then
        assertThat(markdown).contains("# 代码理解聚合报告");
        assertThat(markdown).contains("## 功能点概览");
        assertThat(markdown).contains("## 功能点：创建订单");
        assertThat(markdown).contains("## 功能点：用户登录");
        assertThat(markdown).contains("feat-1");
        assertThat(markdown).contains("create-order");
    }

    @Test
    @DisplayName("无 Feature 节点时聚合报告应包含警告")
    void shouldWarnWhenNoFeatureNodes() {
        when(neo4jGraphDao.queryNodes(anyString(), anyString(), eq(NodeType.Feature.name()),
                any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        String markdown = reportService.generateAggregatedMarkdown("proj-1", "v1");

        assertThat(markdown).contains("未找到功能点");
    }

    // --------------------------------------------------------
    // 场景 7：旧报告不存在时全量生成
    // --------------------------------------------------------

    @Test
    @DisplayName("旧报告为空时，增量方法应退化为全量生成")
    void shouldFallbackToFullGenerationWhenNoExistingReport() {
        List<GraphNode> features = List.of(
                createFeatureNode("feat-1", "create-order", "创建订单", "创建订单", 0.9, null)
        );
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Feature.name()),
                any(), any(), any(), any(), anyInt())).thenReturn(features);

        // 旧报告为 null
        String markdown = reportService.generateIncrementalAggregatedMarkdown(
                "proj-1", "v1", Set.of("feat-1"), null);

        assertThat(markdown).contains("# 代码理解聚合报告");
        assertThat(markdown).contains("## 功能点：创建订单");

        // 旧报告为空串也应全量生成
        String markdown2 = reportService.generateIncrementalAggregatedMarkdown(
                "proj-1", "v1", Set.of("feat-1"), "  ");
        assertThat(markdown2).contains("# 代码理解聚合报告");
    }

    @Test
    @DisplayName("受影响节点为空时，增量方法应直接保留旧报告")
    void shouldKeepExistingReportWhenNoAffectedNodes() {
        String oldReport = "# 旧报告\n\n## 功能点：创建订单\n\n旧内容";

        String result = reportService.generateIncrementalAggregatedMarkdown(
                "proj-1", "v1", Set.of(), oldReport);

        assertThat(result).isSameAs(oldReport);
    }

    // --------------------------------------------------------
    // 场景 8：部分功能点变更时仅重写对应章节
    // --------------------------------------------------------

    @Test
    @DisplayName("部分功能点变更时，仅重写受影响章节，保留未变更章节")
    void shouldOnlyRewriteAffectedFeatureSections() {
        // 旧报告：两个功能点章节 + 头部 + 概览
        String oldReport = String.join("\n",
                "# 代码理解聚合报告",
                "",
                "**项目ID:** proj-1",
                "",
                "## 功能点概览",
                "",
                "| # | 功能点 | 节点Key | 置信度 |",
                "|---|--------|---------|--------|",
                "| 1 | 创建订单 | create-order | 0.5 |",
                "| 2 | 用户登录 | user-login | N/A |",
                "",
                "---",
                "",
                "## 功能点：创建订单",
                "",
                "- **节点ID:** feat-old-1",
                "- **置信度:** 0.5",
                "",
                "> 📝 旧内容",
                "",
                "---",
                "",
                "## 功能点：用户登录",
                "",
                "- **节点ID:** feat-2",
                "- **置信度:** N/A",
                "",
                "> 📝 用户登录旧内容（应保留）",
                "");

        // 图谱中两个 Feature 都存在
        List<GraphNode> features = List.of(
                createFeatureNode("feat-new-1", "create-order", "创建订单", "创建订单", 0.95, "更新后描述"),
                createFeatureNode("feat-2", "user-login", "用户登录", "用户登录", null, null)
        );
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Feature.name()),
                any(), any(), any(), any(), anyInt())).thenReturn(features);
        // 受影响节点查询返回创建订单节点
        when(neo4jGraphDao.findNodesByIds(anyList())).thenReturn(List.of(
                createFeatureNode("feat-affected", "create-order", "创建订单", "创建订单", 0.95, null)
        ));

        // when: 仅创建订单功能点受影响
        String result = reportService.generateIncrementalAggregatedMarkdown(
                "proj-1", "v1", Set.of("feat-affected"), oldReport);

        // then: 受影响章节被重写（节点ID变化、置信度更新）
        assertThat(result).contains("## 功能点：创建订单");
        assertThat(result).contains("feat-new-1");
        assertThat(result).contains("0.95");
        assertThat(result).doesNotContain("feat-old-1");

        // 未受影响章节保留
        assertThat(result).contains("## 功能点：用户登录");
        assertThat(result).contains("用户登录旧内容（应保留）");
        assertThat(result).contains("feat-2");

        // 头部和概览保留
        assertThat(result).contains("# 代码理解聚合报告");
        assertThat(result).contains("## 功能点概览");
    }

    // --------------------------------------------------------
    // 场景 9：报告结构无法按章节分割时退化为全量
    // --------------------------------------------------------

    @Test
    @DisplayName("旧报告无二级标题时，退化为全量生成")
    void shouldFallbackToFullWhenReportNotSplittable() {
        // 旧报告无任何 ## 标题
        String oldReport = "这是一段纯文本，没有章节标题。";

        List<GraphNode> features = List.of(
                createFeatureNode("feat-1", "create-order", "创建订单", "创建订单", 0.9, null)
        );
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq(NodeType.Feature.name()),
                any(), any(), any(), any(), anyInt())).thenReturn(features);
        when(neo4jGraphDao.findNodesByIds(anyList())).thenReturn(List.of(
                createFeatureNode("feat-1", "create-order", "创建订单", "创建订单", 0.9, null)
        ));

        String result = reportService.generateIncrementalAggregatedMarkdown(
                "proj-1", "v1", Set.of("feat-1"), oldReport);

        // 退化为全量：包含标准聚合报告头部
        assertThat(result).contains("# 代码理解聚合报告");
        assertThat(result).contains("## 功能点：创建订单");
    }

    // --------------------------------------------------------
    // 辅助方法单元测试
    // --------------------------------------------------------

    @Test
    @DisplayName("splitReportBySections 应按二级标题分割并保持顺序")
    void shouldSplitReportBySections() {
        String markdown = String.join("\n",
                "# 标题",
                "",
                "## 章节A",
                "",
                "内容A",
                "",
                "## 章节B",
                "",
                "内容B",
                "");

        LinkedHashMap<String, String> sections = reportService.splitReportBySections(markdown);

        assertThat(sections).isNotEmpty();
        assertThat(sections).containsKey(CodeUnderstandingReportService.HEADER_SECTION_KEY);
        assertThat(sections.keySet()).hasSize(3); // header + 章节A + 章节B
        assertThat(sections).containsKeys("## 章节A", "## 章节B");
        // 顺序保持
        assertThat(sections.keySet().toArray()[1]).isEqualTo("## 章节A");
        assertThat(sections.keySet().toArray()[2]).isEqualTo("## 章节B");
        assertThat(sections.get("## 章节A")).contains("内容A");
        assertThat(sections.get("## 章节B")).contains("内容B");
    }

    @Test
    @DisplayName("splitReportBySections 对无标题文本应整体返回")
    void shouldReturnWholeSectionWhenNoHeadings() {
        String markdown = "纯文本无标题";

        LinkedHashMap<String, String> sections = reportService.splitReportBySections(markdown);

        assertThat(sections).hasSize(1);
        assertThat(sections).containsKey(CodeUnderstandingReportService.WHOLE_SECTION_KEY);
        assertThat(sections.get(CodeUnderstandingReportService.WHOLE_SECTION_KEY)).isEqualTo(markdown);
    }

    @Test
    @DisplayName("extractFeatureNames 应仅返回 Feature 类型节点的名称")
    void shouldExtractFeatureNamesOnly() {
        GraphNode feature = createFeatureNode("feat-1", "create-order", "创建订单", "创建订单", 0.9, null);
        GraphNode nonFeature = new GraphNode();
        nonFeature.setId("svc-1");
        nonFeature.setNodeType(NodeType.Service.name());
        nonFeature.setNodeName("UserService");

        when(neo4jGraphDao.findNodesByIds(anyList())).thenReturn(List.of(feature, nonFeature));

        Set<String> names = reportService.extractFeatureNames(
                Set.of("feat-1", "svc-1"), neo4jGraphDao);

        assertThat(names).containsExactly("创建订单");
    }

    @Test
    @DisplayName("extractFeatureNames 对空输入应返回空集合")
    void shouldReturnEmptyWhenNoAffectedNodeIds() {
        Set<String> names = reportService.extractFeatureNames(Set.of(), neo4jGraphDao);
        assertThat(names).isEmpty();
    }
}
