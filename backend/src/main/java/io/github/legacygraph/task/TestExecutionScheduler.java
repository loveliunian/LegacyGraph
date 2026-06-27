package io.github.legacygraph.task;

import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestRun;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestRunRepository;
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

    private final TestRunRepository testRunRepository;
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
            TestRunRepository testRunRepository,
            TestCaseRepository testCaseRepository,
            TestResultRepository testResultRepository,
            ApiTestExecutor apiTestExecutor,
            GraphValidatorService graphValidatorService) {
        this.testRunRepository = testRunRepository;
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

        // 创建测试运行记录
        TestRun testRun = new TestRun();
        testRun.setId(UUID.randomUUID().toString());
        testRun.setProjectId(projectId);
        testRun.setVersionId(versionId);
        testRun.setEnvironment(environment);
        testRun.setStatus("SCHEDULED");
        testRun.setTotalCases(caseIds.size());
        testRun.setPassedCases(0);
        testRun.setFailedCases(0);
        testRun.setStartedAt(LocalDateTime.now());
        testRunRepository.save(testRun);

        // 提交异步执行
        executorService.submit(() -> executeTestRun(testRun.getId(), projectId, caseIds, environment));

        log.info("Test run submitted: runId={}", testRun.getId());
        return testRun.getId();
    }

    /**
     * 异步执行测试运行
     */
    @Async
    public void executeTestRun(String runId, String projectId, List<String> caseIds, String environment) {
        try {
            concurrencySemaphore.acquire();
            activeTasks.incrementAndGet();

            TestRun testRun = testRunRepository.selectById(runId);
            if (testRun == null) {
                log.error("Test run not found: {}", runId);
                return;
            }

            // 更新状态为运行中
            testRun.setStatus("RUNNING");
            testRunRepository.updateById(testRun);
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

                // 更新进度
                synchronized (this) {
                    TestRun current = testRunRepository.selectById(runId);
                    current.setPassedCases(passed);
                    current.setFailedCases(failed);
                    testRunRepository.updateById(current);
                }
            }

            // 更新最终状态
            TestRun finalRun = testRunRepository.selectById(runId);
            finalRun.setPassedCases(passed);
            finalRun.setFailedCases(failed);
            finalRun.setStatus("COMPLETED");
            finalRun.setFinishedAt(LocalDateTime.now());
            testRunRepository.updateById(finalRun);

            // 更新图谱置信度
            graphValidatorService.updateConfidenceByTestResults(projectId, versionId, allResults);

            log.info("Test run completed: {}, passed={}, failed={}", runId, passed, failed);

        } catch (InterruptedException e) {
            log.error("Test run interrupted: {}", runId, e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Test run failed: {}", runId, e);
            TestRun testRun = testRunRepository.selectById(runId);
            if (testRun != null) {
                testRun.setStatus("FAILED");
                testRun.setFinishedAt(LocalDateTime.now());
                testRunRepository.updateById(testRun);
            }
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

        TestRun originalRun = testRunRepository.selectById(runId);
        if (originalRun == null) {
            throw new IllegalArgumentException("Test run not found: " + runId);
        }

        // 获取失败的用例ID
        List<String> failedCaseIds = testResultRepository.findFailedCaseIds(runId);

        // 创建新的测试运行
        TestRun newRun = new TestRun();
        newRun.setId(UUID.randomUUID().toString());
        newRun.setProjectId(projectId);
        newRun.setVersionId(originalRun.getVersionId());
        newRun.setEnvironment(originalRun.getEnvironment());
        newRun.setStatus("SCHEDULED");
        newRun.setTotalCases(failedCaseIds.size());
        newRun.setPassedCases(0);
        newRun.setFailedCases(0);
        newRun.setStartedAt(LocalDateTime.now());
        testRunRepository.save(newRun);

        if (!failedCaseIds.isEmpty()) {
            executorService.submit(() -> executeTestRun(newRun.getId(), projectId, failedCaseIds, newRun.getEnvironment()));
        }

        return newRun.getId();
    }

    /**
     * 取消测试运行
     */
    @Transactional
    public void cancelTestRun(String runId) {
        TestRun testRun = testRunRepository.selectById(runId);
        if (testRun != null) {
            testRun.setStatus("CANCELLED");
            testRun.setFinishedAt(LocalDateTime.now());
            testRunRepository.updateById(testRun);
            log.info("Test run cancelled: {}", runId);
        }
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
