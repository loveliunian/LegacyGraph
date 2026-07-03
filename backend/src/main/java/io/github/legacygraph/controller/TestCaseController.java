package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.agent.TestCaseAgent;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.GenerateTestCasesRequest;
import io.github.legacygraph.dto.GenerateTestCasesRequest.Scope;
import io.github.legacygraph.dto.GeneratedTestCase;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.entity.TestRun;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;
import io.github.legacygraph.repository.TestRunRepository;
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
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}")
@Tag(name = "测试用例管理", description = "测试用例查询、生成、执行")
public class TestCaseController {

    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final TestRunRepository testRunRepository;
    private final io.github.legacygraph.task.TestExecutionScheduler testExecutionScheduler;
    private final GraphValidatorService graphValidatorService;
    private final TestCaseAgent testCaseAgent;
    private final Neo4jGraphDao neo4jGraphDao;
    private final ScanTaskRepository scanTaskRepository;

    public TestCaseController(TestCaseRepository testCaseRepository,
                              TestResultRepository testResultRepository,
                              TestRunRepository testRunRepository,
                              io.github.legacygraph.task.TestExecutionScheduler testExecutionScheduler,
                              GraphValidatorService graphValidatorService,
                              TestCaseAgent testCaseAgent,
                              Neo4jGraphDao neo4jGraphDao,
                              ScanTaskRepository scanTaskRepository) {
        this.testCaseRepository = testCaseRepository;
        this.testResultRepository = testResultRepository;
        this.testRunRepository = testRunRepository;
        this.testExecutionScheduler = testExecutionScheduler;
        this.graphValidatorService = graphValidatorService;
        this.testCaseAgent = testCaseAgent;
        this.neo4jGraphDao = neo4jGraphDao;
        this.scanTaskRepository = scanTaskRepository;
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

    @PostMapping("/test-cases")
    @Operation(summary = "创建测试用例", description = "创建新的测试用例")
    public Result<TestCase> createTestCase(
            @PathVariable String projectId,
            @RequestBody TestCase request) {
        if (!projectId.equals(request.getProjectId())) {
            request.setProjectId(projectId);
        }
        if (request.getId() == null) {
            request.setId(UUID.randomUUID().toString());
        }
        if (request.getStatus() == null) {
            request.setStatus("ENABLED");
        }
        request.setCreatedAt(LocalDateTime.now());
        testCaseRepository.insert(request);
        return Result.success(request);
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
            @RequestBody GenerateTestCasesRequest request) {

        log.info("Test case generation requested for project {}, version {}", projectId, request.getVersionId());

        // 获取所有 API 节点，用 TestCaseAgent 生成测试用例
        // Query nodes from Neo4j — loop over nodeTypes since queryNodes supports single type
        List<GraphNode> apiNodes = new ArrayList<>();
        String[] types = {"ApiEndpoint", "Feature", "Controller"};
        for (String t : types) {
            apiNodes.addAll(neo4jGraphDao.queryNodes(
                    projectId, request.getVersionId(), t, null, null, null, Integer.MAX_VALUE));
        }

        if (apiNodes.isEmpty()) {
            log.info("No API/Feature nodes found for test generation");
            return Result.success(Map.of(
                    "projectId", projectId,
                    "versionId", request.getVersionId(),
                    "status", "completed",
                    "generated", 0,
                    "message", "未找到可生成测试用例的节点"
            ));
        }

        Scope scope = request.getScope();
        List<String> targetTypes = (scope != null && scope.getNodeTypes() != null)
                ? scope.getNodeTypes() : List.of("ApiEndpoint");

        int totalGenerated = 0;
        for (GraphNode node : apiNodes) {
            if (!"ApiEndpoint".equals(node.getNodeType())) continue;
            if (!targetTypes.contains(node.getNodeType())) continue;

            TestCaseAgent.TestGenerationRequest genReq = new TestCaseAgent.TestGenerationRequest();
            genReq.setProjectId(projectId);
            genReq.setFeatureKey(node.getNodeKey());
            genReq.setFeatureName(node.getNodeName());
            genReq.setApiEndpoint(extractApiEndpoint(node));
            genReq.setHttpMethod(extractHttpMethod(node));

            List<GeneratedTestCase> generated = testCaseAgent.generateTestCases(genReq);

            for (GeneratedTestCase genCase : generated) {
                TestCase tc = new TestCase();
                tc.setProjectId(projectId);
                tc.setVersionId(request.getVersionId());
                tc.setCaseCode("TC-" + System.currentTimeMillis() + "-" + totalGenerated);
                tc.setCaseName(genCase.getCaseName() != null ? genCase.getCaseName() : node.getNodeName() + " 测试");
                tc.setCaseType(genCase.getCaseType() != null ? genCase.getCaseType().name() : "API");
                tc.setTargetNodeId(node.getId());
                tc.setSteps(genCase.getSteps() != null ? String.join("\\n", genCase.getSteps()) : "");
                tc.setExpectedResult("验证接口返回符合预期");
                tc.setStatus("ENABLED");
                tc.setGeneratedBy("LLM");
                tc.setCreatedAt(LocalDateTime.now());
                testCaseRepository.insert(tc);
                totalGenerated++;
            }
        }

        log.info("Test case generation completed for project {}, version {}: generated {} cases",
                projectId, request.getVersionId(), totalGenerated);

        return Result.success(Map.of(
                "projectId", projectId,
                "versionId", request.getVersionId(),
                "status", "completed",
                "generated", totalGenerated
        ));
    }

    static String extractHttpMethod(GraphNode node) {
        if (node == null || node.getNodeKey() == null) {
            return "GET";
        }
        String nodeKey = node.getNodeKey().trim();
        int spaceIndex = nodeKey.indexOf(' ');
        if (spaceIndex > 0) {
            String method = nodeKey.substring(0, spaceIndex).trim();
            if (method.matches("(?i)GET|POST|PUT|DELETE|PATCH")) {
                return method.toUpperCase();
            }
        }
        return "GET";
    }

    static String extractApiEndpoint(GraphNode node) {
        if (node == null || node.getNodeKey() == null) {
            return "";
        }
        String nodeKey = node.getNodeKey().trim();
        int spaceIndex = nodeKey.indexOf(' ');
        if (spaceIndex > 0 && nodeKey.substring(0, spaceIndex).matches("(?i)GET|POST|PUT|DELETE|PATCH")) {
            return nodeKey.substring(spaceIndex + 1).trim();
        }
        return nodeKey;
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

        // 提交单个测试用例作为一次测试运行
        String runId = testExecutionScheduler.submitTestRun(
                projectId,
                testCase.getVersionId(),
                List.of(id),
                env
        );

        return Result.success(Map.of(
                "runId", runId,
                "status", "QUEUED",
                "message", "测试用例已提交执行"
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
    public Result<PageResult<TestRun>> listTestRuns(
            @PathVariable String projectId,
            @RequestParam(required = false) String status,
            PageQuery query) {

        LambdaQueryWrapper<TestRun> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TestRun::getProjectId, projectId);
        if (StringUtils.hasText(status)) {
            wrapper.eq(TestRun::getStatus, status);
        }
        wrapper.orderByDesc(TestRun::getStartedAt);

        Page<TestRun> page = testRunRepository.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        PageResult<TestRun> result = PageResult.of(
                page.getRecords(),
                page.getTotal(),
                query.getPageNum(),
                query.getPageSize()
        );
        return Result.success(result);
    }

    @GetMapping("/test-runs/{runId}")
    @Operation(summary = "获取测试运行详情", description = "获取测试运行的详细信息")
    public Result<TestRun> getTestRunDetail(
            @PathVariable String projectId,
            @PathVariable String runId) {
        TestRun testRun = testRunRepository.selectById(runId);
        if (testRun == null || !testRun.getProjectId().equals(projectId)) {
            return Result.error("测试运行不存在");
        }
        return Result.success(testRun);
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
    public Result<List<Map<String, Object>>> getResultLogs(
            @PathVariable String projectId,
            @PathVariable String runId) {
        // 从扫描任务中获取关联的日志记录
        List<ScanTask> tasks = scanTaskRepository.lambdaQuery()
                .eq(ScanTask::getVersionId, runId)
                .list();

        List<Map<String, Object>> logs = new ArrayList<>();
        for (ScanTask task : tasks) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("time", task.getCreatedAt() != null ? task.getCreatedAt().toString() : "-");
            entry.put("type", "FAILED".equals(task.getTaskStatus()) ? "ERROR" : "INFO");
            entry.put("message", task.getTaskName() != null
                    ? "任务 [" + task.getTaskName() + "] 状态: " + task.getTaskStatus()
                    : "扫描子任务状态: " + task.getTaskStatus());
            if (task.getErrorMessage() != null) {
                entry.put("error", task.getErrorMessage());
            }
            logs.add(entry);
        }

        if (logs.isEmpty()) {
            logs.add(Map.of(
                    "time", LocalDateTime.now().toString(),
                    "type", "INFO",
                    "message", "暂无日志记录"
            ));
        }

        return Result.success(logs);
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
