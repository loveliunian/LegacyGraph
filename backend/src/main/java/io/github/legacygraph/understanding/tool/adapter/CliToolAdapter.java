package io.github.legacygraph.understanding.tool.adapter;

import io.github.legacygraph.understanding.tool.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * CLI 工具适配器 —— 将外部 CLI 工具（codex / claude-code / zread）接入统一工具接口。
 *
 * <p>适配策略：
 * <ul>
 *   <li>命令模板：从配置读取，执行时替换占位符（如 {query}）</li>
 *   <li>只读模式：所有命令附加 --no-write / --print 等只读标志</li>
 *   <li>健康检查：通过 {@code command -v} 检测二进制是否存在</li>
 *   <li>安全策略：执行前通过 {@link CliCommandPolicy} 验证</li>
 * </ul>
 *
 * <p>配置项（application.yml）：
 * <pre>
 * legacy-graph:
 *   tool:
 *     cli:
 *       tool-name: codex          # 工具名称
 *       command-template: codex exec --no-write --json "{query}"  # 命令模板
 *       timeout-seconds: 180      # 超时时间
 *       max-output-bytes: 200000  # 最大输出字节数
 * </pre>
 */
@Slf4j
public class CliToolAdapter implements CodeUnderstandingToolAdapter {

    /**
     * 工具名称，默认为 "codex"。
     */
    private final String toolName;

    /**
     * 命令模板，如：codex exec --no-write --json "{query}"
     */
    private final String commandTemplate;

    /**
     * 默认超时秒数。
     */
    private final int defaultTimeoutSeconds;

    /**
     * 默认最大输出字节数。
     */
    private final long defaultMaxOutputBytes;

    private final CliProcessRunner processRunner;
    private final CliCommandPolicy commandPolicy;
    private final Set<ToolCapability> capabilities;

    /**
     * 默认 CLI Agent 能力集合（不可变）。
     */
    private static final Set<ToolCapability> DEFAULT_CAPABILITIES = Set.of(
            ToolCapability.READ_SNIPPET,
            ToolCapability.SUMMARIZE,
            ToolCapability.RUN_AGENT_RESEARCH
    );

    /**
     * 构造函数，通过 Spring 依赖注入。
     *
     * @param toolName            工具名称
     * @param commandTemplate     命令模板
     * @param defaultTimeoutSeconds 默认超时时间
     * @param defaultMaxOutputBytes 默认最大输出字节数
     * @param processRunner       CLI 进程运行器
     * @param commandPolicy       CLI 命令安全策略
     */
    public CliToolAdapter(
            final String toolName,
            final String commandTemplate,
            final int defaultTimeoutSeconds,
            final long defaultMaxOutputBytes,
            final CliProcessRunner processRunner,
            final CliCommandPolicy commandPolicy) {
        this(toolName, commandTemplate, defaultTimeoutSeconds, defaultMaxOutputBytes,
                processRunner, commandPolicy, DEFAULT_CAPABILITIES);
    }

    public CliToolAdapter(
            final String toolName,
            final String commandTemplate,
            final int defaultTimeoutSeconds,
            final long defaultMaxOutputBytes,
            final CliProcessRunner processRunner,
            final CliCommandPolicy commandPolicy,
            final Set<ToolCapability> capabilities) {
        this.toolName = toolName;
        this.commandTemplate = commandTemplate;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.defaultMaxOutputBytes = defaultMaxOutputBytes;
        this.processRunner = processRunner;
        this.commandPolicy = commandPolicy;
        this.capabilities = capabilities != null && !capabilities.isEmpty()
                ? Set.copyOf(capabilities)
                : DEFAULT_CAPABILITIES;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toolName() {
        return toolName;
    }

    /**
     * {@inheritDoc}
     * <p>所有 CLI 工具都返回 {@link ToolKind#CLI}。
     */
    @Override
    public ToolKind toolKind() {
        return ToolKind.CLI;
    }

    /**
     * {@inheritDoc}
     *
     * <p>使用 {@code command -v <二进制名>} 检测工具是否已安装。
     * 如果命令返回 0 则认为工具就绪，否则返回 NOT_INSTALLED。
     *
     * @param context 工具上下文（提供工作目录白名单等）
     * @return ToolHealth 状态对象
     */
    @Override
    public ToolHealth checkHealth(final ToolContext context) {
        final String workDir = resolveWorkDir(context);

        try {
            // 使用 command -v 检测二进制是否存在
            // 注意：command -v 是 POSIX shell 内建命令，需要用 sh -c 包装
            final String binaryName = extractBinaryName();
            final List<String> checkCmd = List.of("sh", "-c", "command -v " + binaryName);

            final CliProcessRunner.ProcessResult result = processRunner.run(
                    workDir, checkCmd, 10, 4096);

            if (result.exitCode() == 0 && result.stdout() != null && !result.stdout().isBlank()) {
                log.info("CLI 工具 {} 就绪: {}", toolName, result.stdout().trim());
                return ToolHealth.builder()
                        .toolName(toolName)
                        .toolKind(ToolKind.CLI)
                        .status(ToolStatus.READY)
                        .capabilities(capabilities)
                        .indexFreshness("UNKNOWN")
                        .message("路径: " + result.stdout().trim())
                        .build();
            } else {
                log.info("CLI 工具 {} 未安装: command -v 返回非零", toolName);
                return ToolHealth.builder()
                        .toolName(toolName)
                        .toolKind(ToolKind.CLI)
                        .status(ToolStatus.NOT_INSTALLED)
                        .capabilities(Collections.emptySet())
                        .indexFreshness("UNKNOWN")
                        .message("command -v 未找到 " + binaryName)
                        .build();
            }
        } catch (final Exception e) {
            log.warn("CLI 工具 {} 健康检查异常", toolName, e);
            return ToolHealth.builder()
                    .toolName(toolName)
                    .toolKind(ToolKind.CLI)
                    .status(ToolStatus.UNAVAILABLE)
                    .capabilities(Collections.emptySet())
                    .indexFreshness("UNKNOWN")
                    .message("健康检查失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>CLI 工具提供的能力：代码片段读取、摘要生成、只读 Agent 研究。
     */
    @Override
    public Set<ToolCapability> capabilities() {
        return capabilities;
    }

    /**
     * {@inheritDoc}
     *
     * <p>执行流程：
     * <ol>
     *   <li>从 ToolRequest 提取查询参数和操作类型</li>
     *   <li>构建命令列表（基于配置模板替换占位符）</li>
     *   <li>通过 {@link CliCommandPolicy} 验证安全性</li>
     *   <li>通过 {@link CliProcessRunner} 执行命令</li>
     *   <li>将 ProcessResult 转换为 ToolResult</li>
     * </ol>
     *
     * @param request 工具执行请求
     * @return ToolResult 执行结果
     */
    @Override
    public ToolResult execute(final ToolRequest request) {
        final String workDir = resolveWorkDir(request != null ? request.getWorkingDir() : null);
        final long startTime = System.currentTimeMillis();

        try {
            // 1. 提取查询参数
            final String query = extractQuery(request);
            final ToolCapability operation = request != null ? request.getOperation() : null;

            // 2. 构建命令列表（模板替换）
            final List<String> command = buildCommand(query, operation);

            // 3. 安全策略验证
            commandPolicy.validateCommand(toolName, workDir, command);

            // 4. 执行
            final CliProcessRunner.ProcessResult result = processRunner.run(
                    workDir, command, defaultTimeoutSeconds, defaultMaxOutputBytes);

            // 5. 转换结果
            final String status = resolveStatus(result);
            final long elapsedMs = result.elapsedMs();

            return ToolResult.builder()
                    .toolName(toolName)
                    .toolKind(ToolKind.CLI)
                    .operation(operation)
                    .status(status)
                    .exitCode(result.effectiveExitCode())
                    .elapsedMs(elapsedMs)
                    .indexFreshness("UNKNOWN")
                    .stdoutSha256(result.stdoutSha256())
                    .stdoutExcerpt(truncate(result.stdout(), 500))
                    .errorExcerpt(truncate(result.stderr(), 500))
                    .evidenceRecords(List.of())
                    .build();

        } catch (final SecurityException e) {
            // 安全策略拒绝
            log.warn("CLI 工具 {} 命令被安全策略拒绝: {}", toolName, e.getMessage());
            return ToolResult.builder()
                    .toolName(toolName)
                    .toolKind(ToolKind.CLI)
                    .operation(request != null ? request.getOperation() : null)
                    .status("DENIED")
                    .exitCode(-1)
                    .elapsedMs(System.currentTimeMillis() - startTime)
                    .indexFreshness("UNKNOWN")
                    .errorExcerpt(e.getMessage())
                    .evidenceRecords(List.of())
                    .build();

        } catch (final Exception e) {
            log.error("CLI 工具 {} 执行失败", toolName, e);
            return ToolResult.builder()
                    .toolName(toolName)
                    .toolKind(ToolKind.CLI)
                    .operation(request != null ? request.getOperation() : null)
                    .status("FAILED")
                    .exitCode(-1)
                    .elapsedMs(System.currentTimeMillis() - startTime)
                    .indexFreshness("UNKNOWN")
                    .errorExcerpt(e.getMessage())
                    .evidenceRecords(List.of())
                    .build();
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 从 ToolRequest 提取查询字符串。
     * 优先从 parameters 的 "query" 键读取，否则使用默认值。
     */
    private String extractQuery(final ToolRequest request) {
        if (request == null || request.getParameters() == null) {
            return "";
        }
        final Object queryObj = request.getParameters().get("query");
        return queryObj != null ? queryObj.toString() : "";
    }

    /**
     * 根据命令模板构建实际的命令列表。
     * 先解析模板为命令列表，再将真实 query 作为独立参数追加，
     * 避免将用户输入混入模板字符串解析导致的命令注入风险。
     *
     * <p>模板示例：{@code codex exec --no-write --json "{query}"}
     * 解析后：   {@code [codex, exec, --no-write, --json, 用户查询内容]}
     */
    private List<String> buildCommand(final String query, final ToolCapability operation) {
        // 解析模板为参数列表，{query} 被当作一个普通占位符 token
        final List<String> command = new ArrayList<>(parseCommandLine(commandTemplate));
        // 移除占位符 token，避免模板中残留的 {query} 被当作真实参数
        command.remove("{query}");
        // 将 query 作为独立参数直接追加，不经过模板解析，防止引号注入
        if (query != null && !query.isEmpty()) {
            command.add(query);
        }
        return command;
    }

    /**
     * 简单的命令行解析：按空格分割，保留双引号内的参数。
     */
    static List<String> parseCommandLine(final String commandLine) {
        final List<String> tokens = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean lastWasQuote = false;  // 跟踪上一个字符是否为引号（用于处理空引号 ""）

        for (int i = 0; i < commandLine.length(); i++) {
            final char c = commandLine.charAt(i);
            if (c == '"') {
                if (inQuotes) {
                    // 关闭引号：将当前内容作为一个 token
                    tokens.add(current.toString());
                    current.setLength(0);
                    lastWasQuote = true;
                }
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (lastWasQuote) {
                    // 前一个 token 已通过引号添加，跳过空格
                    lastWasQuote = false;
                } else if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
                lastWasQuote = false;
            }
        }
        // 添加最后一个 token
        if (lastWasQuote) {
            // 空引号已在循环中处理
        } else if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * 从 toolName 提取二进制文件名（去掉路径前缀）。
     */
    private String extractBinaryName() {
        final int lastSlash = Math.max(toolName.lastIndexOf('/'), toolName.lastIndexOf('\\'));
        return lastSlash >= 0 ? toolName.substring(lastSlash + 1) : toolName;
    }

    /**
     * 解析工作目录：从上下文或请求中获取，若都没有则使用系统临时目录。
     */
    private String resolveWorkDir(final ToolContext context) {
        if (context != null && context.getProjectRoot() != null) {
            return context.getProjectRoot();
        }
        return System.getProperty("java.io.tmpdir", "/tmp");
    }

    private String resolveWorkDir(final String workingDir) {
        if (workingDir != null && !workingDir.isBlank()) {
            return workingDir;
        }
        return System.getProperty("java.io.tmpdir", "/tmp");
    }

    /**
     * 将 ProcessResult 映射为 ToolResult 的状态字符串。
     */
    private String resolveStatus(final CliProcessRunner.ProcessResult result) {
        if (result.timedOut()) {
            return "TIMEOUT";
        }
        if (result.exitCode() == 0) {
            return "SUCCESS";
        }
        return "FAILED";
    }

    /**
     * 截断字符串到指定长度。
     */
    private String truncate(final String input, final int maxLen) {
        if (input == null) {
            return null;
        }
        if (input.length() <= maxLen) {
            return input;
        }
        return input.substring(0, maxLen) + "...[截断]";
    }
}
