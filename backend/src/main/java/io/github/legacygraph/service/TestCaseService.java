package io.github.legacygraph.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.GenerateTestCasesRequest;
import io.github.legacygraph.dto.StartTestRunRequest;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.entity.TestResult;
import io.github.legacygraph.repository.GraphNodeRepository;
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

/**
 * 测试用例生成服务
 * 根据图谱自动生成API测试用例和数据库断言
 * 集成 Playwright 自动执行 E2E 测试
 */
@Slf4j
@Service
public class TestCaseService {

    private final GraphNodeRepository graphNodeRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final ObjectMapper objectMapper;
    private final ApiTestExecutor apiTestExecutor;
    private final DbAssertionExecutor dbAssertionExecutor;
    private final E2eTestExecutor e2eTestExecutor;
    private final TestResultUpdateService testResultUpdateService;

    private final ExecutorService testExecutor = Executors.newFixedThreadPool(4);

    public TestCaseService(GraphNodeRepository graphNodeRepository,
                          TestCaseRepository testCaseRepository,
                          TestResultRepository testResultRepository,
                          ObjectMapper objectMapper,
                          ApiTestExecutor apiTestExecutor,
                          DbAssertionExecutor dbAssertionExecutor,
                          E2eTestExecutor e2eTestExecutor,
                          TestResultUpdateService testResultUpdateService) {
        this.graphNodeRepository = graphNodeRepository;
        this.testCaseRepository = testCaseRepository;
        this.testResultRepository = testResultRepository;
        this.objectMapper = objectMapper;
        this.apiTestExecutor = apiTestExecutor;
        this.dbAssertionExecutor = dbAssertionExecutor;
        this.e2eTestExecutor = e2eTestExecutor;
        this.testResultUpdateService = testResultUpdateService;
    }

    /**
     * 生成测试用例
     */
    @Transactional
    public String generateTestCases(GenerateTestCasesRequest request) {
        String executionId = UUID.randomUUID().toString();
        String versionId = request.getVersionId();

        log.info("Starting generate test cases for versionId: {}", versionId);

        GenerateTestCasesRequest.Scope scope = request.getScope();
        List<String> nodeTypes = scope != null ? scope.getNodeTypes() : Arrays.asList("ApiEndpoint");

        List<GraphNode> nodes = graphNodeRepository.lambdaQuery()
                .eq(GraphNode::getVersionId, versionId)
                .in(GraphNode::getNodeType, nodeTypes)
                .eq(GraphNode::getStatus, "CONFIRMED")
                .list();

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
     */
    private void generateApiTestCase(GraphNode apiNode) {
        String[] parts = apiNode.getNodeKey().split(" ", 2);
        String method = parts.length > 1 ? parts[0] : "GET";
        String path = parts.length > 1 ? parts[1] : apiNode.getNodeKey();

        TestCase testCase = new TestCase();
        testCase.setId(UUID.randomUUID().toString());
        testCase.setProjectId(apiNode.getProjectId());
        testCase.setVersionId(apiNode.getVersionId());
        testCase.setCaseCode("API_" + Math.abs(apiNode.getNodeKey().hashCode()));
        testCase.setCaseName(apiNode.getDisplayName() != null ? apiNode.getDisplayName() + " 测试" : "API测试");
        testCase.setCaseType("API");
        testCase.setTargetNodeId(apiNode.getId());
        testCase.setPriority("P2");
        testCase.setGeneratedBy("AUTO");
        testCase.setConfidence(BigDecimal.valueOf(0.8));
        testCase.setStatus("GENERATED");
        testCase.setCreatedAt(LocalDateTime.now());
        testCase.setUpdatedAt(LocalDateTime.now());

        Map<String, Object> preconditions = new HashMap<>();
        preconditions.put("type", "LOGIN");
        preconditions.put("role", "admin");

        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> step = new HashMap<>();
        step.put("action", "CALL_API");
        step.put("method", method);
        step.put("path", path);
        steps.add(step);

        Map<String, Object> expected = new HashMap<>();
        expected.put("httpStatus", 200);

        try {
            testCase.setPreconditions(objectMapper.writeValueAsString(preconditions));
            testCase.setSteps(objectMapper.writeValueAsString(steps));
            testCase.setExpectedResult(objectMapper.writeValueAsString(expected));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize test case", e);
        }

        testCaseRepository.insert(testCase);
    }

    /**
     * 为数据库表生成断言测试用例
     */
    private void generateDbAssertionTestCase(GraphNode tableNode) {
        TestCase testCase = new TestCase();
        testCase.setId(UUID.randomUUID().toString());
        testCase.setProjectId(tableNode.getProjectId());
        testCase.setVersionId(tableNode.getVersionId());
        testCase.setCaseCode("DB_" + Math.abs(tableNode.getNodeKey().hashCode()));
        testCase.setCaseName("表 " + tableNode.getNodeName() + " 数据验证");
        testCase.setCaseType("DB");
        testCase.setTargetNodeId(tableNode.getId());
        testCase.setPriority("P3");
        testCase.setGeneratedBy("AUTO");
        testCase.setConfidence(BigDecimal.valueOf(0.7));
        testCase.setStatus("GENERATED");
        testCase.setCreatedAt(LocalDateTime.now());
        testCase.setUpdatedAt(LocalDateTime.now());

        Map<String, Object> assertion = new HashMap<>();
        assertion.put("type", "ROW_COUNT");
        assertion.put("table", tableNode.getNodeName());
        assertion.put("operator", "GREATER_THAN");
        assertion.put("expected", 0);

        List<Map<String, Object>> assertions = new ArrayList<>();
        assertions.add(assertion);

        try {
            testCase.setSteps(objectMapper.writeValueAsString(assertions));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize DB test case", e);
        }

        testCaseRepository.insert(testCase);
    }

    /**
     * 为前端页面/组件生成 Playwright E2E 测试用例
     */
    private void generateE2ETestCase(GraphNode pageNode) {
        TestCase testCase = new TestCase();
        testCase.setId(UUID.randomUUID().toString());
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
        String executionId = UUID.randomUUID().toString();
        String versionId = request.getVersionId();
        List<String> caseIds = request.getCaseIds();
        String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl() : "http://localhost:5173";

        log.info("Starting test run executionId={}, versionId={}, caseCount={}",
                executionId, versionId, caseIds.size());

        List<CompletableFuture<TestResult>> futures = new ArrayList<>();

        for (String caseId : caseIds) {
            TestCase testCase = testCaseRepository.getById(caseId);
            if (testCase == null) continue;

            TestResult result = new TestResult();
            result.setId(UUID.randomUUID().toString());
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
                    result.setId(UUID.randomUUID().toString());
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
            result.setId(UUID.randomUUID().toString());
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
