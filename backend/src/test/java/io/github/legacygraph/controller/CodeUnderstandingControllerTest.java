package io.github.legacygraph.controller;

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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.EnumSet;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CodeUnderstandingController 单元测试 —— 验证 REST API 端点行为。
 *
 * <p>测试场景：
 * <ul>
 *   <li>GET /tool-health 返回 200 和工具列表</li>
 *   <li>POST /reports 创建成功返回 taskId</li>
 *   <li>外部网络请求返回 400</li>
 *   <li>超预算请求（maxToolRuns > 100）返回 400</li>
 * </ul>
 *
 * <p>使用 MockMvcBuilders.standaloneSetup + 自定义 ObjectMapper 以支持 Lombok @Builder/@Data 反序列化。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CodeUnderstandingController REST API 测试")
class CodeUnderstandingControllerTest {

    @Mock
    private ToolHealthService toolHealthService;

    @Mock
    private CodeUnderstandingOrchestrator orchestrator;

    @Mock
    private CodeUnderstandingReportService reportService;

    @InjectMocks
    private CodeUnderstandingController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 配置 ObjectMapper 以支持 Lombok @Builder / @Data 类（Jackson 需要访问字段而非构造函数）
        objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // ========================================================
    // 场景 1：GET /tool-health 返回 200
    // ========================================================

    @Test
    @DisplayName("GET /tool-health 应返回 200 和工具健康列表")
    void shouldReturnToolHealthList() throws Exception {
        // given: 模拟健康检查返回两个工具
        when(toolHealthService.checkAllTools("proj-1")).thenReturn(List.of(
                ToolHealth.builder()
                        .toolName("codebase-memory-mcp")
                        .toolKind(ToolKind.MCP)
                        .status(ToolStatus.READY)
                        .capabilities(EnumSet.of(ToolCapability.SEARCH_SYMBOL, ToolCapability.TRACE_CALL))
                        .indexFreshness("FRESH")
                        .message("MCP 服务就绪")
                        .build(),
                ToolHealth.builder()
                        .toolName("local-fallback")
                        .toolKind(ToolKind.LOCAL)
                        .status(ToolStatus.READY)
                        .capabilities(EnumSet.of(ToolCapability.SEARCH_SYMBOL, ToolCapability.READ_SNIPPET))
                        .indexFreshness("FRESH")
                        .message("本地降级工具就绪")
                        .build()
        ));

        // when & then
        mockMvc.perform(get("/lg/projects/{projectId}/understanding/tool-health", "proj-1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.projectId").value("proj-1"))
                .andExpect(jsonPath("$.data.tools").isArray())
                .andExpect(jsonPath("$.data.tools.length()").value(2))
                .andExpect(jsonPath("$.data.tools[0].toolName").value("codebase-memory-mcp"))
                .andExpect(jsonPath("$.data.tools[0].status").value("READY"))
                .andExpect(jsonPath("$.data.tools[1].toolName").value("local-fallback"));
    }

    // ========================================================
    // 场景 2：POST /reports 创建成功
    // ========================================================

    @Test
    @DisplayName("POST /reports 创建成功应返回 taskId、统计字段和实际工具状态")
    void shouldCreateReportSuccessfully() throws Exception {
        // given: 正常请求
        CodeUnderstandingRequest request = CodeUnderstandingRequest.builder()
                .versionId("v1")
                .question("分析 UserService 的调用链")
                .build();

        CodeUnderstandingTaskResult result = CodeUnderstandingTaskResult.builder()
                .taskId("task-abc-123")
                .status("SUCCESS")
                .reportId("report-abc-123")
                .toolRuns(3)
                .evidenceCount(5)
                .claimCount(2)
                .pendingConfirmCount(1)
                .downloadUrl("/api/lg/projects/proj-1/understanding/reports/task-abc-123/download?format=MD")
                .build();

        when(orchestrator.execute(eq("proj-1"), any(CodeUnderstandingRequest.class)))
                .thenReturn(result);
        when(toolHealthService.checkAllTools("proj-1")).thenReturn(List.of(
                ToolHealth.builder()
                        .toolName("codebase-memory-mcp")
                        .toolKind(ToolKind.MCP)
                        .status(ToolStatus.READY)
                        .build(),
                ToolHealth.builder()
                        .toolName("zread")
                        .toolKind(ToolKind.CLI)
                        .status(ToolStatus.NOT_INSTALLED)
                        .build()
        ));

        // when & then
        mockMvc.perform(post("/lg/projects/{projectId}/understanding/reports", "proj-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-abc-123"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reportId").value("report-abc-123"))
                .andExpect(jsonPath("$.data.toolRuns").value(3))
                .andExpect(jsonPath("$.data.evidenceCount").value(5))
                .andExpect(jsonPath("$.data.claimCount").value(2))
                .andExpect(jsonPath("$.data.pendingConfirmCount").value(1))
                .andExpect(jsonPath("$.data.downloadUrl").value("/api/lg/projects/proj-1/understanding/reports/task-abc-123/download?format=MD"))
                .andExpect(jsonPath("$.data.toolStatus.codebase-memory-mcp").value("READY"))
                .andExpect(jsonPath("$.data.toolStatus.zread").value("NOT_INSTALLED"));
    }

    // ========================================================
    // 场景 3：POST /reports 外部网络请求返回 400
    // ========================================================

    @Test
    @DisplayName("allowExternalNetwork=true 的请求应返回错误码")
    void shouldRejectExternalNetworkRequest() throws Exception {
        // given: 请求中 allowExternalNetwork=true
        CodeUnderstandingRequest request = CodeUnderstandingRequest.builder()
                .versionId("v1")
                .question("分析代码")
                .toolPolicy(new CodeUnderstandingRequest.ToolPolicyDto())
                .build();
        request.getToolPolicy().setAllowExternalNetwork(true);

        // when & then: 不应调用 orchestrator
        mockMvc.perform(post("/lg/projects/{projectId}/understanding/reports", "proj-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value(containsString("不允许外部网络访问")));
    }

    // ========================================================
    // 场景 4：POST /reports 超预算请求返回 400
    // ========================================================

    @Test
    @DisplayName("maxToolRuns > 100 的请求应返回错误码")
    void shouldRejectOverBudgetRequest() throws Exception {
        // given: maxToolRuns 超过 100
        CodeUnderstandingRequest request = CodeUnderstandingRequest.builder()
                .versionId("v1")
                .question("分析代码")
                .toolPolicy(new CodeUnderstandingRequest.ToolPolicyDto())
                .build();
        request.getToolPolicy().setMaxToolRuns(200);

        // when & then
        mockMvc.perform(post("/lg/projects/{projectId}/understanding/reports", "proj-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value(containsString("不能超过 100")));
    }

    // ========================================================
    // 场景 5：GET /reports/{taskId} 查询任务状态
    // ========================================================

    @Test
    @DisplayName("GET /reports/{taskId} 查询已存在的任务应返回 200")
    void shouldQueryExistingTask() throws Exception {
        // given: 先创建一个报告，taskStore 中有记录
        CodeUnderstandingRequest request = CodeUnderstandingRequest.builder()
                .versionId("v1")
                .question("分析代码")
                .build();

        CodeUnderstandingTaskResult result = CodeUnderstandingTaskResult.builder()
                .taskId("task-query-1")
                .status("SUCCESS")
                .reportId("report-query-1")
                .build();

        when(orchestrator.execute(eq("proj-1"), any(CodeUnderstandingRequest.class)))
                .thenReturn(result);

        // 先创建
        mockMvc.perform(post("/lg/projects/{projectId}/understanding/reports", "proj-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // when & then: 查询刚创建的任务
        mockMvc.perform(get("/lg/projects/{projectId}/understanding/reports/{taskId}",
                        "proj-1", "task-query-1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-query-1"));
    }

    @Test
    @DisplayName("GET /reports/{taskId} 查询不存在的任务应返回错误")
    void shouldReturnErrorForNonExistentTask() throws Exception {
        // when & then: 查询不存在的 taskId
        mockMvc.perform(get("/lg/projects/{projectId}/understanding/reports/{taskId}",
                        "proj-1", "nonexistent-task")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("任务不存在"));
    }
}
