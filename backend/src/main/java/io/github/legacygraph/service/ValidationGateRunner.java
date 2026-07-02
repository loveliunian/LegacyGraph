package io.github.legacygraph.service;

import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.entity.ValidationGate;
import io.github.legacygraph.repository.TestResultRepository;
import io.github.legacygraph.repository.ValidationGateRepository;
import io.github.legacygraph.task.TestExecutionScheduler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 验证门禁执行器（可用版）— 见 doc §验证门禁与图谱回写、§ValidationGateRunner 与现有测试执行链的衔接。
 * <p>
 * 复用现有 {@link TestExecutionScheduler} / {@code ApiTestExecutor} 执行测试类门禁，
 * <b>不另造执行器</b>；测试回写统一走执行级入口（{@code TestResultUpdateService}，写 verifiedScore）。
 * STATIC/MIGRATION 门禁通过受控 {@link ProcessBuilder} 执行命令。
 * </p>
 * <p>门禁结果落 {@code lg_validation_gate}，逐条 PASSED/FAILED。</p>
 *
 * <h3>Phase 0-2 长事务拆分</h3>
 * <p>runGate/runAll 不再标注 @Transactional：命令执行（ProcessBuilder.waitFor）和
 * 测试等待（Thread.sleep 轮询）均不持有数据库连接。
 * 短 TX 的 startGate/finishGate 单独标注。</p>
 */
@Slf4j
@Service
public class ValidationGateRunner {

    private final ValidationGateRepository validationGateRepository;
    private final TestExecutionScheduler testExecutionScheduler;
    private final TestResultRepository testResultRepository;
    private final long resultWaitTimeoutMs;
    private final long resultPollIntervalMs;
    private final TransactionTemplate transactionTemplate;

    /** 命令类门禁超时（秒） */
    private static final long COMMAND_TIMEOUT_SEC = 600;

    public ValidationGateRunner(ValidationGateRepository validationGateRepository,
                                TestExecutionScheduler testExecutionScheduler,
                                TestResultRepository testResultRepository,
                                @Value("${legacy-graph.test.gate-result-timeout-ms:300000}") long resultWaitTimeoutMs,
                                @Value("${legacy-graph.test.gate-result-poll-ms:1000}") long resultPollIntervalMs,
                                TransactionTemplate transactionTemplate) {
        this.validationGateRepository = validationGateRepository;
        this.testExecutionScheduler = testExecutionScheduler;
        this.testResultRepository = testResultRepository;
        this.resultWaitTimeoutMs = resultWaitTimeoutMs;
        this.resultPollIntervalMs = resultPollIntervalMs;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 执行单个门禁并回写结果。
     * <p>Phase 0-2：拆为 startGate（短 TX）→ execute（无 TX）→ finishGate（短 TX）。</p>
     *
     * @param gate     待执行门禁（已落库，含 gateType/command）
     * @param context  执行上下文（projectId/versionId/caseIds/workingDir）
     * @return 更新后的门禁（result=PASSED/FAILED）
     */
    public ValidationGate runGate(ValidationGate gate, GateContext context) {
        // 短 TX：标记 RUNNING
        transactionTemplate.executeWithoutResult(status -> startGate(gate));

        // 长 IO：执行门禁（命令执行、测试等待，无 TX）
        boolean passed;
        try {
            passed = switch (gate.getGateType()) {
                case "STATIC", "MIGRATION" -> runCommandGate(gate, context);
                case "UNIT", "API", "DB", "E2E" -> runTestGate(gate, context);
                default -> {
                    log.warn("Unknown gate type: {}, marking FAILED", gate.getGateType());
                    yield false;
                }
            };
        } catch (Exception e) {
            log.error("Gate {} execution error: {}", gate.getGateType(), e.getMessage());
            passed = false;
        }

        // 短 TX：回写结果
        boolean finalPassed = passed;
        transactionTemplate.executeWithoutResult(status -> finishGate(gate, finalPassed));
        log.info("Gate {} finished: {}", gate.getGateType(), gate.getResult());
        return gate;
    }

    /** 短 TX：标记门禁开始执行 */
    private void startGate(ValidationGate gate) {
        gate.setStartedAt(LocalDateTime.now());
        gate.setResult("RUNNING");
        validationGateRepository.updateById(gate);
    }

    /** 短 TX：回写门禁结果 */
    private void finishGate(ValidationGate gate, boolean passed) {
        gate.setResult(passed ? "PASSED" : "FAILED");
        gate.setFinishedAt(LocalDateTime.now());
        validationGateRepository.updateById(gate);
    }

    /**
     * 批量执行一个任务的所有门禁，返回是否全部通过。
     * 任一门禁失败即整体失败（调用方据此把 ChangeTask 置 VALIDATION_FAILED）。
     * <p>Phase 0-2：移除 @Transactional，内部逐条 runGate 已自行管理短 TX。</p>
     */
    public boolean runAll(String changeTaskId, GateContext context) {
        List<ValidationGate> gates = validationGateRepository.lambdaQuery()
                .eq(ValidationGate::getChangeTaskId, changeTaskId)
                .list();
        boolean allPassed = true;
        for (ValidationGate gate : gates) {
            ValidationGate updated = runGate(gate, context);
            if (!"PASSED".equals(updated.getResult())) {
                allPassed = false;
            }
        }
        log.info("All gates for task {} finished: allPassed={}", changeTaskId, allPassed);
        return allPassed;
    }

    // ==================== 门禁执行体 ====================

    /**
     * 命令类门禁（STATIC/MIGRATION）：受控执行命令，退出码 0 视为通过。
     * 无命令时视为跳过（通过），避免空门禁阻断。
     */
    private boolean runCommandGate(ValidationGate gate, GateContext context) throws Exception {
        String command = gate.getCommand();
        if (command == null || command.isBlank()) {
            log.info("Gate {} has no command, treated as PASS (skipped)", gate.getGateType());
            return true;
        }
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
        if (context != null && context.getWorkingDir() != null) {
            pb.directory(new java.io.File(context.getWorkingDir()));
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.warn("Gate {} command timed out after {}s", gate.getGateType(), COMMAND_TIMEOUT_SEC);
            return false;
        }
        int exit = process.exitValue();
        log.info("Gate {} command exit code: {}", gate.getGateType(), exit);
        return exit == 0;
    }

    /**
     * 测试类门禁（UNIT/API/DB/E2E）：复用 TestExecutionScheduler 执行用例。
     * 回写在 scheduler 内部统一走 TestResultUpdateService（执行级，写 verifiedScore）。
     * 无 caseIds 时视为跳过（通过）。
     */
    private boolean runTestGate(ValidationGate gate, GateContext context) {
        if (context == null || context.getCaseIds() == null || context.getCaseIds().isEmpty()) {
            log.info("Gate {} has no test cases, treated as PASS (skipped)", gate.getGateType());
            return true;
        }
        // 复用现有调度器：提交测试运行（异步执行 + 统一回写）
        String runId = testExecutionScheduler.submitTestRun(
                context.getProjectId(), context.getVersionId(),
                context.getCaseIds(), context.getEnvironment());
        gate.setReportUri("testRun:" + runId);
        log.info("Gate {} submitted test run {}", gate.getGateType(), runId);
        return waitForTestResults(gate, runId, context.getCaseIds().size());
    }

    /**
     * 轮询等待测试结果（无事务：Thread.sleep 不持有 DB 连接）。
     */
    private boolean waitForTestResults(ValidationGate gate, String runId, int expectedCount) {
        long deadline = System.currentTimeMillis() + Math.max(1, resultWaitTimeoutMs);
        long pollMs = Math.max(1, resultPollIntervalMs);

        while (System.currentTimeMillis() <= deadline) {
            List<TestResult> results = testResultRepository.findByExecutionId(runId);
            if (results != null && results.size() >= expectedCount) {
                boolean allPassed = results.stream()
                        .allMatch(r -> "PASSED".equalsIgnoreCase(r.getResultStatus()));
                log.info("Gate {} test run {} completed: results={}, allPassed={}",
                        gate.getGateType(), runId, results.size(), allPassed);
                return allPassed;
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Gate {} interrupted while waiting for test run {}", gate.getGateType(), runId);
                return false;
            }
        }

        log.warn("Gate {} timed out waiting for test run {} results, expectedCount={}",
                gate.getGateType(), runId, expectedCount);
        return false;
    }

    /** 门禁执行上下文 */
    @lombok.Data
    @lombok.Builder
    public static class GateContext {
        private String projectId;
        private String versionId;
        private String workingDir;
        private String environment;
        private List<String> caseIds;
    }
}
