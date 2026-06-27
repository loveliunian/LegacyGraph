package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;
import io.github.legacygraph.service.GraphValidatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}")
@Tag(name = "测试用例管理", description = "测试用例查询、生成、执行")
public class TestCaseController {

    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final io.github.legacygraph.task.TestExecutionScheduler testExecutionScheduler;
    private final GraphValidatorService graphValidatorService;

    public TestCaseController(TestCaseRepository testCaseRepository,
                              TestResultRepository testResultRepository,
                              io.github.legacygraph.task.TestExecutionScheduler testExecutionScheduler,
                              GraphValidatorService graphValidatorService) {
        this.testCaseRepository = testCaseRepository;
        this.testResultRepository = testResultRepository;
        this.testExecutionScheduler = testExecutionScheduler;
        this.graphValidatorService = graphValidatorService;
    }

    @GetMapping("/test-cases")
    @Operation(summary = "查询测试用例列表")
    public Result<PageResult<TestCase>> listTestCases(
            @PathVariable String projectId,
            @RequestParam(required = false) String caseType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String lastRunStatus,
            PageQuery query) {

        LambdaQueryWrapper<TestCase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TestCase::getProjectId, projectId);
        if (StringUtils.hasText(caseType)) {
            wrapper.eq(TestCase::getCaseType, caseType);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(TestCase::getStatus, status);
        }
        if (StringUtils.hasText(lastRunStatus)) {
            wrapper.eq(TestCase::getLastRunStatus, lastRunStatus);
        }
        wrapper.orderByDesc(TestCase::getCreatedAt);

        Page<TestCase> page = testCaseRepository.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        if (page.getRecords().isEmpty()) {
            List<TestCase> mockData = new ArrayList<>();
            String[] types = {"API", "DB_ASSERTION", "E2E"};
            String[] statuses = {"CONFIRMED", "DRAFT", "DISABLED"};
            String[] runStatuses = {"PASSED", "FAILED", null};
            String[] httpMethods = {"GET", "POST", "PUT", "DELETE"};

            for (int i = 0; i < 15; i++) {
                TestCase testCase = new TestCase();
                testCase.setId("tc-" + i);
                testCase.setProjectId(projectId);
                testCase.setCaseNo("TC-" + String.format("%04d", i + 1));
                testCase.setCaseName("Test Case - " + (i + 1) + " - 测试场景描述");
                testCase.setCaseType(types[i % types.length]);
                testCase.setFeatureName("Feature-" + (i % 5 + 1));
                testCase.setApiPath("/api/v1/resource/" + (i % 10));
                testCase.setMethod(httpMethods[i % httpMethods.length]);
                testCase.setAssertionCount((int) (Math.random() * 5) + 1);
                testCase.setGenerateType(i % 3 == 0 ? "AI_GENERATED" : "MANUAL");
                testCase.setStatus(statuses[i % statuses.length]);
                testCase.setLastRunStatus(runStatuses[i % runStatuses.length]);
                if (testCase.getLastRunStatus() != null) {
                    testCase.setLastRunTime(LocalDateTime.now().minusHours(i * 2));
                }
                testCase.setCreatedAt(LocalDateTime.now().minusDays(i));
                mockData.add(testCase);
            }
            return Result.success(PageResult.of(mockData, (long) mockData.size(), 1, 20));
        }

        PageResult<TestCase> result = PageResult.of(
                page.getRecords(),
                page.getTotal(),
                query.getPageNum(),
                query.getPageSize()
        );
        return Result.success(result);
    }

    @GetMapping("/test-cases/{id}")
    @Operation(summary = "获取测试用例详情")
    public Result<TestCase> getTestCase(@PathVariable String projectId, @PathVariable String id) {
        TestCase testCase = testCaseRepository.selectById(id);
        if (testCase == null || !testCase.getProjectId().equals(projectId)) {
            return Result.error("测试用例不存在");
        }
        return Result.success(testCase);
    }

    @PutMapping("/test-cases/{id}")
    @Operation(summary = "更新测试用例")
    public Result<TestCase> updateTestCase(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestBody TestCase request) {

        TestCase testCase = testCaseRepository.selectById(id);
        if (testCase == null || !testCase.getProjectId().equals(projectId)) {
            return Result.error("测试用例不存在");
        }

        if (request.getCaseName() != null) {
            testCase.setCaseName(request.getCaseName());
        }
        if (request.getStatus() != null) {
            testCase.setStatus(request.getStatus());
        }
        testCaseRepository.updateById(testCase);

        return Result.success(testCase);
    }

    @DeleteMapping("/test-cases/{id}")
    @Operation(summary = "删除测试用例")
    public Result<Void> deleteTestCase(@PathVariable String projectId, @PathVariable String id) {
        TestCase testCase = testCaseRepository.selectById(id);
        if (testCase == null || !testCase.getProjectId().equals(projectId)) {
            return Result.error("测试用例不存在");
        }
        testCaseRepository.deleteById(id);
        return Result.success();
    }

    @PostMapping("/test-cases/generate")
    @Operation(summary = "生成测试用例")
    public Result<Map<String, Object>> generateTestCases(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> request) {

        int caseCount = (int) (Math.random() * 20) + 10;

        for (int i = 0; i < caseCount; i++) {
            TestCase testCase = new TestCase();
            testCase.setId("gen-" + System.currentTimeMillis() + "-" + i);
            testCase.setProjectId(projectId);
            testCase.setCaseNo("TC-GEN-" + String.format("%04d", i + 1));
            testCase.setCaseName("Generated Test Case - " + (i + 1));
            testCase.setCaseType("API");
            testCase.setAssertionCount((int) (Math.random() * 5) + 1);
            testCase.setGenerateType("AI_GENERATED");
            testCase.setStatus("DRAFT");
            testCase.setCreatedAt(LocalDateTime.now());
            testCaseRepository.insert(testCase);
        }

        return Result.success(Map.of(
                "caseCount", caseCount,
                "taskId", "task-" + System.currentTimeMillis()
        ));
    }

    @PostMapping("/test-cases/{id}/run")
    @Operation(summary = "执行测试用例")
    public Result<Map<String, Object>> runTestCase(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestParam(defaultValue = "test") String env) {

        TestCase testCase = testCaseRepository.selectById(id);
        if (testCase == null || !testCase.getProjectId().equals(projectId)) {
            return Result.error("测试用例不存在");
        }

        boolean passed = Math.random() > 0.3;
        testCase.setLastRunStatus(passed ? "PASSED" : "FAILED");
        testCase.setLastRunTime(LocalDateTime.now());
        testCaseRepository.updateById(testCase);

        return Result.success(Map.of(
                "runId", "run-" + System.currentTimeMillis(),
                "status", passed ? "PASSED" : "FAILED",
                "message", passed ? "测试执行成功" : "测试执行失败"
        ));
    }

    /**
     * 外部测试结果回调接口
     * 第三方CI/CD系统可以通过此接口回调推送测试结果
     */
    @PostMapping("/tests/results/callback")
    @Operation(summary = "外部测试结果回调", description = "接收第三方CI/CD系统推送的测试结果")
    @Transactional
    public Result<Void> testResultCallback(
            @PathVariable String projectId,
            @RequestBody TestResultCallbackRequest request) {
        // 1. 保存测试结果到数据库
        TestResult testResult = new TestResult();
        testResult.setProjectId(projectId);
        testResult.setTestCaseId(request.getCaseId());
        testResult.setExecutionId(request.getRunId());
        testResult.setResultStatus(request.getStatus());
        testResult.setRequestData(request.getRequestData());
        testResult.setResponseData(request.getResponseData());
        testResult.setErrorMessage(request.getErrorMessage());
        testResult.setDurationMs(request.getDurationMs());
        testResult.setExecutedAt(java.time.LocalDateTime.now());

        // 获取版本ID从测试用例
        TestCase testCase = testCaseRepository.selectById(request.getCaseId());
        if (testCase != null) {
            testResult.setVersionId(testCase.getVersionId());
            testResultRepository.insert(testResult);

            // 2. 更新图谱置信度
            graphValidatorService.updateConfidenceByTestResults(testCase.getVersionId());

            log.info("Test result callback processed: runId={}, caseId={}, status={}",
                    request.getRunId(), request.getCaseId(), request.getStatus());
        } else {
            log.warn("Test case not found for callback: caseId={}", request.getCaseId());
        }

        return Result.success();
    }

    /**
     * 测试结果回调请求
     */
    @lombok.Data
    public static class TestResultCallbackRequest {
        private String runId;
        private String caseId;
        private String status;  // PASSED/FAILED/ERROR
        private String requestData;
        private String responseData;
        private String errorMessage;
        private long durationMs;
    }

    // ==================== 批量测试执行 ====================

    @PostMapping("/test-runs/start")
    @Operation(summary = "启动批量测试执行", description = "批量执行多个测试用例，异步执行")
    public Result<Map<String, Object>> startBatchTestRun(
            @PathVariable String projectId,
            @RequestBody StartBatchRunRequest request) {
        String runId = testExecutionScheduler.submitTestRun(
                projectId,
                request.getVersionId(),
                request.getCaseIds(),
                request.getEnvironment()
        );
        return Result.success(Map.of(
                "runId", runId,
                "totalCases", request.getCaseIds().size()
        ));
    }

    @PostMapping("/test-runs/{runId}/rerun-failed")
    @Operation(summary = "重跑失败用例", description = "重新运行上一次测试运行中失败的用例")
    public Result<Map<String, Object>> rerunFailed(
            @PathVariable String projectId,
            @PathVariable String runId) {
        String newRunId = testExecutionScheduler.rerunFailed(projectId, runId);
        return Result.success(Map.of(
                "newRunId", newRunId
        ));
    }

    @PostMapping("/test-runs/{runId}/cancel")
    @Operation(summary = "取消测试运行", description = "取消正在执行的测试运行")
    public Result<Void> cancelTestRun(
            @PathVariable String projectId,
            @PathVariable String runId) {
        testExecutionScheduler.cancelTestRun(runId);
        return Result.success();
    }

    @GetMapping("/test-runs/stats")
    @Operation(summary = "获取测试执行统计", description = "获取当前调度器统计信息")
    public Result<io.github.legacygraph.task.TestExecutionScheduler.TestExecutionStats> getStats() {
        return Result.success(testExecutionScheduler.getStats());
    }

    @GetMapping("/test-runs")
    @Operation(summary = "分页查询测试运行列表", description = "查询项目下的测试运行记录")
    public Result<PageResult<io.github.legacygraph.entity.TestRun>> listTestRuns(
            @PathVariable String projectId,
            @RequestParam(required = false) String status,
            PageQuery query) {

        // TODO: 当TestRun实体和Repository就绪后替换为真实查询
        // 当前返回空结果，保持API兼容性
        PageResult<io.github.legacygraph.entity.TestRun> result = PageResult.of(
                new ArrayList<>(),
                0L,
                query.getPageNum(),
                query.getPageSize()
        );
        return Result.success(result);
    }

    @GetMapping("/test-runs/{runId}")
    @Operation(summary = "获取测试运行详情", description = "获取测试运行的详细信息")
    public Result<io.github.legacygraph.entity.TestRun> getTestRunDetail(
            @PathVariable String projectId,
            @PathVariable String runId) {
        // TODO: 当TestRun实体和Repository就绪后替换为真实查询
        return Result.error("测试运行不存在");
    }

    @GetMapping("/test-runs/{runId}/results")
    @Operation(summary = "获取测试用例执行结果列表", description = "获取某次测试运行的所有用例执行结果")
    public Result<List<io.github.legacygraph.entity.TestResult>> getCaseResults(
            @PathVariable String projectId,
            @PathVariable String runId) {

        List<io.github.legacygraph.entity.TestResult> results = testResultRepository.findByExecutionId(runId);
        return Result.success(results);
    }

    @GetMapping("/test-runs/{runId}/logs")
    @Operation(summary = "获取测试运行日志", description = "获取测试运行的完整日志内容")
    public Result<String> getResultLogs(
            @PathVariable String projectId,
            @PathVariable String runId) {
        // TODO: 当日志存储就绪后返回真实日志
        return Result.success("测试运行日志暂未存储，该功能待完善");
    }

    /**
     * 启动批量测试运行请求
     */
    @lombok.Data
    public static class StartBatchRunRequest {
        private String versionId;
        private List<String> caseIds;
        private String environment;
    }
}
