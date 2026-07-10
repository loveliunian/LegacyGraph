package io.github.legacygraph.task.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.TestCaseAgent;
import io.github.legacygraph.common.ScanStep;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.GeneratedTestCase;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.repository.TestCaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI_TEST_GENERATE — 高价值节点测试用例生成（autoGenerateTestCase 开启时执行）。
 */
@Slf4j
@Component
public class TestGenerateStep implements AiScanStepExecutor {

    /** 测试生成的高价值节点上限，避免编排耗时过长 */
    private static final int MAX_TEST_GEN_NODES = 20;

    private final AiScanStepSupport support;
    private final Neo4jGraphDao neo4jGraphDao;
    private final TestCaseAgent testCaseAgent;
    private final TestCaseRepository testCaseRepository;
    private final ObjectMapper objectMapper;

    public TestGenerateStep(AiScanStepSupport support,
                            Neo4jGraphDao neo4jGraphDao,
                            TestCaseAgent testCaseAgent,
                            TestCaseRepository testCaseRepository,
                            ObjectMapper objectMapper) {
        this.support = support;
        this.neo4jGraphDao = neo4jGraphDao;
        this.testCaseAgent = testCaseAgent;
        this.testCaseRepository = testCaseRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getStepName() {
        return "AI_TEST_GENERATE";
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public ScanStep getScanStep() {
        return ScanStep.MERGE_ENTITIES;
    }

    @Override
    public boolean shouldExecute(StepExecutionContext ctx) {
        boolean shouldExecute = ctx.getConfig() != null && ctx.getConfig().isAutoGenerateTestCase();
        if (ctx.getConfig() != null) {
            log.info("AI_TEST_GENERATE shouldExecute: {} (autoGenerateTestCase={})", 
                    shouldExecute, ctx.getConfig().isAutoGenerateTestCase());
        } else {
            log.info("AI_TEST_GENERATE shouldExecute: false (config is null)");
        }
        return shouldExecute;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext ctx) {
        String projectId = ctx.getProjectId();
        String versionId = ctx.getVersionId();
        ScanTask task = support.createTask(projectId, versionId, "AI_TEST_GENERATE", "测试用例生成");
        try {
            // 查询多种节点类型，与手动生成端点保持一致
            List<GraphNode> allNodes = new ArrayList<>();
            String[] types = {"ApiEndpoint", "Feature", "Controller"};
            for (String t : types) {
                allNodes.addAll(neo4jGraphDao.queryNodes(projectId, versionId,
                        t, null, null, null, MAX_TEST_GEN_NODES));
            }

            // P1 优化：并发调 LLM（复用 docExtractExecutor 4 路；此时 doc/code/mapping 已结束，池空闲）。
            AtomicInteger generated = new AtomicInteger(0);
            // 收集 TestCase 到列表，最后批量 insert（替代逐条 insert 的 312 次 DB 往返）
            List<TestCase> batch = Collections.synchronizedList(new ArrayList<>());
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (GraphNode node : allNodes) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        TestCaseAgent.TestGenerationRequest req = new TestCaseAgent.TestGenerationRequest();
                        req.setProjectId(projectId);
                        req.setFeatureKey(node.getNodeKey());
                        req.setFeatureName(node.getNodeName());
                        req.setApiEndpoint(node.getNodeKey());
                        // B5：从 ApiEndpoint 节点名解析真实 HTTP method（节点名形如 "POST /xyBank/unLock"），
                        // 不再对所有接口硬编码 GET，避免给 POST/PUT/DELETE 接口生成 GET 用例。
                        // Feature/Controller 节点无 method 概念，回退 GET。
                        req.setHttpMethod(resolveHttpMethod(node));

                        List<GeneratedTestCase> cases = testCaseAgent.generateTestCases(req);
                        for (GeneratedTestCase gen : cases) {
                            int idx = generated.incrementAndGet();
                            TestCase tc = buildTestCase(projectId, versionId, node, gen, idx);
                            if (tc != null) {
                                batch.add(tc);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Test generation failed for node {}: {}", node.getNodeKey(), e.getMessage());
                    }
                }, support.getDocExtractExecutor()));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 批量持久化（单次 insertBatch 替代 N 次 insert）
            if (!batch.isEmpty()) {
                try {
                    testCaseRepository.insertBatch(batch);
                } catch (Exception e) {
                    log.warn("Failed to batch insert {} test cases: {}", batch.size(), e.getMessage());
                }
            }

            String summary = "AI 生成测试用例 " + generated.get() + " 条";
            support.completeTask(task, summary, null);
            return StepExecutionResult.builder().success(true).message(summary)
                    .processedCount(generated.get()).build();
        } catch (Exception e) {
            log.error("AI_TEST_GENERATE failed: versionId={}", versionId, e);
            support.completeTask(task, null, e.getMessage());
            return StepExecutionResult.builder().success(false).message(e.getMessage()).build();
        }
    }

    /**
     * 从节点名解析 HTTP method。ApiEndpoint 节点名形如 "POST /xyBank/unLock"，
     * 取首个空格前的 token；非标准 method 或非 ApiEndpoint 节点回退 GET。
     */
    private String resolveHttpMethod(GraphNode node) {
        if (node == null || !"ApiEndpoint".equals(node.getNodeType())) {
            return "GET";
        }
        String name = node.getNodeName();
        if (name == null || name.isBlank()) {
            return "GET";
        }
        int sp = name.indexOf(' ');
        if (sp <= 0) {
            return "GET";
        }
        String method = name.substring(0, sp).toUpperCase(java.util.Locale.ROOT);
        return switch (method) {
            case "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS" -> method;
            default -> "GET";
        };
    }

    /** 构造 TestCase（不落库，由调用方批量 insert）。构造失败返回 null。 */
    private TestCase buildTestCase(String projectId, String versionId, GraphNode node,
                                   GeneratedTestCase gen, int index) {
        try {
            TestCase tc = new TestCase();
            tc.setProjectId(projectId);
            tc.setVersionId(versionId);
            tc.setCaseCode("AI-TC-" + versionId + "-" + index);
            tc.setCaseName(gen.getCaseName() != null ? gen.getCaseName() : node.getNodeName() + " 测试");
            tc.setCaseType(gen.getCaseType() != null ? gen.getCaseType().name() : "API");
            tc.setScenario(support.nonBlank(gen.getFeatureKey(), node.getNodeKey()));
            tc.setTargetNodeId(node.getId());
            tc.setPriority("MEDIUM");
            tc.setPreconditions(toJsonArray(gen.getPreconditions()));
            tc.setSteps(buildStructuredSteps(gen));
            tc.setExpectedResult(buildExpectedResult(gen));
            tc.setConfidence(BigDecimal.valueOf(0.7));
            tc.setStatus("ENABLED");
            tc.setGeneratedBy("LLM");
            tc.setCreatedAt(LocalDateTime.now());
            tc.setUpdatedAt(LocalDateTime.now());
            return tc;
        } catch (Exception e) {
            log.warn("Failed to build test case for node {}: {}", node.getNodeKey(), e.getMessage());
            return null;
        }
    }

    private String buildStructuredSteps(GeneratedTestCase gen) throws Exception {
        // steps 列为 JSONB 类型，输出 JSON 数组，元素为字符串或对象
        List<Object> steps = new ArrayList<>();
        if (gen.getSteps() != null && !gen.getSteps().isEmpty()) {
            steps.addAll(gen.getSteps());
        }
        if (gen.getRequest() != null && !gen.getRequest().isEmpty()) {
            Map<String, Object> requestStep = new HashMap<>();
            requestStep.put("action", "REQUEST");
            requestStep.put("body", gen.getRequest());
            steps.add(requestStep);
        }
        if (gen.getNeedHumanInput() != null && !gen.getNeedHumanInput().isEmpty()) {
            Map<String, Object> humanInputStep = new HashMap<>();
            humanInputStep.put("action", "NEED_HUMAN_INPUT");
            humanInputStep.put("items", gen.getNeedHumanInput());
            steps.add(humanInputStep);
        }
        return objectMapper.writeValueAsString(steps);
    }

    private String buildExpectedResult(GeneratedTestCase gen) throws Exception {
        // expected_result 列为 JSONB NOT NULL，必须输出合法 JSON
        if (gen.getAssertions() == null || gen.getAssertions().isEmpty()) {
            Map<String, Object> defaultExpected = new HashMap<>();
            defaultExpected.put("description", "验证接口返回符合预期");
            return objectMapper.writeValueAsString(defaultExpected);
        }
        return objectMapper.writeValueAsString(gen.getAssertions());
    }

    /**
     * 将 List<String> 序列化为 JSON 数组字符串，null 或空时返回 "[]"。
     * preconditions 列为 JSONB 类型，必须输出合法 JSON。
     */
    private String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }
}
