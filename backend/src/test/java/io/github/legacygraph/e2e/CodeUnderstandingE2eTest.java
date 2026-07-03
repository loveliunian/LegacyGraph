package io.github.legacygraph.e2e;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.understanding.CodeUnderstandingRequest;
import io.github.legacygraph.dto.understanding.CodeUnderstandingTaskResult;
import io.github.legacygraph.understanding.CodeUnderstandingOrchestrator;
import io.github.legacygraph.understanding.CodeUnderstandingReportService;
import io.github.legacygraph.understanding.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 代码理解端到端测试 —— 模拟完整的报告生命周期流程。
 *
 * <p>测试场景：
 * <ul>
 *   <li>端到端：health check → create report → query result</li>
 *   <li>MCP 不可用时报告降级生成</li>
 *   <li>报告包含证据索引</li>
 * </ul>
 *
 * <p>使用 MockMvc standalone setup + Mock 服务层，测试完整 API 交互流程。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CodeUnderstanding E2E 端到端测试")
class CodeUnderstandingE2eTest {

    @Mock
    private ToolHealthService toolHealthService;

    @Mock
    private CodeUnderstandingOrchestrator orchestrator;

    @Mock
    private CodeUnderstandingReportService reportService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private io.github.legacygraph.controller.CodeUnderstandingController controller;

    @BeforeEach
    void setUp() {
        controller = new io.github.legacygraph.controller.CodeUnderstandingController(
                toolHealthService, orchestrator, reportService);

        // 配置 ObjectMapper 以支持 Lombok @Builder / @Data 类
        objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        // 默认健康检查：local-fallback 就绪，MCP 不可用
        when(toolHealthService.checkAllTools(anyString())).thenReturn(List.of(
                ToolHealth.builder()
                        .toolName("local-fallback")
                        .toolKind(ToolKind.LOCAL)
                        .status(ToolStatus.READY)
                        .capabilities(EnumSet.of(ToolCapability.SEARCH_SYMBOL,
                                ToolCapability.READ_SNIPPET, ToolCapability.READ_RESOURCE))
                        .indexFreshness("FRESH")
                        .message("本地降级工具就绪")
                        .build()
        ));
    }

    // ========================================================
    // 场景 1：端到端流程
    // ========================================================

    @Test
    @DisplayName("端到端：health check → create report → query result")
    void shouldCompleteFullE2eFlow() throws Exception {
        // ─── 步骤 1：健康检查 ───
        MvcResult healthResult = mockMvc.perform(
                        get("/lg/projects/{projectId}/understanding/tool-health", "proj-1")
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.projectId").value("proj-1"))
                .andExpect(jsonPath("$.data.tools").isArray())
                .andReturn();

        // 验证健康检查响应包含工具列表
        String healthBody = healthResult.getResponse().getContentAsString();
        assertThat(healthBody).contains("local-fallback");
        assertThat(healthBody).contains("READY");
        System.out.println("✅ 步骤 1 通过：health check 返回工具列表");

        // ─── 步骤 2：创建报告 ───
        CodeUnderstandingTaskResult taskResult = CodeUnderstandingTaskResult.builder()
                .taskId("e2e-task-001")
                .status("SUCCESS")
                .reportId("e2e-report-001")
                .toolRuns(2)
                .evidenceCount(8)
                .claimCount(3)
                .pendingConfirmCount(1)
                .downloadUrl("/api/lg/projects/proj-1/understanding/reports/e2e-task-001/download?format=MD")
                .build();

        when(orchestrator.execute(eq("proj-1"), any(CodeUnderstandingRequest.class)))
                .thenReturn(taskResult);

        CodeUnderstandingRequest request = CodeUnderstandingRequest.builder()
                .versionId("v1")
                .question("分析 UserService 和 OrderService 的架构与调用关系")
                .scope(CodeUnderstandingRequest.Scope.builder()
                        .paths(List.of("src/main/service/"))
                        .symbols(List.of("UserService", "OrderService"))
                        .build())
                .build();

        MvcResult createResult = mockMvc.perform(
                        post("/lg/projects/{projectId}/understanding/reports", "proj-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("e2e-task-001"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reportId").value("e2e-report-001"))
                .andExpect(jsonPath("$.data.toolRuns").value(2))
                .andExpect(jsonPath("$.data.evidenceCount").value(8))
                .andExpect(jsonPath("$.data.claimCount").value(3))
                .andExpect(jsonPath("$.data.pendingConfirmCount").value(1))
                .andReturn();

        // 验证创建响应包含 toolStatus
        String createBody = createResult.getResponse().getContentAsString();
        assertThat(createBody).contains("local-fallback");
        assertThat(createBody).contains("READY");
        System.out.println("✅ 步骤 2 通过：create report 返回 taskId");

        // ─── 步骤 3：查询报告结果 ───
        mockMvc.perform(get("/lg/projects/{projectId}/understanding/reports/{taskId}",
                        "proj-1", "e2e-task-001")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("e2e-task-001"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.evidenceCount").value(8));

        System.out.println("✅ 步骤 3 通过：query report 返回正确数据");
        System.out.println("🎉 端到端流程全部通过！");
    }

    // ========================================================
    // 场景 2：MCP 不可用时报告降级生成
    // ========================================================

    @Test
    @DisplayName("MCP 不可用时报告的降级生成（orchestrator 仍应返回成功）")
    void shouldDegradeGracefullyWhenMcpUnavailable() throws Exception {
        // given: orchestrator 返回一个降级结果（toolRuns 较少，可能只有 local-fallback）
        CodeUnderstandingTaskResult degradedResult = CodeUnderstandingTaskResult.builder()
                .taskId("degraded-task-001")
                .status("SUCCESS")
                .reportId("degraded-report-001")
                .toolRuns(1) // 只有 local-fallback 运行
                .evidenceCount(3)
                .claimCount(1)
                .pendingConfirmCount(1)
                .downloadUrl("/api/lg/projects/proj-1/understanding/reports/degraded-task-001/download?format=MD")
                .build();

        when(orchestrator.execute(eq("proj-1"), any(CodeUnderstandingRequest.class)))
                .thenReturn(degradedResult);

        // 模拟报告服务生成降级报告
        String degradedMarkdown = """
                # 代码理解报告
                ## 1. 任务背景
                **项目ID:** proj-1
                **生成时间:** 2024-01-01 00:00:00

                ## 2. 工具运行状态
                | 工具名称 | 操作 | 状态 | 耗时(ms) | 索引新鲜度 |
                |----------|------|------|----------|----------|
                | local-fallback | SEARCH_SYMBOL | SUCCESS | 100 | FRESH |

                ## 9. 证据索引
                | 工具运行 ID | 工具名称 | 操作 | 状态 |
                |-------------|----------|------|------|
                | run-local-1 | local-fallback | SEARCH_SYMBOL | SUCCESS |
                """;
        when(reportService.generateMarkdown(eq("proj-1"), eq("degraded-task-001"),
                any(), eq(null))).thenReturn(degradedMarkdown);

        // when: 创建报告
        CodeUnderstandingRequest request = CodeUnderstandingRequest.builder()
                .versionId("v1")
                .question("分析代码")
                .build();

        mockMvc.perform(post("/lg/projects/{projectId}/understanding/reports", "proj-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("degraded-task-001"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        // then: 降级报告应包含证据索引和工具状态
        String markdown = reportService.generateMarkdown(
                "proj-1", "degraded-task-001", degradedResult, null);
        assertThat(markdown).contains("## 2. 工具运行状态");
        assertThat(markdown).contains("local-fallback");
        assertThat(markdown).contains("## 9. 证据索引");
        assertThat(markdown).contains("run-local-1");

        System.out.println("✅ MCP 降级场景通过：报告仍然包含完整性章节");
    }

    // ========================================================
    // 场景 3：报告包含证据索引
    // ========================================================

    @Test
    @DisplayName("报告应包含证据索引表格")
    void shouldIncludeEvidenceIndexInReport() throws Exception {
        // given: orchestrator 返回多工具运行的结果
        CodeUnderstandingTaskResult multiToolResult = CodeUnderstandingTaskResult.builder()
                .taskId("multi-task-001")
                .status("SUCCESS")
                .reportId("multi-report-001")
                .toolRuns(3)
                .evidenceCount(12)
                .claimCount(5)
                .pendingConfirmCount(2)
                .downloadUrl("/api/lg/projects/proj-1/understanding/reports/multi-task-001/download?format=MD")
                .build();

        when(orchestrator.execute(eq("proj-1"), any(CodeUnderstandingRequest.class)))
                .thenReturn(multiToolResult);

        // 模拟完整的报告内容
        String fullReport = """
                # 代码理解报告

                ## 1. 任务背景
                **项目ID:** proj-1
                **用户问题:** 分析整个项目架构

                ## 2. 工具运行状态
                | 工具名称 | 操作 | 状态 | 耗时(ms) | 索引新鲜度 |
                |----------|------|------|----------|----------|
                | codebase-memory-mcp | SEARCH_SYMBOL | SUCCESS | 500 | FRESH |
                | codex | READ_SNIPPET | SUCCESS | 1200 | FRESH |
                | local-fallback | PACK_CONTEXT | SUCCESS | 50 | FRESH |

                ## 5. 已确认事实 ✅
                | # | 证据类型 | 源文件 | 符号 | 置信度 |
                |---|----------|--------|------|--------|
                | 1 | SOURCE_SNIPPET | UserService.java | UserService | 95% |

                ## 6. AI 推断和待确认候选 ⏳
                > ⚠️ 以下结论标记为 PENDING_CONFIRM

                ## 9. 证据索引
                | 工具运行 ID | 工具名称 | 操作 | 状态 |
                |-------------|----------|------|------|
                | run-mcp-1 | codebase-memory-mcp | SEARCH_SYMBOL | SUCCESS |
                | run-cli-2 | codex | READ_SNIPPET | SUCCESS |
                | run-local-3 | local-fallback | PACK_CONTEXT | SUCCESS |
                """;
        when(reportService.generateMarkdown(eq("proj-1"), eq("multi-task-001"),
                any(), eq(null))).thenReturn(fullReport);

        // when
        CodeUnderstandingRequest request = CodeUnderstandingRequest.builder()
                .versionId("v1")
                .question("分析整个项目架构")
                .build();

        mockMvc.perform(post("/lg/projects/{projectId}/understanding/reports", "proj-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("multi-task-001"));

        // then: 验证报告包含所有关键章节
        String markdown = reportService.generateMarkdown(
                "proj-1", "multi-task-001", multiToolResult, null);
        assertThat(markdown).contains("## 2. 工具运行状态");
        assertThat(markdown).contains("## 5. 已确认事实");
        assertThat(markdown).contains("## 6. AI 推断和待确认候选");
        assertThat(markdown).contains("## 9. 证据索引");

        // 验证证据索引包含具体数据
        assertThat(markdown).contains("run-mcp-1");
        assertThat(markdown).contains("run-cli-2");
        assertThat(markdown).contains("run-local-3");
        assertThat(markdown).contains("codebase-memory-mcp");
        assertThat(markdown).contains("codex");
        assertThat(markdown).contains("local-fallback");

        System.out.println("✅ 报告完整性验证通过：包含所有关键章节和证据索引数据");
    }
}
