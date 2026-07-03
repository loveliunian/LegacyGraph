package io.github.legacygraph.understanding;

import io.github.legacygraph.dto.understanding.CodeUnderstandingTaskResult;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
        when(toolRunRepository.selectList(null)).thenReturn(runs);
        when(toolEvidenceRepository.selectList(null)).thenReturn(List.of());

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
        when(toolRunRepository.selectList(null)).thenReturn(runs);
        when(toolEvidenceRepository.selectList(null)).thenReturn(List.of());

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
        when(toolRunRepository.selectList(null)).thenReturn(runs);
        when(toolEvidenceRepository.selectList(null)).thenReturn(allEvidence);

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
        when(toolRunRepository.selectList(null)).thenReturn(List.of());

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
        when(toolRunRepository.selectList(null)).thenReturn(runs);
        when(toolEvidenceRepository.selectList(null)).thenReturn(confirmedEvidence);

        // when
        String markdown = reportService.generateMarkdown(
                "proj-1", "task-001", taskResult, "分析");

        // then
        assertThat(markdown).contains("## 5. 已确认事实");
        assertThat(markdown).contains("UserService.java");
    }
}
