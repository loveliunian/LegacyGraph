package io.github.legacygraph.understanding;

import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.dto.understanding.CodeUnderstandingRequest;
import io.github.legacygraph.dto.understanding.CodeUnderstandingTaskResult;
import io.github.legacygraph.entity.ToolRunEntity;
import io.github.legacygraph.service.KnowledgeClaimService;
import io.github.legacygraph.understanding.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CodeUnderstandingOrchestrator 单元测试 —— 验证端到端编排逻辑。
 *
 * <p>测试场景：
 * <ul>
 *   <li>MCP 可用时优先走 MCP（通过 ToolRouter 路由）</li>
 *   <li>MCP 不可用时降级到本地 fallback</li>
 *   <li>maxToolRuns 预算耗尽后停止</li>
 *   <li>外部工具异常不影响整体流程</li>
 * </ul>
 *
 * <p>注意：Spring Boot 4.0 移除了 @MockBean，使用纯 Mockito + @InjectMocks。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CodeUnderstandingOrchestrator 编排器测试")
class CodeUnderstandingOrchestratorTest {

    @Mock
    private ToolQueryPlanner toolQueryPlanner;

    @Mock
    private ToolRouter toolRouter;

    @Mock
    private ToolRunRecorder toolRunRecorder;

    @Mock
    private EvidenceNormalizer evidenceNormalizer;

    @Mock
    private KnowledgeClaimService knowledgeClaimService;

    @InjectMocks
    private CodeUnderstandingOrchestrator orchestrator;

    private ToolRunEntity mockRunEntity;

    @BeforeEach
    void setUp() {
        // 构建 mock ToolRunEntity 用于 recordRunStart 返回
        mockRunEntity = new ToolRunEntity();
        mockRunEntity.setId("mock-run-123");

        // 默认模拟 ToolRunRecorder 行为
        lenient().when(toolRunRecorder.recordRunStart(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(mockRunEntity);
        lenient().doNothing().when(toolRunRecorder).recordRunComplete(anyString(), any());
        lenient().doNothing().when(toolRunRecorder).recordEvidence(anyString(), anyList());
    }

    // ========================================================
    // 场景 1：MCP 可用时优先走 MCP
    // ========================================================

    @Test
    @DisplayName("MCP 可用时 ToolRouter 路由到 MCP 适配器执行")
    void shouldRouteToMcpWhenAvailable() {
        // given: ToolQueryPlanner 生成包含 SEARCH_SYMBOL 步骤的计划
        ToolQueryPlanner.PlanStep step = ToolQueryPlanner.PlanStep.builder()
                .phase("STRUCTURED_QUERY")
                .capability(ToolCapability.SEARCH_SYMBOL)
                .description("搜索符号: UserService")
                .parameters(Map.of("symbol", "UserService"))
                .priority(1)
                .build();

        ToolQueryPlanner.ToolQueryPlan plan = new ToolQueryPlanner.ToolQueryPlan(List.of(step));
        when(toolQueryPlanner.plan(any())).thenReturn(plan);

        // 模拟 MCP 适配器可用
        CodeUnderstandingToolAdapter mockMcpAdapter = mock(CodeUnderstandingToolAdapter.class);
        when(mockMcpAdapter.toolName()).thenReturn("codebase-memory-mcp");
        when(mockMcpAdapter.toolKind()).thenReturn(ToolKind.MCP);

        ToolResult successResult = ToolResult.builder()
                .toolName("codebase-memory-mcp")
                .toolKind(ToolKind.MCP)
                .operation(ToolCapability.SEARCH_SYMBOL)
                .status("SUCCESS")
                .exitCode(0)
                .elapsedMs(500L)
                .indexFreshness("FRESH")
                .evidenceRecords(List.of(Map.of(
                        "evidenceType", "SOURCE_SNIPPET",
                        "symbolQn", "com.example.UserService",
                        "sourcePath", "src/main/UserService.java"
                )))
                .build();

        when(mockMcpAdapter.execute(any(ToolRequest.class))).thenReturn(successResult);
        when(toolRouter.route(any(), any())).thenReturn(Optional.of(mockMcpAdapter));

        // 模拟 EvidenceNormalizer 返回结果
        EvidenceNormalizer.NormalizationResult normResult = new EvidenceNormalizer.NormalizationResult(
                List.of(Map.of("result", "ok")), List.of()
        );
        when(evidenceNormalizer.normalize(any(), anyString(), anyString(), anyString()))
                .thenReturn(normResult);

        // 模拟 KnowledgeClaimService
        when(knowledgeClaimService.upsertDraft(any(KnowledgeClaimDraft.class)))
                .thenReturn(null);

        // when
        CodeUnderstandingRequest request = CodeUnderstandingRequest.builder()
                .versionId("v1")
                .question("分析 UserService 的调用链")
                .scope(CodeUnderstandingRequest.Scope.builder()
                        .paths(List.of("src/main/UserService.java"))
                        .symbols(List.of("UserService"))
                        .build())
                .build();

        CodeUnderstandingTaskResult taskResult = orchestrator.execute("proj-1", request);

        // then: MCP 适配器应被调用
        assertThat(taskResult).isNotNull();
        assertThat(taskResult.getStatus()).isEqualTo("SUCCESS");
        verify(mockMcpAdapter, atLeastOnce()).execute(any(ToolRequest.class));
    }

    // ========================================================
    // 场景 2：MCP 不可用时降级
    // ========================================================

    @Test
    @DisplayName("MCP 不可用时应降级（ToolRouter 返回 empty 时 continue）")
    void shouldContinueWhenNoAdapterAvailable() {
        // given: 计划有步骤，但 ToolRouter 返回 empty
        ToolQueryPlanner.PlanStep step = ToolQueryPlanner.PlanStep.builder()
                .phase("STRUCTURED_QUERY")
                .capability(ToolCapability.SEARCH_SYMBOL)
                .description("搜索符号")
                .parameters(Map.of("symbol", "SomeSymbol"))
                .priority(1)
                .build();

        ToolQueryPlanner.ToolQueryPlan plan = new ToolQueryPlanner.ToolQueryPlan(List.of(step));
        when(toolQueryPlanner.plan(any())).thenReturn(plan);

        // ToolRouter 路由失败——无可用适配器
        when(toolRouter.route(any(), any())).thenReturn(Optional.empty());

        // when: 创建报告请求
        CodeUnderstandingRequest request = CodeUnderstandingRequest.builder()
                .versionId("v1")
                .question("分析代码")
                .build();

        CodeUnderstandingTaskResult taskResult = orchestrator.execute("proj-1", request);

        // then: 任务应正常完成（无工具可用的降级场景）
        assertThat(taskResult).isNotNull();
        assertThat(taskResult.getStatus()).isEqualTo("SUCCESS");
        assertThat(taskResult.getToolRuns()).isZero(); // 没有实际执行
    }

    // ========================================================
    // 场景 3：maxToolRuns 预算耗尽后停止
    // ========================================================

    @Test
    @DisplayName("达到 maxToolRuns 预算后应停止后续步骤")
    void shouldStopAtMaxToolRunsBudget() {
        // given: 生成 5 个步骤，但策略只允许 2 次
        List<ToolQueryPlanner.PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            steps.add(ToolQueryPlanner.PlanStep.builder()
                    .phase("STRUCTURED_QUERY")
                    .capability(ToolCapability.SEARCH_SYMBOL)
                    .description("步骤 " + i)
                    .parameters(Map.of("index", i))
                    .priority(i + 1)
                    .build());
        }
        ToolQueryPlanner.ToolQueryPlan plan = new ToolQueryPlanner.ToolQueryPlan(steps);
        when(toolQueryPlanner.plan(any())).thenReturn(plan);

        // 模拟适配器
        CodeUnderstandingToolAdapter mockAdapter = mock(CodeUnderstandingToolAdapter.class);
        when(mockAdapter.toolName()).thenReturn("local-fallback");
        when(mockAdapter.toolKind()).thenReturn(ToolKind.LOCAL);

        ToolResult successResult = ToolResult.builder()
                .toolName("local-fallback")
                .status("SUCCESS")
                .evidenceRecords(List.of())
                .build();
        when(mockAdapter.execute(any(ToolRequest.class))).thenReturn(successResult);

        when(toolRouter.route(any(), any())).thenReturn(Optional.of(mockAdapter));

        // EvidenceNormalizer 返回空
        EvidenceNormalizer.NormalizationResult emptyNorm = new EvidenceNormalizer.NormalizationResult(
                List.of(), List.of()
        );
        when(evidenceNormalizer.normalize(any(), anyString(), anyString(), anyString()))
                .thenReturn(emptyNorm);

        // when: 请求中 maxToolRuns=2
        CodeUnderstandingRequest request = CodeUnderstandingRequest.builder()
                .versionId("v1")
                .question("分析代码")
                .toolPolicy(new CodeUnderstandingRequest.ToolPolicyDto())
                .build();
        request.getToolPolicy().setMaxToolRuns(2);

        CodeUnderstandingTaskResult taskResult = orchestrator.execute("proj-1", request);

        // then: 只执行了 2 次
        assertThat(taskResult).isNotNull();
        assertThat(taskResult.getToolRuns()).isEqualTo(2);
        verify(mockAdapter, times(2)).execute(any(ToolRequest.class));
    }

    @Test
    @DisplayName("pendingConfirmCount 只统计 AI/过期/降级等待确认来源")
    void shouldCountOnlyPendingClaimSources() {
        // given: 一个工具步骤返回两个 Claim，一个确定性 CODE，一个 AI_INFERENCE
        ToolQueryPlanner.PlanStep step = ToolQueryPlanner.PlanStep.builder()
                .phase("STRUCTURED_QUERY")
                .capability(ToolCapability.SEARCH_SYMBOL)
                .description("搜索符号")
                .parameters(Map.of("symbol", "UserService"))
                .priority(1)
                .build();
        when(toolQueryPlanner.plan(any())).thenReturn(new ToolQueryPlanner.ToolQueryPlan(List.of(step)));

        CodeUnderstandingToolAdapter mockAdapter = mock(CodeUnderstandingToolAdapter.class);
        when(mockAdapter.toolName()).thenReturn("local-fallback");
        when(mockAdapter.toolKind()).thenReturn(ToolKind.LOCAL);
        when(mockAdapter.execute(any(ToolRequest.class))).thenReturn(ToolResult.builder()
                .toolName("local-fallback")
                .toolKind(ToolKind.LOCAL)
                .operation(ToolCapability.SEARCH_SYMBOL)
                .status("SUCCESS")
                .indexFreshness("FRESH")
                .evidenceRecords(List.of(Map.of("evidenceType", "SOURCE_SNIPPET")))
                .build());
        when(toolRouter.route(any(), any())).thenReturn(Optional.of(mockAdapter));

        KnowledgeClaimDraft confirmedCodeClaim = KnowledgeClaimDraft.builder()
                .projectId("proj-1")
                .versionId("v1")
                .subjectType("Service")
                .subjectKey("UserService")
                .predicate("CONTAINS")
                .sourceType("CODE")
                .confidence(new BigDecimal("0.95"))
                .build();
        KnowledgeClaimDraft pendingAiClaim = KnowledgeClaimDraft.builder()
                .projectId("proj-1")
                .versionId("v1")
                .subjectType("BusinessRule")
                .subjectKey("rule:candidate")
                .predicate("RELATED_TO")
                .sourceType("AI_INFERENCE")
                .confidence(new BigDecimal("0.90"))
                .build();
        when(evidenceNormalizer.normalize(any(), anyString(), anyString(), anyString()))
                .thenReturn(new EvidenceNormalizer.NormalizationResult(
                        List.of(Map.of("evidenceType", "SOURCE_SNIPPET")),
                        List.of(confirmedCodeClaim, pendingAiClaim)
                ));

        CodeUnderstandingRequest request = CodeUnderstandingRequest.builder()
                .versionId("v1")
                .question("分析代码")
                .build();

        // when
        CodeUnderstandingTaskResult taskResult = orchestrator.execute("proj-1", request);

        // then
        assertThat(taskResult.getClaimCount()).isEqualTo(2);
        assertThat(taskResult.getPendingConfirmCount()).isEqualTo(1);
    }

    // ========================================================
    // 场景 4：工具异常不影响流程
    // ========================================================

    @Test
    @DisplayName("工具执行异常应被捕获，不影响后续步骤")
    void shouldHandleToolExecutionException() {
        // given: 有一个步骤
        ToolQueryPlanner.PlanStep step = ToolQueryPlanner.PlanStep.builder()
                .phase("STRUCTURED_QUERY")
                .capability(ToolCapability.SEARCH_SYMBOL)
                .description("搜索符号")
                .parameters(Map.of("symbol", "WillThrow"))
                .priority(1)
                .build();

        ToolQueryPlanner.ToolQueryPlan plan = new ToolQueryPlanner.ToolQueryPlan(List.of(step));
        when(toolQueryPlanner.plan(any())).thenReturn(plan);

        // 模拟适配器抛异常
        CodeUnderstandingToolAdapter mockAdapter = mock(CodeUnderstandingToolAdapter.class);
        when(mockAdapter.toolName()).thenReturn("buggy-adapter");
        when(mockAdapter.toolKind()).thenReturn(ToolKind.CLI);
        when(mockAdapter.execute(any(ToolRequest.class)))
                .thenThrow(new RuntimeException("Tool crashed!"));

        when(toolRouter.route(any(), any())).thenReturn(Optional.of(mockAdapter));

        // EvidenceNormalizer 不应被调用（因为执行失败）
        EvidenceNormalizer.NormalizationResult emptyNorm = new EvidenceNormalizer.NormalizationResult(
                List.of(), List.of()
        );
        when(evidenceNormalizer.normalize(any(), anyString(), anyString(), anyString()))
                .thenReturn(emptyNorm);

        // when
        CodeUnderstandingRequest request = CodeUnderstandingRequest.builder()
                .versionId("v1")
                .question("分析代码")
                .build();

        CodeUnderstandingTaskResult taskResult = orchestrator.execute("proj-1", request);

        // then: 任务应正常完成，不抛异常
        assertThat(taskResult).isNotNull();
        assertThat(taskResult.getStatus()).isEqualTo("SUCCESS");
    }
}
