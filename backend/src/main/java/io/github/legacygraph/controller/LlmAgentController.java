package io.github.legacygraph.controller;

import io.github.legacygraph.agent.*;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.FactExtractionResult;
import io.github.legacygraph.dto.GeneratedTestCase;
import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.service.GraphMergeService;
import io.github.legacygraph.service.VectorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * LLM Agent 控制器
 * 依照详细设计文档中的关键 API 列表
 */
@Slf4j
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Tag(name = "LLM Agent API", description = "LLM 驱动的各类 Agent 执行接口")
public class LlmAgentController {

    private final CodeFactAgent codeFactAgent;
    private final DocUnderstandingAgent docUnderstandingAgent;
    private final FeatureMappingAgent featureMappingAgent;
    private final GraphMergeAgent graphMergeAgent;
    private final TestCaseAgent testCaseAgent;
    private final ReviewAgent reviewAgent;
    private final GraphMergeService graphMergeService;
    private final VectorizationService vectorizationService;
    private final GraphNodeRepository nodeRepository;

    /**
     * 运行指定 Agent
     * POST /api/agents/run
     */
    @PostMapping("/run")
    @Operation(summary = "运行指定 Agent")
    public Result<?> runAgent(@RequestBody RunAgentRequest request) {
        String agentType = request.getAgentType();
        String projectId = request.getProjectId();
        Map<String, String> variables = request.getVariables();

        // 根据 agentType 路由到对应实现
        return switch (agentType.toLowerCase()) {
            case "codefact" -> {
                String codeContent = variables.get("codeContent");
                String sourcePath = variables.get("sourcePath");
                FactExtractionResult result = codeFactAgent.extractFacts(projectId, codeContent, sourcePath);
                yield Result.ok(result);
            }
            case "docunderstanding" -> {
                String docContent = variables.get("docContent");
                String sourcePath = variables.get("sourcePath");
                DocUnderstandingAgent.BusinessFactExtraction result =
                        docUnderstandingAgent.extractBusinessFacts(projectId, docContent, sourcePath);
                yield Result.ok(result);
            }
            case "featuremapping" -> {
                FeatureMappingAgent.MappingResult result =
                        featureMappingAgent.mapFeatures(parseMappingRequest(projectId, variables));
                yield Result.ok(result);
            }
            case "testcasegeneration" -> {
                TestCaseAgent.TestGenerationResult result =
                        testCaseAgent.generateTestCases(parseTestGenRequest(projectId, variables));
                yield Result.ok(result);
            }
            default -> Result.badRequest("Unknown agent type: " + agentType);
        };
    }

    /**
     * 图谱合并候选查询
     * GET /api/graph/merge/candidates
     */
    @GetMapping("/graph/merge/candidates")
    @Operation(summary = "获取合并候选对")
    public Result<List<GraphMergeService.MergeCandidate>> getMergeCandidates(
            @RequestParam String projectId,
            @RequestParam String nodeType) {
        List<GraphMergeService.MergeCandidate> candidates =
                graphMergeService.findMergeCandidates(projectId, nodeType);
        return Result.ok(candidates);
    }

    /**
     * 图谱合并决策（调用 LLM）
     * POST /api/graph/merge/decide
     */
    @PostMapping("/graph/merge/decide")
    @Operation(summary = "LLM 决策两个节点是否合并")
    public Result<GraphMergeDecision> decideMerge(
            @RequestParam String projectId,
            @RequestParam String nodeAId,
            @RequestParam String nodeBId) {
        GraphNode nodeA = nodeRepository.selectById(nodeAId);
        GraphNode nodeB = nodeRepository.selectById(nodeBId);
        if (nodeA == null || nodeB == null) {
            return Result.badRequest("Node not found");
        }
        GraphMergeDecision decision = graphMergeAgent.decideMerge(projectId, nodeA, nodeB,
                0.8, 0.7, 0.6, 0.5, 0.7);
        return Result.ok(decision);
    }

    /**
     * 执行自动合并
     * POST /api/graph/merge/execute
     */
    @PostMapping("/graph/merge/execute")
    @Operation(summary = "执行节点合并")
    public Result<Void> executeMerge(
            @RequestParam String projectId,
            @RequestParam String targetNodeId,
            @RequestParam String mergeNodeId) {
        graphMergeService.executeMerge(projectId, targetNodeId, mergeNodeId);
        return Result.ok();
    }

    /**
     * 根据功能生成测试用例
     * POST /api/tests/generate
     */
    @PostMapping("/tests/generate")
    @Operation(summary = "根据功能节点生成测试用例")
    public Result<List<GeneratedTestCase>> generateTests(@RequestBody TestCaseAgent.TestGenerationRequest request) {
        List<GeneratedTestCase> testCases = testCaseAgent.generateTestCases(request);
        return Result.ok(testCases);
    }

    /**
     * 生成审核建议
     * POST /api/review/suggest
     */
    @PostMapping("/review/suggest")
    @Operation(summary = "为待审核项生成审核建议")
    public Result<ReviewAgent.ReviewResult> suggestReview(@RequestBody ReviewAgent.ReviewRequest request) {
        ReviewAgent.ReviewResult result = reviewAgent.generateReviewSuggestion(request);
        return Result.ok(result);
    }

    private FeatureMappingAgent.MappingRequest parseMappingRequest(String projectId, Map<String, String> vars) {
        FeatureMappingAgent.MappingRequest req = new FeatureMappingAgent.MappingRequest();
        req.setProjectId(projectId);
        req.setVueCode(vars.get("vueCode"));
        req.setApiDefinitions(vars.get("apiDefinitions"));
        req.setControllerCode(vars.get("controllerCode"));
        req.setPermissionInfo(vars.get("permissionInfo"));
        req.setProductDoc(vars.get("productDoc"));
        return req;
    }

    private TestCaseAgent.TestGenerationRequest parseTestGenRequest(String projectId, Map<String, String> vars) {
        TestCaseAgent.TestGenerationRequest req = new TestCaseAgent.TestGenerationRequest();
        req.setProjectId(projectId);
        req.setFeatureKey(vars.get("featureKey"));
        req.setFeatureName(vars.get("featureName"));
        req.setApiEndpoint(vars.get("apiEndpoint"));
        req.setHttpMethod(vars.get("httpMethod"));
        req.setRequestSchema(vars.get("requestSchema"));
        req.setRelatedTables(vars.get("relatedTables"));
        req.setBusinessRules(vars.get("businessRules"));
        return req;
    }

    @lombok.Data
    public static class RunAgentRequest {
        private String agentType;
        private String projectId;
        private Map<String, String> variables;
    }
}
