package io.github.legacygraph.understanding.tool.adapter;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * CLI 命令安全策略 —— 管理哪些命令可以在受控环境中执行。
 *
 * <p>安全策略分层：
 * <ol>
 *   <li>命令模板预定义：只允许预注册的命令模板，不接受用户原始 shell</li>
 *   <li>禁止模式匹配：拒绝包含危险操作（rm -rf、git push 等）的命令</li>
 *   <li>工作目录白名单：确保命令只在受信目录内执行</li>
 * </ol>
 *
 * <p>只读约束：所有通过此策略的命令仅限于读取和分析，不允许写操作。
 */
@Slf4j
public class CliCommandPolicy {

    /**
     * 工作目录白名单 —— 只允许在这些目录或子目录内执行命令。
     */
    private final Set<String> workDirWhitelist;

    /**
     * 禁止的正则模式 —— 任何命令参数匹配这些模式都会被拒绝。
     */
    private static final List<Pattern> FORBIDDEN_PATTERNS = List.of(
            // 文件删除 / 破坏性操作
            Pattern.compile("\\brm\\s+.*-rf\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brm\\s+.*-r\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brmdir\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdel(ete)?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdd\\s+if=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmkfs\\.", Pattern.CASE_INSENSITIVE),

            // Git 写操作
            Pattern.compile("\\bgit\\s+commit\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+push\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+merge\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+rebase\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+tag\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+reset\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+stash\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+checkout\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+add\\b", Pattern.CASE_INSENSITIVE),

            // 网络上传 / 数据外泄
            Pattern.compile("\\bcurl\\b.*\\s-d\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcurl\\b.*\\s--data\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcurl\\b.*\\s-F\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcurl\\b.*\\s--upload-file\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwget\\b.*\\s--post-data", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwget\\b.*\\s--post-file", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bscp\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brsync\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnc\\s+.*\\s<\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsocat\\b", Pattern.CASE_INSENSITIVE),

            // 权限提升 / 系统修改
            Pattern.compile("\\bsudo\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsu\\s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchmod\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchown\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmount\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bumount\\b", Pattern.CASE_INSENSITIVE),

            // 反弹 shell / 注入
            Pattern.compile("\\b/bin/bash\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b/bin/sh\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b/dev/tcp/", Pattern.CASE_INSENSITIVE),

            // 任意代码执行重定向
            Pattern.compile("&&\\s*rm\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("`[^`]+`"),  // 反引号命令替换（允许 CLI 工具自己的模板参数如 {query}）
            Pattern.compile("\\$\\([^)]+\\)"),  // $(cmd) 命令替换
            Pattern.compile(";\\s*rm\\b", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 支持的工具及其允许的命令二进制名。
     */
    private static final Map<String, Set<String>> ALLOWED_TOOLS = Map.of(
            "codex", Set.of("codex"),
            "claude-code", Set.of("claude"),
            "zread", Set.of("zread"),
            "claude", Set.of("claude"),
            "grep", Set.of("grep"),
            "find", Set.of("find"),
            "git", Set.of("git")
    );

    /**
     * 构造函数，注入工作目录白名单。
     *
     * @param workDirWhitelist 允许的工作目录集合（由配置提供）
     */
    public CliCommandPolicy(final Set<String> workDirWhitelist) {
        this.workDirWhitelist = workDirWhitelist != null ? workDirWhitelist : Set.of();
    }

    /**
     * 验证命令安全性（预检查，在实际执行前调用）。
     *
     * <p>验证流程：
     * <ol>
     *   <li>工具名是否在已注册工具中</li>
     *   <li>工作目录是否在白名单内</li>
     *   <li>命令二进制名是否在允许列表中</li>
     *   <li>命令参数是否不包含禁止模式</li>
     * </ol>
     *
     * @param toolName 工具注册名（如 "codex", "claude-code"）
     * @param workDir  工作目录路径
     * @param command  完整命令列表（第一个元素为可执行文件路径或名称）
     * @throws SecurityException 如果命令不通过安全策略
     */
    public void validateCommand(final String toolName, final String workDir, final List<String> command) {
        // 1. 工具名检查
        if (toolName == null || !ALLOWED_TOOLS.containsKey(toolName)) {
            throw new SecurityException("未注册的 CLI 工具: " + toolName
                    + " (已注册: " + ALLOWED_TOOLS.keySet() + ")");
        }

        // 2. 工作目录白名单检查（空白名单 = 宽松模式，测试友好）
        if (!workDirWhitelist.isEmpty()) {
            validateWorkDir(workDir);
        }

        // 3. 命令列表检查
        if (command == null || command.isEmpty()) {
            throw new SecurityException("命令列表不能为空");
        }

        // 4. 命令二进制名检查
        final String binaryName = extractBinaryName(command.get(0));
        final Set<String> allowedBinaries = ALLOWED_TOOLS.get(toolName);
        if (!allowedBinaries.contains(binaryName)) {
            throw new SecurityException(
                    "工具 " + toolName + " 不允许执行二进制: " + binaryName
                            + " (允许: " + allowedBinaries + ")");
        }

        // 5. 禁止模式检查 —— 对所有参数拼接为字符串检查
        final String commandLine = String.join(" ", command);
        for (final Pattern pattern : FORBIDDEN_PATTERNS) {
            if (pattern.matcher(commandLine).find()) {
                throw new SecurityException(
                        "命令包含禁止模式: " + pattern.pattern()
                                + " (匹配片段: " + extractMatch(commandLine, pattern) + ")");
            }
        }

        log.debug("CLI 命令通过安全策略: tool={}, cmd={}", toolName, command);
    }

    /**
     * 便捷方法，无 command 参数时仅校验工具名和工作目录。
     *
     * @param toolName 工具注册名
     * @param workDir  工作目录路径
     * @throws SecurityException 如果不通过
     */
    public void validateCommand(final String toolName, final String workDir) {
        validateCommand(toolName, workDir, List.of(extractBinaryName(toolName)));
    }

    /**
     * 提取二进制文件名（去掉路径前缀）。
     */
    private String extractBinaryName(final String pathOrName) {
        if (pathOrName == null || pathOrName.isBlank()) {
            return "";
        }
        final int lastSlash = Math.max(pathOrName.lastIndexOf('/'), pathOrName.lastIndexOf('\\'));
        return lastSlash >= 0 ? pathOrName.substring(lastSlash + 1) : pathOrName;
    }

    /**
     * 验证工作目录在白名单内。
     */
    private void validateWorkDir(final String workDir) {
        if (workDir == null || workDir.isBlank()) {
            throw new SecurityException("工作目录不能为空");
        }
        final Path workPath = Path.of(workDir).toAbsolutePath().normalize();
        for (final String allowed : workDirWhitelist) {
            if (workPath.startsWith(Path.of(allowed).toAbsolutePath().normalize())) {
                return;
            }
        }
        throw new SecurityException(
                "工作目录不在白名单内: " + workDir + " (白名单: " + workDirWhitelist + ")");
    }

    /**
     * 提取匹配片段用于日志。
     */
    private String extractMatch(final String input, final Pattern pattern) {
        final var matcher = pattern.matcher(input);
        if (matcher.find()) {
            return matcher.group();
        }
        return "";
    }
}
