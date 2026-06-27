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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.UUID;

/**
 * 测试用例生成服务
 * 根据图谱自动生成API测试用例和数据库断言
 */
@Slf4j
@Service
public class TestCaseService {

    private final GraphNodeRepository graphNodeRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final ObjectMapper objectMapper;

    public TestCaseService(GraphNodeRepository graphNodeRepository,
                          TestCaseRepository testCaseRepository,
                          TestResultRepository testResultRepository,
                          ObjectMapper objectMapper) {
        this.graphNodeRepository = graphNodeRepository;
        this.testCaseRepository = testCaseRepository;
        this.testResultRepository = testResultRepository;
        this.objectMapper = objectMapper;
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

        // 查询符合条件的节点
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
            }
        }

        log.info("Generated {} test cases", generatedCount);
        return executionId;
    }

    /**
     * 为API接口生成测试用例
     */
    private void generateApiTestCase(GraphNode apiNode) {
        // 解析nodeKey 获取method和path
        // nodeKey格式: "POST /api/process/start"
        String[] parts = apiNode.getNodeKey().split(" ", 2);
        String method = parts.length > 1 ? parts[0] : "GET";
        String path = parts.length > 1 ? parts[1] : apiNode.getNodeKey();

        // 构建测试用例
        TestCase testCase = new TestCase();
        testCase.setId(UUID.randomUUID().toString());
        testCase.setProjectId(apiNode.getProjectId());
        testCase.setVersionId(apiNode.getVersionId());
        testCase.setCaseCode("API_" + apiNode.getNodeKey().hashCode());
        testCase.setCaseName(apiNode.getDisplayName() != null ? apiNode.getDisplayName() + " 测试" : "API测试");
        testCase.setCaseType("API");
        testCase.setTargetNodeId(apiNode.getId());
        testCase.setPriority("P2");
        testCase.setGeneratedBy("AI_AUTO");
        testCase.setConfidence(java.math.BigDecimal.valueOf(0.8));
        testCase.setStatus("GENERATED");
        testCase.setCreatedAt(LocalDateTime.now());
        testCase.setUpdatedAt(LocalDateTime.now());

        // 构建默认步骤和期望结果
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
     * 执行测试用例
     */
    @Transactional
    public String startTestRun(StartTestRunRequest request) {
        String executionId = UUID.randomUUID().toString();
        String versionId = request.getVersionId();
        List<String> caseIds = request.getCaseIds();

        log.info("Starting test run executionId={}, versionId={}, caseCount={}",
                executionId, versionId, caseIds.size());

        // 为每个测试用例创建测试结果记录
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
        }

        // TODO: 异步执行测试
        log.info("Test run started: {}", executionId);
        return executionId;
    }
}
