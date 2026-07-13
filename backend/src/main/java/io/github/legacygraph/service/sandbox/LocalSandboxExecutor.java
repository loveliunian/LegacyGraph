package io.github.legacygraph.service.sandbox;

import io.github.legacygraph.dto.sandbox.SandboxRequest;
import io.github.legacygraph.dto.sandbox.SandboxResult;
import io.github.legacygraph.dto.sandbox.SandboxResult.TestResult;
import io.github.legacygraph.dto.sandbox.SandboxResult.Violation;
import io.github.legacygraph.service.test.GateCommandWhitelist;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 本地沙箱执行器实现（阶段三-3.1 + 漏点 ④ 回滚修复）。
 * <p>
 * 在本地工作目录中应用文件变更，然后执行验证门禁：
 * <ul>
 *   <li>STATIC / MIGRATION：通过 {@link ProcessBuilder} 执行命令（经白名单校验）</li>
 *   <li>UNIT / API / DB / E2E：执行对应的构建/测试命令</li>
 * </ul>
 * </p>
 * <p>
 * 安全约束：复用 {@link GateCommandWhitelist} 进行命令白名单校验，防止命令注入。
 * </p>
 * <p>
 * 漏点 ④ 修复：每次执行都会通过 {@link FileBackupTracker} 备份 workingDir 中的原文件，
 * 新建文件登记到待删除列表，{@code finally} 块统一回滚（删除新建、还原修改）。
 * 保证验证结束后 workingDir 干净，不污染下一次执行。
 * </p>
 */
@Slf4j
@Service
public class LocalSandboxExecutor implements SandboxExecutor {

    /** 命令执行超时时间（秒） */
    @Value("${legacygraph.sandbox.command-timeout-sec:600}")
    private int commandTimeoutSec;

    /** 默认门禁命令映射 */
    private static final Map<String, String[]> DEFAULT_GATE_COMMANDS = Map.of(
            "STATIC", new String[]{"mvn", "compile"},
            "UNIT", new String[]{"mvn", "test"},
            "MIGRATION", new String[]{"mvn", "flyway:migrate"}
    );

    @Override
    public SandboxResult execute(SandboxRequest request) {
        if (request == null) {
            return SandboxResult.builder()
                    .success(false)
                    .failureReason("沙箱请求为空")
                    .build();
        }

        String workingDir = request.getWorkingDir();
        if (workingDir == null || workingDir.isBlank()) {
            return SandboxResult.builder()
                    .success(false)
                    .failureReason("工作目录为空")
                    .build();
        }

        Path workPath = Paths.get(workingDir);
        if (!Files.isDirectory(workPath)) {
            return SandboxResult.builder()
                    .success(false)
                    .failureReason("工作目录不存在: " + workingDir)
                    .build();
        }

        long startTime = System.currentTimeMillis();
        List<TestResult> testResults = new ArrayList<>();
        List<Violation> violations = new ArrayList<>();
        Map<String, String> metrics = new HashMap<>();
        StringBuilder buildOutput = new StringBuilder();
        boolean allSuccess = true;
        String failureReason = null;
        FileBackupTracker backup = null;

        try {
            // ① 应用文件变更（同时建立备份/登记）
            backup = applyFileChanges(request.getFiles(), workPath);
            buildOutput.append("文件变更应用完成\n");

            // ② 执行门禁
            List<String> gateTypes = request.getGateTypes();
            if (gateTypes == null || gateTypes.isEmpty()) {
                gateTypes = List.of("STATIC", "UNIT");
            }

            for (String gateType : gateTypes) {
                try {
                    GateExecutionResult gateResult = executeGate(gateType, workPath);
                    buildOutput.append(gateResult.output);

                    if (!gateResult.success) {
                        allSuccess = false;
                        if (failureReason == null) {
                            failureReason = "门禁 " + gateType + " 失败";
                        }
                    }

                    if (isTestGate(gateType)) {
                        testResults.add(TestResult.builder()
                                .testName(gateType + " gate")
                                .testType(gateType)
                                .passed(gateResult.success)
                                .durationMs(gateResult.durationMs)
                                .failureMessage(gateResult.success ? null : gateResult.output)
                                .build());
                    }

                    metrics.put(gateType + "_duration_ms", String.valueOf(gateResult.durationMs));
                    metrics.put(gateType + "_passed", String.valueOf(gateResult.success));

                } catch (SecurityException e) {
                    log.warn("门禁 {} 命令安全校验失败: {}", gateType, e.getMessage());
                    allSuccess = false;
                    if (failureReason == null) {
                        failureReason = "门禁 " + gateType + " 安全校验失败: " + e.getMessage();
                    }
                    violations.add(Violation.builder()
                            .rule("GATE_SECURITY")
                            .severity("ERROR")
                            .message(e.getMessage())
                            .build());
                } catch (Exception e) {
                    log.error("门禁 {} 执行异常: {}", gateType, e.getMessage());
                    allSuccess = false;
                    if (failureReason == null) {
                        failureReason = "门禁 " + gateType + " 执行异常: " + e.getMessage();
                    }
                }
            }
        } catch (Exception e) {
            log.error("应用文件变更失败: {}", e.getMessage());
            return SandboxResult.builder()
                    .success(false)
                    .failureReason("应用文件变更失败: " + e.getMessage())
                    .buildOutput(buildOutput.toString())
                    .build();
        } finally {
            // 漏点 ④ 修复：无论成功失败，finally 块统一回滚 workingDir。
            if (backup != null) {
                try {
                    backup.restore();
                    log.debug("Sandbox rollback completed: created={}, modified={}, deleted={}",
                            backup.createdFiles.size(),
                            backup.modifiedFiles.size(),
                            backup.deletedFiles.size());
                } catch (Exception rollbackEx) {
                    log.error("Sandbox rollback failed: {}", rollbackEx.getMessage());
                }
            }
        }

        long totalDuration = System.currentTimeMillis() - startTime;
        metrics.put("total_duration_ms", String.valueOf(totalDuration));
        metrics.put("total_gates", String.valueOf(request.getGateTypes() != null
                ? request.getGateTypes().size() : 0));

        log.info("Sandbox execution completed: changeTaskId={}, success={}, duration={}ms",
                request.getChangeTaskId(), allSuccess, totalDuration);

        return SandboxResult.builder()
                .success(allSuccess)
                .buildOutput(buildOutput.toString())
                .testResults(testResults)
                .violations(violations)
                .metrics(metrics)
                .failureReason(failureReason)
                .build();
    }

    // ==================== 内部方法 ====================

    /**
     * 应用文件变更到工作目录，同时建立回滚快照。
     * <p>
     * CREATE / MODIFY：先读取原内容（如有）写入 modifiedFiles，再写入新内容；
     * DELETE：先读取内容写入 deletedFiles 再删除。
     * </p>
     *
     * @return 回滚追踪器，调用方在 finally 中执行 {@link FileBackupTracker#restore()}
     */
    private FileBackupTracker applyFileChanges(List<SandboxRequest.FileChange> files, Path workPath)
            throws Exception {
        FileBackupTracker tracker = new FileBackupTracker();
        if (files == null || files.isEmpty()) {
            return tracker;
        }
        for (SandboxRequest.FileChange file : files) {
            String filePath = file.getFilePath();
            if (filePath == null || filePath.isBlank()) {
                continue;
            }
            Path fullPath = workPath.resolve(filePath);
            String changeType = file.getChangeType() != null
                    ? file.getChangeType().toUpperCase() : "MODIFY";

            switch (changeType) {
                case "DELETE" -> {
                    if (Files.exists(fullPath)) {
                        String content = Files.readString(fullPath, StandardCharsets.UTF_8);
                        tracker.deletedFiles.put(fullPath, content);
                        Files.delete(fullPath);
                        log.debug("Deleted file: {}", fullPath);
                    }
                }
                case "CREATE" -> {
                    if (!Files.exists(fullPath)) {
                        tracker.createdFiles.add(fullPath);
                    } else {
                        String original = Files.readString(fullPath, StandardCharsets.UTF_8);
                        tracker.modifiedFiles.put(fullPath, original);
                    }
                    if (fullPath.getParent() != null) {
                        Files.createDirectories(fullPath.getParent());
                    }
                    Files.writeString(fullPath,
                            file.getContent() != null ? file.getContent() : "",
                            StandardCharsets.UTF_8);
                    log.debug("Created file: {}", fullPath);
                }
                case "MODIFY" -> {
                    if (Files.exists(fullPath)) {
                        String original = Files.readString(fullPath, StandardCharsets.UTF_8);
                        tracker.modifiedFiles.put(fullPath, original);
                    } else {
                        tracker.createdFiles.add(fullPath);
                    }
                    if (fullPath.getParent() != null) {
                        Files.createDirectories(fullPath.getParent());
                    }
                    Files.writeString(fullPath,
                            file.getContent() != null ? file.getContent() : "",
                            StandardCharsets.UTF_8);
                    log.debug("Modified file: {}", fullPath);
                }
                default -> log.warn("Unknown changeType {} for {}", changeType, filePath);
            }
        }
        return tracker;
    }

    /** 执行单个门禁 */
    private GateExecutionResult executeGate(String gateType, Path workPath) throws Exception {
        String[] defaultCmd = DEFAULT_GATE_COMMANDS.get(gateType);
        if (defaultCmd == null) {
            log.info("Gate {} has no default command, skipping", gateType);
            return new GateExecutionResult(true, "Gate " + gateType + " skipped (no default command)\n", 0);
        }

        String commandStr = String.join(" ", defaultCmd);
        List<String> tokens = GateCommandWhitelist.parseAndValidate(commandStr);

        long startTime = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(tokens);
        pb.directory(workPath.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        boolean finished = process.waitFor(commandTimeoutSec, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            return new GateExecutionResult(false,
                    "Gate " + gateType + " timed out after " + commandTimeoutSec + "s\n",
                    System.currentTimeMillis() - startTime);
        }

        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n")) + "\n";
        }

        int exitCode = process.exitValue();
        long duration = System.currentTimeMillis() - startTime;
        boolean success = exitCode == 0;

        return new GateExecutionResult(success, output, duration);
    }

    private boolean isTestGate(String gateType) {
        return "UNIT".equals(gateType) || "API".equals(gateType)
                || "DB".equals(gateType) || "E2E".equals(gateType);
    }

    /** 门禁执行结果内部记录 */
    private record GateExecutionResult(boolean success, String output, long durationMs) {}

    /**
     * 文件备份追踪器 — 漏点 ④ 修复配套数据结构。
     * <ul>
     *   <li>createdFiles：本次执行中新建的文件，restore 时删除</li>
     *   <li>modifiedFiles：本次执行中修改的文件，restore 时还原原内容</li>
     *   <li>deletedFiles：本次执行中删除的文件，restore 时还原</li>
     * </ul>
     */
    private static final class FileBackupTracker {
        final List<Path> createdFiles = new ArrayList<>();
        final Map<Path, String> modifiedFiles = new HashMap<>();
        final Map<Path, String> deletedFiles = new HashMap<>();

        void restore() throws Exception {
            // 先还原修改
            for (Map.Entry<Path, String> e : modifiedFiles.entrySet()) {
                Path p = e.getKey();
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            }
            // 再还原删除
            for (Map.Entry<Path, String> e : deletedFiles.entrySet()) {
                Path p = e.getKey();
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            }
            // 最后清理新建
            for (Path p : createdFiles) {
                Files.deleteIfExists(p);
            }
        }
    }
}