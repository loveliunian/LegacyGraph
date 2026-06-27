package io.github.legacygraph.task;

import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;
import io.github.legacygraph.service.GraphValidatorService;
import io.github.legacygraph.test.ApiTestExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试执行调度器
 * 管理并发测试执行，控制并发量，实时更新执行进度
 */
@Slf4j
@Component
public class TestExecutionScheduler {

    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final ApiTestExecutor apiTestExecutor;
    private final GraphValidatorService graphValidatorService;

    // 线程池配置 - 控制最大并发数
    private final ExecutorService executorService;
    private final Semaphore concurrencySemaphore;

    // 统计
    private final AtomicInteger activeTasks = new AtomicInteger(0);

    @Autowired
    public TestExecutionScheduler(
            TestCaseRepository testCaseRepository,
            TestResultRepository testResultRepository,
            ApiTestExecutor apiTestExecutor,
            GraphValidatorService graphValidatorService) {
        this.testCaseRepository = testCaseRepository;
        this.testResultRepository = testResultRepository;
        this.apiTestExecutor = apiTestExecutor;
        this.graphValidatorService = graphValidatorService;

        // 默认最大并发数：可通过配置读取
        int maxConcurrency = 5;
        this.executorService = new ThreadPoolExecutor(
                2, maxConcurrency,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
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

        String runId = UUID.randomUUID().toString();

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
        try {
            concurrencySemaphore.acquire();
            activeTasks.incrementAndGet();

            log.info("Starting test run execution: {}", runId);

            int passed = 0;
            int failed = 0;
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

            // 更新图谱置信度
            graphValidatorService.updateConfidenceByTestResults(versionId);

            log.info("Test run completed: {}, passed={}, failed={}", runId, passed, failed);

        } catch (InterruptedException e) {
            log.error("Test run interrupted: {}", runId, e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Test run failed: {}", runId, e);
            testResultRepository.updateStatusByExecutionId(runId, "FAILED");
        } finally {
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

        String newRunId = UUID.randomUUID().toString();

        if (!failedCaseIds.isEmpty()) {
            // Get versionId from first test case - this is a simplified approach
            final String finalVersionId;
            TestCase firstCase = testCaseRepository.selectById(failedCaseIds.get(0));
            if (firstCase != null) {
                finalVersionId = firstCase.getVersionId();
            } else {
                finalVersionId = "";
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
     * 根据环境获取base URL
     */
    private String getBaseUrl(String environment) {
        // 实际应该从配置或数据库读取
        return switch (environment) {
            case "dev" -> "http://localhost:8080";
            case "test" -> "http://test-api.example.com";
            case "prod" -> "https://api.example.com";
            default -> "http://localhost:8080";
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
