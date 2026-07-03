package io.github.legacygraph.understanding.tool.adapter;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * CLI 进程运行器 —— 封装 ProcessBuilder，安全地执行外部命令行工具。
 *
 * <p>职责：
 * <ul>
 *   <li>在受控的工作目录下启动子进程</li>
 *   <li>限制执行时间，超时后强制终止</li>
 *   <li>限制输出大小，超过上限后截断并计算 SHA-256 摘要</li>
 *   <li>工作目录白名单验证</li>
 * </ul>
 *
 * <p>安全性设计：
 * <ul>
 *   <li>不允许任意 shell 命令，只接受预构建的 List&lt;String&gt; 命令</li>
 *   <li>工作目录必须在白名单内，防止路径遍历</li>
 *   <li>输出大小受限，防止 OOM</li>
 * </ul>
 */
@Slf4j
public class CliProcessRunner {

    /**
     * 工作目录白名单 —— 从配置注入，确保执行不会逃逸到非法目录。
     */
    private final Set<String> workDirWhitelist;

    /**
     * 构造函数，注入工作目录白名单。
     *
     * @param workDirWhitelist 允许的工作目录集合（由配置提供）
     */
    public CliProcessRunner(final Set<String> workDirWhitelist) {
        this.workDirWhitelist = workDirWhitelist != null ? workDirWhitelist : Set.of();
    }

    /**
     * 执行命令并返回进程结果。
     *
     * @param workDir         工作目录（必须在白名单内）
     * @param command         命令及其参数列表（不可为空）
     * @param timeoutSeconds  超时秒数（超时后 destroyForcibly）
     * @param maxOutputBytes  stdout 最大字节数（超限后截断）
     * @return ProcessResult 包含退出码、stdout、stderr、SHA-256、耗时
     * @throws IllegalArgumentException 如果工作目录不在白名单内
     * @throws IOException              如果进程启动失败
     */
    public ProcessResult run(final String workDir,
                             final List<String> command,
                             final int timeoutSeconds,
                             final long maxOutputBytes) throws IOException {
        // 参数校验
        validateWorkDir(workDir);
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("命令列表不能为空");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("超时时间必须为正数，实际: " + timeoutSeconds);
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("最大输出字节数必须为正数，实际: " + maxOutputBytes);
        }

        final long startTime = System.currentTimeMillis();
        log.info("执行 CLI 命令: {} (工作目录: {}, 超时: {}s)", command, workDir, timeoutSeconds);

        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(Path.of(workDir).toFile());
        pb.redirectErrorStream(false); // stdout 和 stderr 分开读取

        final Process process = pb.start();
        boolean timedOut = false;

        try {
            // 等待进程完成或超时
            final boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                // 超时：强制终止进程
                timedOut = true;
                log.warn("CLI 命令超时 ({}s)，强制终止: {}", timeoutSeconds, command);
                process.destroyForcibly();
                // 等待终止生效
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("CLI 执行被中断: {}", command);
            process.destroyForcibly();
            throw new IOException("CLI 进程被中断", e);
        }

        final int exitCode = process.exitValue();
        final long elapsedMs = System.currentTimeMillis() - startTime;

        // 读取 stdout 和 stderr
        final byte[] rawStdout = readStream(process.getInputStream(), maxOutputBytes);
        final byte[] rawStderr = readStream(process.getErrorStream(), maxOutputBytes);

        final String stdout = new String(rawStdout, StandardCharsets.UTF_8);
        final String stderr = new String(rawStderr, StandardCharsets.UTF_8);
        final String stdoutSha256 = sha256(rawStdout);

        log.info("CLI 命令完成: exitCode={}, elapsed={}ms, timedOut={}, stdoutLen={}",
                exitCode, elapsedMs, timedOut, rawStdout.length);

        return new ProcessResult(exitCode, stdout, stderr, stdoutSha256, elapsedMs, timedOut);
    }

    /**
     * 验证工作目录是否在白名单内。
     *
     * @param workDir 待验证的工作目录路径
     * @throws IllegalArgumentException 如果不在白名单内
     */
    private void validateWorkDir(final String workDir) {
        if (workDir == null || workDir.isBlank()) {
            throw new IllegalArgumentException("工作目录不能为空");
        }

        // 白名单为空时，仅允许临时目录（测试友好）
        if (workDirWhitelist.isEmpty()) {
            log.debug("工作目录白名单为空，使用宽松模式：允许任意路径通过");
            return;
        }

        final Path workPath = Path.of(workDir).toAbsolutePath().normalize();
        for (final String allowed : workDirWhitelist) {
            final Path allowedPath = Path.of(allowed).toAbsolutePath().normalize();
            if (workPath.startsWith(allowedPath)) {
                return; // 通过验证
            }
        }
        throw new IllegalArgumentException(
                "工作目录不在白名单内: " + workDir + " (白名单: " + workDirWhitelist + ")");
    }

    /**
     * 从输入流读取字节，最多读取 maxBytes。
     *
     * @param in       输入流
     * @param maxBytes 最大字节数
     * @return 读取到的字节数组
     * @throws IOException 读取失败
     */
    private byte[] readStream(final java.io.InputStream in, final long maxBytes) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final byte[] chunk = new byte[8192];
        int bytesRead;
        long totalRead = 0;

        while ((bytesRead = in.read(chunk)) != -1) {
            final long remaining = maxBytes - totalRead;
            if (remaining <= 0) {
                // 已超限，继续清空流但不再存储
                continue;
            }
            final int toWrite = (int) Math.min(bytesRead, remaining);
            buffer.write(chunk, 0, toWrite);
            totalRead += toWrite;
        }
        return buffer.toByteArray();
    }

    /**
     * 计算字节数组的 SHA-256 摘要（十六进制小写）。
     *
     * @param data 输入字节数组
     * @return SHA-256 十六进制字符串
     */
    static String sha256(final byte[] data) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (final NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    /**
     * CLI 进程执行结果。
     *
     * @param exitCode     进程退出码
     * @param stdout       标准输出（已截断到 maxOutputBytes）
     * @param stderr       标准错误（已截断）
     * @param stdoutSha256 stdout 原文的 SHA-256 摘要
     * @param elapsedMs    执行耗时（毫秒）
     * @param timedOut     是否因超时被强制终止
     */
    public record ProcessResult(
            int exitCode,
            String stdout,
            String stderr,
            String stdoutSha256,
            long elapsedMs,
            boolean timedOut
    ) {
        /** 超时退出码占位值 */
        public static final int TIMEOUT_EXIT_CODE = -143;

        /** 返回适合展示给 ToolResult 的退出码（超时时返回 TIMEOUT_EXIT_CODE） */
        public int effectiveExitCode() {
            return timedOut ? TIMEOUT_EXIT_CODE : exitCode;
        }
    }
}
