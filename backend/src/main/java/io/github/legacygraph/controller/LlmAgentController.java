package io.github.legacygraph.controller;

import io.github.legacygraph.agent.*;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.FactExtractionResult;
import io.github.legacygraph.dto.GeneratedTestCase;
import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.service.GraphMergeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * LLM Agent 控制器
 * 提供大语言模型驱动的各类智能Agent执行接口，包括：
 * <ul>
 *   <li>代码事实抽取：从代码中抽取业务事实和知识</li>
 *   <li>文档理解：从文档中抽取业务事实</li>
 *   <li>功能映射：将UI、API、权限映射到功能模块</li>
 *   <li>测试用例生成：根据功能描述生成测试用例</li>
 *   <li>知识图谱合并：对重复节点进行合并决策</li>
 *   <li>代码评审：生成审核建议</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/agents")
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Tag(name = "LLM Agent API", description = "大语言模型驱动的各类智能Agent执行接口")
public class LlmAgentController {

    private final CodeFactAgent codeFactAgent;
    private final DocUnderstandingAgent docUnderstandingAgent;
    private final FeatureMappingAgent featureMappingAgent;
    private final GraphMergeAgent graphMergeAgent;
    private final TestCaseAgent testCaseAgent;
    private final ReviewAgent reviewAgent;
    private final GraphMergeService graphMergeService;
    private final Neo4jGraphDao neo4jGraphDao;

    /**
     * 通用运行指定Agent
     * 根据Agent类型路由到对应的Agent实现，动态传入参数
     * @param request 运行Agent请求，包含Agent类型、项目ID和参数变量
     * @return Agent执行结果，根据Agent类型返回不同的数据结构
     */
    @PostMapping("/run")
    @Operation(summary = "通用运行指定Agent", description = "根据Agent类型动态路由，运行指定的Agent并返回结果")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "执行成功"),
            @ApiResponse(responseCode = "400", description = "未知的Agent类型")
    })
    public Result<?> runAgent(
            @Parameter(description = "运行Agent请求", required = true)
            @RequestBody RunAgentRequest request) {
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
                List<GeneratedTestCase> result =
                        testCaseAgent.generateTestCases(parseTestGenRequest(projectId, variables));
                yield Result.ok(result);
            }
            default -> Result.badRequest("Unknown agent type: " + agentType);
        };
    }

    /**
     * 获取图谱合并候选对
     * 根据相似度算法查找可能需要合并的重复节点对
     * @param projectId 项目ID
     * @param nodeType 节点类型
     * @return 合并候选对列表
     */
    @GetMapping("/graph/merge/candidates")
    @Operation(summary = "获取合并候选对", description = "根据相似度算法查找可能需要合并的重复节点对")
    public Result<List<GraphMergeService.MergeCandidate>> getMergeCandidates(
            @Parameter(description = "项目ID", required = true)
            @RequestParam String projectId,
            @Parameter(description = "节点类型", required = true)
            @RequestParam String nodeType) {
        List<GraphMergeService.MergeCandidate> candidates =
                graphMergeService.findMergeCandidates(projectId, nodeType);
        return Result.ok(candidates);
    }

    /**
     * LLM决策两个节点是否应该合并
     * 调用大语言模型分析两个节点的语义，判断是否应该合并为一个节点
     * @param projectId 项目ID
     * @param nodeAId 第一个节点ID
     * @param nodeBId 第二个节点ID
     * @return 合并决策结果，包含是否合并和理由
     */
    @PostMapping("/graph/merge/decide")
    @Operation(summary = "LLM决策节点合并", description = "调用大语言模型分析两个节点的语义，判断是否应该合并")
    public Result<GraphMergeDecision> decideMerge(
            @Parameter(description = "项目ID", required = true)
            @RequestParam String projectId,
            @Parameter(description = "第一个节点ID", required = true)
            @RequestParam String nodeAId,
            @Parameter(description = "第二个节点ID", required = true)
            @RequestParam String nodeBId) {
        GraphNode nodeA = neo4jGraphDao.findNodeById(nodeAId).orElse(null);
        GraphNode nodeB = neo4jGraphDao.findNodeById(nodeBId).orElse(null);
        if (nodeA == null || nodeB == null) {
            return Result.badRequest("Node not found");
        }
        GraphMergeDecision decision = graphMergeAgent.decideMerge(projectId, nodeA, nodeB,
                0.8, 0.7, 0.6, 0.5, 0.7);
        return Result.ok(decision);
    }

    /**
     * 执行节点合并
     * 将源节点合并到目标节点，然后将源节点标记为删除
     * @param projectId 项目ID
     * @param targetNodeId 目标节点ID，保留这个节点
     * @param mergeNodeId 待合并节点ID，合并后会被标记为删除
     * @return 成功结果
     */
    @PostMapping("/graph/merge/execute")
    @Operation(summary = "执行节点合并", description = "将源节点合并到目标节点，源节点标记为删除")
    public Result<Void> executeMerge(
            @Parameter(description = "项目ID", required = true)
            @RequestParam String projectId,
            @Parameter(description = "目标节点ID，保留", required = true)
            @RequestParam String targetNodeId,
            @Parameter(description = "待合并节点ID，合并后删除", required = true)
            @RequestParam String mergeNodeId) {
        graphMergeService.executeMerge(projectId, targetNodeId, mergeNodeId);
        return Result.ok();
    }

    /**
     * 根据功能节点生成测试用例
     * 使用LLM根据功能描述、API信息和业务规则自动生成测试用例
     * @param request 测试生成请求，包含功能信息和API定义
     * @return 生成的测试用例列表
     */
    @PostMapping("/tests/generate")
    @Operation(summary = "生成测试用例", description = "根据功能节点信息，使用LLM自动生成测试用例")
    public Result<List<GeneratedTestCase>> generateTests(
            @Parameter(description = "测试生成请求", required = true)
            @RequestBody TestCaseAgent.TestGenerationRequest request) {
        List<GeneratedTestCase> testCases = testCaseAgent.generateTestCases(request);
        return Result.ok(testCases);
    }

    /**
     * 生成审核建议
     * 对图谱中的待审核项，使用LLm生成审核建议
     * @param request 审核请求，包含待审核内容
     * @return 审核结果，包含建议和评分
     */
    @PostMapping("/review/suggest")
    @Operation(summary = "生成审核建议", description = "对图谱中的待审核项，使用LLM生成审核建议和评分")
    public Result<ReviewAgent.ReviewResult> suggestReview(
            @Parameter(description = "审核请求", required = true)
            @RequestBody ReviewAgent.ReviewRequest request) {
        ReviewAgent.ReviewResult result = reviewAgent.generateReviewSuggestion(request);
        return Result.ok(result);
    }

    /**
     * 解析功能映射请求
     * 从通用变量map中解析出功能映射请求的各个字段
     * @param projectId 项目ID
     * @param vars 变量map
     * @return 功能映射请求
     */
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

    /**
     * 解析测试用例生成请求
     * 从通用变量map中解析出测试生成请求的各个字段
     * @param projectId 项目ID
     * @param vars 变量map
     * @return 测试用例生成请求
     */
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

    /**
     * 通用运行Agent请求
     */
    @lombok.Data
    public static class RunAgentRequest {
        @io.swagger.v3.oas.annotations.media.Schema(description = "Agent类型，可选值: codefact|docunderstanding|featuremapping|testcasegeneration", example = "codefact")
        private String agentType;

        @io.swagger.v3.oas.annotations.media.Schema(description = "项目ID", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
        private String projectId;

        @io.swagger.v3.oas.annotations.media.Schema(description = "Agent参数字典，key为参数名，value为参数值")
        private Map<String, String> variables;
    }
}
