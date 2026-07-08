package io.github.legacygraph.task;

import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.entity.TestRun;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;
import io.github.legacygraph.repository.TestRunRepository;
import io.github.legacygraph.service.graph.GraphValidatorService;
import io.github.legacygraph.service.test.TestResultUpdateService;
import io.github.legacygraph.test.ApiTestExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.legacygraph.util.IdUtil;

/**
 * 测试执行调度器
 * 管理并发测试执行，控制并发量，实时更新执行进度
 */
@Slf4j
@Component
public class TestExecutionScheduler {

    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final TestRunRepository testRunRepository;
    private final ApiTestExecutor apiTestExecutor;
    private final GraphValidatorService graphValidatorService;
    private final TestResultUpdateService testResultUpdateService;

    // 线程池配置 - 控制最大并发数
    private final ExecutorService executorService;
    private final Semaphore concurrencySemaphore;

    // 统计
    private final AtomicInteger activeTasks = new AtomicInteger(0);

    // 测试环境 base URL（可通过配置覆盖，见 doc §ValidationGateRunner 与现有测试执行链的衔接）
    @Value("${legacy-graph.test.base-url.dev:http://localhost:8080}")
    private String devBaseUrl;
    @Value("${legacy-graph.test.base-url.test:http://localhost:8080}")
    private String testBaseUrl;
    @Value("${legacy-graph.test.base-url.prod:http://localhost:8080}")
    private String prodBaseUrl;

    @Autowired
    public TestExecutionScheduler(
            TestCaseRepository testCaseRepository,
            TestResultRepository testResultRepository,
            TestRunRepository testRunRepository,
            ApiTestExecutor apiTestExecutor,
            GraphValidatorService graphValidatorService,
            io.github.legacygraph.service.test.TestResultUpdateService testResultUpdateService) {
        this.testCaseRepository = testCaseRepository;
        this.testResultRepository = testResultRepository;
        this.testRunRepository = testRunRepository;
        this.apiTestExecutor = apiTestExecutor;
        this.graphValidatorService = graphValidatorService;
        this.testResultUpdateService = testResultUpdateService;

        // 使用虚拟线程执行测试（Semaphore 控制并发数，默认最大 5）
        int maxConcurrency = 5;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.concurrencySemaphore = new Semaphore(maxConcurrency);
    }

    /**
     * 提交批量测试执行任务
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @param caseIds 测试用例ID列表
     * @param environment 测试环境
     * @return 测试运行ID
     */
    @Transactional
    public String submitTestRun(String projectId, String versionId, List<String> caseIds, String environment) {
        log.info("Submitting test run: projectId={}, versionId={}, caseCount={}", projectId, versionId, caseIds.size());

        String runId = IdUtil.fastUUID();

        // 持久化 TestRun 记录（状态 RUNNING），供测试执行列表查询展示
        TestRun testRun = new TestRun();
        testRun.setId(runId);
        testRun.setProjectId(projectId);
        testRun.setVersionId(versionId);
        testRun.setEnvironment(environment);
        testRun.setStatus("RUNNING");
        testRun.setStartedAt(LocalDateTime.now());
        testRun.setTotalCases(caseIds.size());
        testRun.setPassedCases(0);
        testRun.setFailedCases(0);
        testRunRepository.insert(testRun);

        // 提交异步执行
        executorService.submit(() -> executeTestRun(runId, projectId, versionId, caseIds, environment));

        log.info("Test run submitted: runId={}", runId);
        return runId;
    }

    /**
     * 异步执行测试运行
     */
    @Async
    public void executeTestRun(String runId, String projectId, String versionId, List<String> caseIds, String environment) {
        int passed = 0;
        int failed = 0;
        boolean runFailed = false;
        try {
            concurrencySemaphore.acquire();
            activeTasks.incrementAndGet();

            log.info("Starting test run execution: {}", runId);

            String baseUrl = getBaseUrl(environment);

            List<TestResult> allResults = new ArrayList<>();

            for (String caseId : caseIds) {
                TestCase testCase = testCaseRepository.selectById(caseId);
                if (testCase == null) {
                    log.warn("Test case not found: {}", caseId);
                    failed++;
                    continue;
                }

                try {
                    TestResult result = apiTestExecutor.execute(testCase, baseUrl, runId);
                    allResults.add(result);

                    if ("PASSED".equals(result.getResultStatus())) {
                        passed++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    log.error("Test case execution failed: {}", caseId, e);
                    failed++;
                }

            }

            // 更新图谱置信度（统一回写，见 doc §两套回写服务的确切差异与统一方案）：
            // ① 执行级细粒度回写：按 targetNodeId 写 verifiedScore（唯一测试验证累加字段）
            testResultUpdateService.updateConfidenceByTestResults(runId);
            // ② 版本级报表刷新：按 targetNodeId 邻域精确更新（已修全量扫描/过度降分），并失效缓存
            graphValidatorService.updateConfidenceByTestResults(versionId);

            log.info("Test run completed: {}, passed={}, failed={}", runId, passed, failed);

        } catch (InterruptedException e) {
            runFailed = true;
            log.error("Test run interrupted: {}", runId, e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            runFailed = true;
            log.error("Test run failed: {}", runId, e);
            testResultRepository.updateStatusByExecutionId(runId, "FAILED");
        } finally {
            // 回写 TestRun 终态：通过→COMPLETED，异常→FAILED；并记录通过/失败数与结束时间
            TestRun runUpdate = new TestRun();
            runUpdate.setId(runId);
            runUpdate.setStatus(runFailed ? "FAILED" : "COMPLETED");
            runUpdate.setPassedCases(passed);
            runUpdate.setFailedCases(failed);
            runUpdate.setFinishedAt(LocalDateTime.now());
            try {
                testRunRepository.updateById(runUpdate);
            } catch (Exception e) {
                log.warn("Failed to update TestRun terminal state: runId={}", runId, e);
            }
            concurrencySemaphore.release();
            activeTasks.decrementAndGet();
        }
    }

    /**
     * 重跑失败用例
     */
    @Transactional
    public String rerunFailed(String projectId, String runId) {
        log.info("Rerunning failed cases for run: {}", runId);

        // 获取失败的用例ID
        List<String> failedCaseIds = testResultRepository.findFailedCaseIds(runId);

        String newRunId = IdUtil.fastUUID();

        if (!failedCaseIds.isEmpty()) {
            // Get versionId from first test case - this is a simplified approach
            final String finalVersionId;
            TestCase firstCase = testCaseRepository.selectById(failedCaseIds.get(0));
            if (firstCase != null) {
                finalVersionId = firstCase.getVersionId();
            } else {
                finalVersionId = "";
            }
            // 为重跑创建新的 TestRun 记录（状态 RUNNING）；version_id 为外键，缺失时跳过持久化
            if (finalVersionId != null && !finalVersionId.isEmpty()) {
                TestRun rerun = new TestRun();
                rerun.setId(newRunId);
                rerun.setProjectId(projectId);
                rerun.setVersionId(finalVersionId);
                rerun.setEnvironment("test");
                rerun.setStatus("RUNNING");
                rerun.setStartedAt(LocalDateTime.now());
                rerun.setTotalCases(failedCaseIds.size());
                rerun.setPassedCases(0);
                rerun.setFailedCases(0);
                testRunRepository.insert(rerun);
            }

            executorService.submit(() -> executeTestRun(newRunId, projectId, finalVersionId, failedCaseIds, "test"));
        }

        return newRunId;
    }

    /**
     * 取消测试运行
     */
    @Transactional
    public void cancelTestRun(String runId) {
        testResultRepository.updateStatusByExecutionId(runId, "CANCELLED");
        // 同步将 TestRun 置为 CANCELLED 并记录结束时间
        TestRun cancel = new TestRun();
        cancel.setId(runId);
        cancel.setStatus("CANCELLED");
        cancel.setFinishedAt(LocalDateTime.now());
        testRunRepository.updateById(cancel);
        log.info("Test run cancelled: {}", runId);
    }

    /**
     * 获取当前统计信息
     */
    public TestExecutionStats getStats() {
        TestExecutionStats stats = new TestExecutionStats();
        stats.setActiveTasks(activeTasks.get());
        stats.setAvailablePermits(concurrencySemaphore.availablePermits());
        return stats;
    }

    /**
     * 根据环境获取 base URL（从配置读取，未配置时回退 localhost）。
     */
    private String getBaseUrl(String environment) {
        return switch (environment != null ? environment : "") {
            case "dev" -> devBaseUrl;
            case "test" -> testBaseUrl;
            case "prod" -> prodBaseUrl;
            default -> devBaseUrl;
        };
    }

    /**
     * 统计信息
     */
    @lombok.Data
    public static class TestExecutionStats {
        private int activeTasks;
        private int availablePermits;
    }
}
