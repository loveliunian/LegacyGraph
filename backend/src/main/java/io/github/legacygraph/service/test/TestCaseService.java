package io.github.legacygraph.service.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.builder.FeatureSliceBuilder;
import io.github.legacygraph.builder.ScenarioDSLBuilder;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.GenerateTestCasesRequest;
import io.github.legacygraph.dto.StartTestRunRequest;
import io.github.legacygraph.dto.graph.FeatureSlice;
import io.github.legacygraph.dto.graph.ScenarioDSL;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.repository.TestResultRepository;
import io.github.legacygraph.test.ApiTestExecutor;
import io.github.legacygraph.test.DbAssertionExecutor;
import io.github.legacygraph.test.E2eTestExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.legacygraph.util.IdUtil;

/**
 * 测试用例生成服务
 * 根据图谱自动生成API测试用例和数据库断言
 * 集成 Playwright 自动执行 E2E 测试
 */
@Slf4j
@Service
public class TestCaseService {

    private final Neo4jGraphDao neo4jGraphDao;
    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final ObjectMapper objectMapper;
    private final ApiTestExecutor apiTestExecutor;
    private final DbAssertionExecutor dbAssertionExecutor;
    private final E2eTestExecutor e2eTestExecutor;
    private final TestResultUpdateService testResultUpdateService;
    private final FeatureSliceBuilder featureSliceBuilder;
    private final ScenarioDSLBuilder scenarioDSLBuilder;

    private final ExecutorService testExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public TestCaseService(Neo4jGraphDao neo4jGraphDao,
                          TestCaseRepository testCaseRepository,
                          TestResultRepository testResultRepository,
                          ObjectMapper objectMapper,
                          ApiTestExecutor apiTestExecutor,
                          DbAssertionExecutor dbAssertionExecutor,
                          E2eTestExecutor e2eTestExecutor,
                          TestResultUpdateService testResultUpdateService,
                          FeatureSliceBuilder featureSliceBuilder,
                          ScenarioDSLBuilder scenarioDSLBuilder) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.testCaseRepository = testCaseRepository;
        this.testResultRepository = testResultRepository;
        this.objectMapper = objectMapper;
        this.apiTestExecutor = apiTestExecutor;
        this.dbAssertionExecutor = dbAssertionExecutor;
        this.e2eTestExecutor = e2eTestExecutor;
        this.testResultUpdateService = testResultUpdateService;
        this.featureSliceBuilder = featureSliceBuilder;
        this.scenarioDSLBuilder = scenarioDSLBuilder;
    }

    // ==================== Phase 2.2: Feature Slice → Test Case 贯通 ====================

    /**
     * 从 Feature Slice 生成测试用例（Phase 2.2）。
     * <p>使用 ScenarioDSLBuilder 从 Slice 构建场景 DSL，
     * 再映射为 TestCase 并持久化。替代逐节点生成模式。</p>
     *
     * @param sliceId Feature Slice ID
     * @return 生成的测试用例数量
     */
    @Transactional
    public int generateTestCasesFromSlice(String projectId, String sliceId) {
        FeatureSlice slice = featureSliceBuilder.buildSliceById(projectId, sliceId);
        if (slice == null || slice.getSliceId() == null) {
            log.warn("TestCaseService: slice not found: projectId={}, sliceId={}", projectId, sliceId);
            return 0;
        }

        List<ScenarioDSL> scenarios = scenarioDSLBuilder.buildFromSlice(slice);
        if (scenarios.isEmpty()) {
            log.info("TestCaseService: no scenarios generated from slice {}", sliceId);
            return 0;
        }

        int count = 0;
        List<TestCase> batch = new ArrayList<>(scenarios.size());
        for (ScenarioDSL dsl : scenarios) {
            TestCase tc = scenarioToTestCase(slice.getProjectId(), slice.getVersionId(), dsl, slice);
            if (tc != null) {
                batch.add(tc);
                count++;
            }
        }
        for (TestCase tc : batch) {
            testCaseRepository.insert(tc);
        }

        // 回写 slice：关联 testCaseIds
        if (slice.getTestCaseIds() == null) slice.setTestCaseIds(new ArrayList<>());
        slice.getTestCaseIds().add(sliceId);
        log.info("TestCaseService: generated {} test cases from slice {} (scenarioCount={})",
                count, sliceId, scenarios.size());
        return count;
    }

    private TestCase scenarioToTestCase(String projectId, String versionId,
                                         ScenarioDSL dsl, FeatureSlice slice) {
        TestCase tc = new TestCase();
        tc.setId(IdUtil.fastUUID());
        tc.setProjectId(projectId);
        tc.setVersionId(versionId);
        tc.setCaseCode("SLICE_" + Math.abs(dsl.getScenarioId().hashCode()));
        tc.setCaseName(dsl.getName() != null ? dsl.getName() : "Slice-" + dsl.getScenarioId());
        tc.setCaseType(dsl.getScenarioType() != null ? dsl.getScenarioType() : "API");
        tc.setPriority("P2");
        tc.setGeneratedBy("SLICE_DSL");
        tc.setConfidence(slice.getConfidence() != null ? slice.getConfidence() : BigDecimal.valueOf(0.7));
        tc.setStatus("GENERATED");
        tc.setCreatedAt(LocalDateTime.now());
        tc.setUpdatedAt(LocalDateTime.now());

        // DSL 的 assertions 序列化为 expectedResult
        try {
            Map<String, Object> expected = new HashMap<>();
            List<Map<String, String>> assertionList = new ArrayList<>();
            for (ScenarioDSL.Assertion a : dsl.getAssertions()) {
                Map<String, String> am = new HashMap<>();
                am.put("type", a.getType());
                am.put("field", a.getField());
                am.put("operator", a.getOperator());
                am.put("expected", a.getExpectedValue());
                assertionList.add(am);
            }
            expected.put("assertions", assertionList);
            expected.put("scenarioType", dsl.getScenarioType());
            expected.put("sliceId", slice.getSliceId());
            tc.setExpectedResult(objectMapper.writeValueAsString(expected));
        } catch (JsonProcessingException e) {
            log.warn("TestCaseService: failed to serialize assertions: {}", e.getMessage());
        }

        // actions 序列化为 steps
        try {
            tc.setSteps(objectMapper.writeValueAsString(dsl.getActions()));
        } catch (JsonProcessingException e) {
            log.warn("TestCaseService: failed to serialize steps: {}", e.getMessage());
        }

        // preconditions
        try {
            tc.setPreconditions(objectMapper.writeValueAsString(dsl.getPreconditions()));
        } catch (JsonProcessingException e) {
            log.warn("TestCaseService: failed to serialize preconditions: {}", e.getMessage());
        }

        return tc;
    }
    @Transactional
    public String generateTestCases(String projectId, GenerateTestCasesRequest request) {
        String executionId = IdUtil.fastUUID();
        String versionId = request.getVersionId();

        log.info("Starting generate test cases for projectId: {}, versionId: {}", projectId, versionId);

        GenerateTestCasesRequest.Scope scope = request.getScope();
        List<String> nodeTypes = scope != null ? scope.getNodeTypes() : Arrays.asList("ApiEndpoint");

        // Query nodes from Neo4j — loop over nodeTypes since queryNodes supports single type
        List<GraphNode> nodes = new ArrayList<>();
        for (String nt : nodeTypes) {
            nodes.addAll(neo4jGraphDao.queryNodes(
                    projectId, versionId, nt, null, null, "CONFIRMED", Integer.MAX_VALUE));
        }

        int generatedCount = 0;
        for (GraphNode node : nodes) {
            if ("ApiEndpoint".equals(node.getNodeType())) {
                generateApiTestCase(node);
                generatedCount++;
            } else if ("Table".equals(node.getNodeType())) {
                generateDbAssertionTestCase(node);
                generatedCount++;
            } else if ("Page".equals(node.getNodeType()) || "Component".equals(node.getNodeType())) {
                generateE2ETestCase(node);
                generatedCount++;
            }
        }

        log.info("Generated {} test cases", generatedCount);
        return executionId;
    }

    /**
     * 为API接口生成测试用例
     * 生成正常场景 + 常见异常场景（参数错误、未授权、空输入）
     */
    private void generateApiTestCase(GraphNode apiNode) {
        String[] parts = apiNode.getNodeKey().split(" ", 2);
        String method = parts.length > 1 ? parts[0] : "GET";
        String path = parts.length > 1 ? parts[1] : apiNode.getNodeKey();

        // 1. 正常成功场景
        TestCase normalTestCase = createBaseApiTestCase(apiNode, method, path, "NORMAL", "成功路径", BigDecimal.valueOf(0.8));
        Map<String, Object> preconditions = new HashMap<>();
        preconditions.put("type", "LOGIN");
        preconditions.put("role", "user");

        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("action", "CALL_API");
        step.put("method", method);
        step.put("path", path);
        // 添加示例请求体（合理默认值根据HTTP方法推断）
        if (!"GET".equals(method) && !"DELETE".equals(method)) {
            step.put("body", "{\n  // 根据业务填写实际参数\n}");
        }
        steps.add(step);

        Map<String, Object> expected = new HashMap<>();
        expected.put("httpStatus", 200);
        expected.put("businessSuccess", true);
        expected.put("responseContains", "code|status|success");

        try {
            normalTestCase.setPreconditions(objectMapper.writeValueAsString(preconditions));
            normalTestCase.setSteps(objectMapper.writeValueAsString(steps));
            normalTestCase.setExpectedResult(objectMapper.writeValueAsString(expected));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize test case", e);
        }
        testCaseRepository.insert(normalTestCase);

        // 2. 未授权场景（未登录访问）
        TestCase unauthorizedTestCase = createBaseApiTestCase(apiNode, method, path, "UNAUTHORIZED", "未授权访问", BigDecimal.valueOf(0.95));
        Map<String, Object> preconditionsUnauth = new HashMap<>();
        preconditionsUnauth.put("type", "NO_LOGIN");

        List<Map<String, Object>> stepsUnauth = new ArrayList<>();
        Map<String, Object> stepUnauth = new HashMap<>();
        stepUnauth.put("action", "CALL_API");
        stepUnauth.put("method", method);
        stepUnauth.put("path", path);
        stepsUnauth.add(stepUnauth);

        Map<String, Object> expectedUnauth = new HashMap<>();
        expectedUnauth.put("httpStatus", 401);
        expectedUnauth.put("businessSuccess", false);

        try {
            unauthorizedTestCase.setPreconditions(objectMapper.writeValueAsString(preconditionsUnauth));
            unauthorizedTestCase.setSteps(objectMapper.writeValueAsString(stepsUnauth));
            unauthorizedTestCase.setExpectedResult(objectMapper.writeValueAsString(expectedUnauth));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize test case", e);
        }
        testCaseRepository.insert(unauthorizedTestCase);

        // 3. 参数校验失败场景（空必填参数）
        if (!"GET".equals(method)) {
            TestCase badParamTestCase = createBaseApiTestCase(apiNode, method, path, "BAD_PARAM", "必填参数为空", BigDecimal.valueOf(0.9));
            Map<String, Object> preconditionsBad = new HashMap<>();
            preconditionsBad.put("type", "LOGIN");
            preconditionsBad.put("role", "user");

            List<Map<String, Object>> stepsBad = new ArrayList<>();
            Map<String, Object> stepBad = new HashMap<>();
            stepBad.put("action", "CALL_API");
            stepBad.put("method", method);
            stepBad.put("path", path);
            stepBad.put("body", "{\n  // 缺少必填参数\n}");
            stepsBad.add(stepBad);

            Map<String, Object> expectedBad = new HashMap<>();
            expectedBad.put("httpStatus", 400);
            expectedBad.put("businessSuccess", false);
            expectedBad.put("errorContains", "parameter|required|invalid");

            try {
                badParamTestCase.setPreconditions(objectMapper.writeValueAsString(preconditionsBad));
                badParamTestCase.setSteps(objectMapper.writeValueAsString(stepsBad));
                badParamTestCase.setExpectedResult(objectMapper.writeValueAsString(expectedBad));
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize test case", e);
            }
            testCaseRepository.insert(badParamTestCase);
        }
    }

    /**
     * 创建基础 API 测试用例骨架
     */
    private TestCase createBaseApiTestCase(GraphNode apiNode, String method, String path, String scenario, String scenarioDesc, BigDecimal confidence) {
        TestCase testCase = new TestCase();
        testCase.setId(IdUtil.fastUUID());
        testCase.setProjectId(apiNode.getProjectId());
        testCase.setVersionId(apiNode.getVersionId());
        testCase.setCaseCode("API_" + Math.abs(apiNode.getNodeKey().hashCode()) + "_" + scenario.toLowerCase());
        testCase.setCaseName(apiNode.getDisplayName() != null ? apiNode.getDisplayName() + " " + scenarioDesc : "API测试-" + scenarioDesc);
        testCase.setCaseType("API");
        testCase.setTargetNodeId(apiNode.getId());
        testCase.setScenario(scenario);
        testCase.setPriority(scenario.equals("NORMAL") ? "P2" : "P3");
        testCase.setGeneratedBy("AUTO");
        testCase.setConfidence(confidence);
        testCase.setStatus("GENERATED");
        testCase.setCreatedAt(LocalDateTime.now());
        testCase.setUpdatedAt(LocalDateTime.now());
        return testCase;
    }

    /**
     * 为数据库表生成断言测试用例
     * 生成多条断言：
     * 1. 表非空检查（总行数 > 0）
     * 2. 如果能找到上游 API 写入，可以生成业务数据存在断言
     */
    private void generateDbAssertionTestCase(GraphNode tableNode) {
        // 1. 基础非空检查
        TestCase baseTestCase = createBaseDbTestCase(tableNode, "ROW_COUNT", "总行数非空", BigDecimal.valueOf(0.85));
        List<Map<String, Object>> assertions = new ArrayList<>();

        Map<String, Object> assertion = new HashMap<>();
        assertion.put("type", "ROW_COUNT");
        assertion.put("table", tableNode.getNodeName());
        assertion.put("operator", "GREATER_THAN");
        assertion.put("expected", 0);
        assertions.add(assertion);

        try {
            baseTestCase.setSteps(objectMapper.writeValueAsString(assertions));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize DB test case", e);
        }
        testCaseRepository.insert(baseTestCase);

        // 2. 如果表有描述信息，尝试添加业务数据完整性检查
        String description = tableNode.getDescription();
        if (description != null && !description.isEmpty()) {
            // 添加检查主键列非空约束
            TestCase constraintTestCase = createBaseDbTestCase(tableNode, "PRIMARY_KEY_NOT_NULL", "主键不为空检查", BigDecimal.valueOf(0.8));
            List<Map<String, Object>> constraintAssertions = new ArrayList<>();
            Map<String, Object> pkAssertion = new HashMap<>();
            pkAssertion.put("type", "COLUMN_NOT_NULL");
            pkAssertion.put("table", tableNode.getNodeName());
            pkAssertion.put("column", "id");
            pkAssertion.put("expected", "NOT NULL");
            constraintAssertions.add(pkAssertion);

            try {
                constraintTestCase.setSteps(objectMapper.writeValueAsString(constraintAssertions));
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize DB test case", e);
            }
            testCaseRepository.insert(constraintTestCase);
        }
    }

    /**
     * 创建基础 DB 测试用例骨架
     */
    private TestCase createBaseDbTestCase(GraphNode tableNode, String assertionType, String description, BigDecimal confidence) {
        TestCase testCase = new TestCase();
        testCase.setId(IdUtil.fastUUID());
        testCase.setProjectId(tableNode.getProjectId());
        testCase.setVersionId(tableNode.getVersionId());
        testCase.setCaseCode("DB_" + Math.abs(tableNode.getNodeKey().hashCode()) + "_" + assertionType.toLowerCase());
        testCase.setCaseName("表 " + tableNode.getNodeName() + " " + description);
        testCase.setCaseType("DB");
        testCase.setTargetNodeId(tableNode.getId());
        testCase.setPriority("P3");
        testCase.setGeneratedBy("AUTO");
        testCase.setConfidence(confidence);
        testCase.setStatus("GENERATED");
        testCase.setCreatedAt(LocalDateTime.now());
        testCase.setUpdatedAt(LocalDateTime.now());
        return testCase;
    }

    /**
     * 为前端页面/组件生成 Playwright E2E 测试用例
     */
    private void generateE2ETestCase(GraphNode pageNode) {
        TestCase testCase = new TestCase();
        testCase.setId(IdUtil.fastUUID());
        testCase.setProjectId(pageNode.getProjectId());
        testCase.setVersionId(pageNode.getVersionId());
        testCase.setCaseCode("E2E_" + Math.abs(pageNode.getNodeKey().hashCode()));
        testCase.setCaseName("页面 " + pageNode.getNodeName() + " 渲染测试");
        testCase.setCaseType("E2E");
        testCase.setTargetNodeId(pageNode.getId());
        testCase.setPriority("P2");
        testCase.setGeneratedBy("AUTO");
        testCase.setConfidence(BigDecimal.valueOf(0.75));
        testCase.setStatus("GENERATED");
        testCase.setCreatedAt(LocalDateTime.now());
        testCase.setUpdatedAt(LocalDateTime.now());

        Map<String, Object> preconditions = new HashMap<>();
        preconditions.put("baseUrl", "${TEST_BASE_URL}");

        List<Map<String, Object>> steps = new ArrayList<>();
        
        Map<String, Object> navigateStep = new HashMap<>();
        navigateStep.put("action", "NAVIGATE");
        navigateStep.put("url", "/" + pageNode.getNodeKey());
        steps.add(navigateStep);

        Map<String, Object> assertStep = new HashMap<>();
        assertStep.put("action", "ASSERT_VISIBLE");
        assertStep.put("selector", "body");
        steps.add(assertStep);

        try {
            testCase.setPreconditions(objectMapper.writeValueAsString(preconditions));
            testCase.setSteps(objectMapper.writeValueAsString(steps));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize E2E test case", e);
        }

        testCaseRepository.insert(testCase);
    }

    /**
     * 执行测试用例（异步）
     * 根据测试用例类型分派到不同执行器
     */
    @Transactional
    public String startTestRun(StartTestRunRequest request) {
        String executionId = IdUtil.fastUUID();
        String versionId = request.getVersionId();
        List<String> caseIds = request.getCaseIds();
        String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl() : "http://localhost:5173";

        log.info("Starting test run executionId={}, versionId={}, caseCount={}",
                executionId, versionId, caseIds.size());

        List<CompletableFuture<TestResult>> futures = new ArrayList<>();

        // 批量加载 TestCase，避免 N+1
        Map<String, TestCase> tcMap = new HashMap<>();
        if (!caseIds.isEmpty()) {
            List<TestCase> tcs = testCaseRepository.selectBatchIds(caseIds);
            for (TestCase tc : tcs) {
                tcMap.put(tc.getId(), tc);
            }
        }

        for (String caseId : caseIds) {
            TestCase testCase = tcMap.get(caseId);
            if (testCase == null) continue;

            TestResult result = new TestResult();
            result.setId(IdUtil.fastUUID());
            result.setProjectId(testCase.getProjectId());
            result.setVersionId(versionId);
            result.setTestCaseId(caseId);
            result.setExecutionId(executionId);
            result.setResultStatus("RUNNING");
            result.setExecutedAt(LocalDateTime.now());
            testResultRepository.insert(result);

            CompletableFuture<TestResult> future = CompletableFuture.supplyAsync(
                    () -> executeTestCase(testCase, executionId, baseUrl),
                    testExecutor
            ).thenApply(executedResult -> {
                testResultRepository.updateById(executedResult);
                return executedResult;
            });
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    testResultUpdateService.updateConfidenceByTestResults(executionId);
                    log.info("Test run completed: {}", executionId);
                });

        log.info("Test run started: {}", executionId);
        return executionId;
    }

    /**
     * 根据测试类型执行单个测试用例
     */
    private TestResult executeTestCase(TestCase testCase, String executionId, String baseUrl) {
        String caseType = testCase.getCaseType();
        long startTime = System.currentTimeMillis();

        try {
            switch (caseType) {
                case "API":
                    return apiTestExecutor.execute(testCase, executionId, baseUrl);
                case "DB":
                    return dbAssertionExecutor.execute(testCase, executionId);
                case "E2E":
                    return e2eTestExecutor.execute(testCase, executionId, baseUrl);
                default:
                    TestResult result = new TestResult();
                    result.setId(IdUtil.fastUUID());
                    result.setProjectId(testCase.getProjectId());
                    result.setVersionId(testCase.getVersionId());
                    result.setTestCaseId(testCase.getId());
                    result.setExecutionId(executionId);
                    result.setResultStatus("SKIPPED");
                    result.setErrorMessage("Unknown test type: " + caseType);
                    result.setDurationMs(System.currentTimeMillis() - startTime);
                    result.setExecutedAt(LocalDateTime.now());
                    return result;
            }
        } catch (Exception e) {
            log.error("Test execution failed: {}", testCase.getCaseCode(), e);
            TestResult result = new TestResult();
            result.setId(IdUtil.fastUUID());
            result.setProjectId(testCase.getProjectId());
            result.setVersionId(testCase.getVersionId());
            result.setTestCaseId(testCase.getId());
            result.setExecutionId(executionId);
            result.setResultStatus("ERROR");
            result.setErrorMessage(e.getMessage());
            result.setDurationMs(System.currentTimeMillis() - startTime);
            result.setExecutedAt(LocalDateTime.now());
            return result;
        }
    }
}
