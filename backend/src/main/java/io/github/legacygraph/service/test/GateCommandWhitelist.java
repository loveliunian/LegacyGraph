package io.github.legacygraph.service.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 验证门禁命令白名单校验器（H05 RCE 修复）。
 *
 * <p>禁止通过 {@code /bin/sh -c} 执行任意命令，改为：
 * <ol>
 *   <li>按空白符切分命令为 tokens</li>
 *   <li>校验第一个 token（binary）是否在白名单内</li>
 *   <li>用 {@link ProcessBuilder} 直接执行（不走 shell，杜绝管道/重定向/分号注入）</li>
 * </ol>
 *
 * <p>安全约束：
 * <ul>
 *   <li>白名单仅包含构建/测试工具，不含 rm/cp/cat/echo 等系统命令</li>
 *   <li>命令中如含 {@code ;} {@code |} {@code &&} {@code ||} {@code >} {@code <} 等 shell 元字符直接拒绝</li>
 *   <li>所有拒绝均抛 {@link SecurityException}，由调用方决定如何回写门禁结果</li>
 * </ul>
 */
public final class GateCommandWhitelist {

    /** 允许执行的 binary 白名单 */
    private static final Set<String> ALLOWED_BINARIES = Set.of(
            "mvn", "gradle", "gradlew", "npm", "node", "npx", "yarn", "pnpm",
            "git", "make", "cmake", "pytest", "python", "python3",
            "go", "java", "javac", "docker", "dotnet"
    );

    /** 危险 shell 元字符 — 出现任意一个即拒绝（防止命令注入） */
    private static final Set<String> SHELL_METACHARS = Set.of(
            ";", "|", "&&", "||", ">", ">>", "<", "<<", "$(", "`", "&"
    );

    private GateCommandWhitelist() {}

    /**
     * 解析并校验命令，返回可直接传给 {@link ProcessBuilder} 的 token 列表。
     *
     * @param command 原始命令字符串
     * @return 合法的命令 token 列表
     * @throws SecurityException 如果 binary 不在白名单或包含 shell 元字符
     */
    public static List<String> parseAndValidate(String command) {
        if (command == null || command.isBlank()) {
            throw new SecurityException("GATE_COMMAND_EMPTY: command is null or blank");
        }
        String trimmed = command.trim();

        // 1. 检查 shell 元字符注入
        for (String metachar : SHELL_METACHARS) {
            if (trimmed.contains(metachar)) {
                throw new SecurityException(
                        "GATE_SHELL_METACHAR_REJECTED: command contains shell metacharacter '" + metachar + "'");
            }
        }

        // 2. 按空白符切分
        String[] tokens = trimmed.split("\\s+");
        List<String> cmd = new ArrayList<>(tokens.length);
        for (String t : tokens) {
            if (!t.isEmpty()) cmd.add(t);
        }
        if (cmd.isEmpty()) {
            throw new SecurityException("GATE_COMMAND_EMPTY: no tokens after split");
        }

        // 3. 校验 binary（第一个 token，取 basename 去掉路径前缀）
        String binary = cmd.get(0);
        int lastSlash = binary.lastIndexOf('/');
        if (lastSlash >= 0) {
            binary = binary.substring(lastSlash + 1);
        }
        if (!ALLOWED_BINARIES.contains(binary)) {
            throw new SecurityException(
                    "GATE_BINARY_NOT_WHITELISTED: binary '" + binary + "' is not in the allowed list " + ALLOWED_BINARIES);
        }

        return cmd;
    }

    /** 暴露白名单（供测试/审计用） */
    public static Set<String> allowedBinaries() {
        return ALLOWED_BINARIES;
    }
}
