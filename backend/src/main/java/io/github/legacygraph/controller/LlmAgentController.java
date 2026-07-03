package io.github.legacygraph.controller;

import io.github.legacygraph.agent.*;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.config.AgentConfigProperties;
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

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * LLM Agent 控制器
 * 提供大语言模型驱动的各类智能Agent执行接口。
 *
 * <p>通用 Agent 路由（{@link #runAgent}）通过 {@link #agentRegistry} 字典分发，
 * 新增 Agent 类型只需在 {@code @PostConstruct} 中注册一行，无需修改 switch/case。
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
    private final SqlAdvisorAgent sqlAdvisorAgent;
    private final TestFailureAnalysisAgent testFailureAnalysisAgent;
    private final ReportInsightAgent reportInsightAgent;
    private final RefactorAgent refactorAgent;
    private final ChangeImpactAgent changeImpactAgent;
    private final MigrationAgent migrationAgent;
    private final PrDescriptionAgent prDescriptionAgent;
    private final GraphMergeService graphMergeService;
    private final Neo4jGraphDao neo4jGraphDao;
    private final AgentConfigProperties agentConfig;

    /**
     * Agent 类型 → 执行器 的字典注册表。
     * key: agentType (小写), value: (projectId, variables) → result
     */
    private final Map<String, BiFunction<String, Map<String, String>, ?>> agentRegistry = new HashMap<>();

    @PostConstruct
    void initAgentRegistry() {
        // 注册所有通用 Agent 处理器 —— 新增 Agent 在此加一行即可
        agentRegistry.put("codefact", (projectId, vars) -> {
            String codeContent = vars.get("codeContent");
            String sourcePath = vars.get("sourcePath");
            return codeFactAgent.extractFacts(projectId, codeContent, sourcePath);
        });
        agentRegistry.put("docunderstanding", (projectId, vars) -> {
            String docContent = vars.get("docContent");
            String sourcePath = vars.get("sourcePath");
            return docUnderstandingAgent.extractBusinessFacts(projectId, docContent, sourcePath);
        });
        agentRegistry.put("featuremapping", (projectId, vars) -> {
            FeatureMappingAgent.MappingRequest req = parseMappingRequest(projectId, vars);
            return featureMappingAgent.mapFeatures(req);
        });
        agentRegistry.put("testcasegeneration", (projectId, vars) ->
                testCaseAgent.generateTestCases(parseTestGenRequest(projectId, vars)));
    }

    /**
     * 通用运行指定Agent
     * 根据Agent类型从字典注册表中查找对应执行器。
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

        BiFunction<String, Map<String, String>, ?> handler = agentRegistry.get(agentType.toLowerCase());
        if (handler == null) {
            return Result.badRequest("Unknown agent type: " + agentType);
        }
        return Result.ok(handler.apply(projectId, variables));
    }

    /**
     * 获取图谱合并候选对
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

        AgentConfigProperties.MergeConfig cfg = agentConfig.getMerge();
        GraphMergeDecision decision = graphMergeAgent.decideMerge(projectId, nodeA, nodeB,
                cfg.getNameWeight(), cfg.getSemanticWeight(), cfg.getStructWeight(),
                cfg.getNeighborWeight(), cfg.getEvidenceWeight());
        return Result.ok(decision);
    }

    /**
     * 执行节点合并
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
     * SQL 性能分析
     */
    @PostMapping("/sql/analyze")
    @Operation(summary = "SQL 性能优化分析", description = "分析 SQL 性能问题并给出优化建议与优化后 SQL")
    public Result<io.github.legacygraph.dto.SqlAdvisorResult> analyzeSql(
            @RequestBody SqlAnalyzeRequest request) {
        io.github.legacygraph.dto.SqlAdvisorResult result = sqlAdvisorAgent.analyze(
                request.getProjectId(), request.getSqlKey(), request.getSql(), request.getSchemaInfo());
        return Result.ok(result);
    }

    /**
     * 测试失败根因分析
     */
    @PostMapping("/tests/analyze-failure")
    @Operation(summary = "测试失败根因分析", description = "根据失败上下文归纳根因、排查步骤与复测建议")
    public Result<io.github.legacygraph.dto.TestFailureAnalysis> analyzeTestFailure(
            @RequestBody TestFailureAnalysisAgent.FailureContext context) {
        io.github.legacygraph.dto.TestFailureAnalysis result = testFailureAnalysisAgent.analyze(context);
        return Result.ok(result);
    }

    /**
     * 报告洞察与行动建议
     */
    @PostMapping("/report/insights")
    @Operation(summary = "报告行动建议", description = "根据图谱指标与缺口摘要生成按优先级排序的行动清单")
    public Result<io.github.legacygraph.dto.ReportInsight> reportInsights(
            @RequestBody ReportInsightRequest request) {
        io.github.legacygraph.dto.ReportInsight result = reportInsightAgent.generateInsights(
                request.getProjectId(), request.getMetrics(), request.getGaps());
        return Result.ok(result);
    }

    // ==================== Phase 4：后置能力 ====================

    @PostMapping("/refactor/suggest")
    @Operation(summary = "代码异味重构建议", description = "分析职责边界并给出拆分建议与重构骨架")
    public Result<io.github.legacygraph.dto.RefactorSuggestion> suggestRefactor(
            @RequestBody RefactorRequest request) {
        return Result.ok(refactorAgent.suggest(request.getProjectId(), request.getTarget(),
                request.getSmellType(), request.getCode()));
    }

    @PostMapping("/change/impact")
    @Operation(summary = "变更影响分析", description = "语义级判断变更类型、严重程度与回归范围")
    public Result<io.github.legacygraph.dto.ChangeImpactAnalysis> analyzeChangeImpact(
            @RequestBody ChangeImpactRequest request) {
        return Result.ok(changeImpactAgent.analyze(request.getProjectId(), request.getChangeTarget(),
                request.getChangeDescription(), request.getDependencies()));
    }

    @PostMapping("/migration/convert")
    @Operation(summary = "迁移代码自动转换", description = "按迁移规则给出转换建议与转换后代码")
    public Result<io.github.legacygraph.dto.MigrationConversion> convertMigration(
            @RequestBody MigrationRequest request) {
        return Result.ok(migrationAgent.convert(request.getProjectId(), request.getMigrationDirection(),
                request.getSourcePath(), request.getCode(), request.getCustomRules()));
    }

    @PostMapping("/pr/describe")
    @Operation(summary = "PR 描述/提交信息生成", description = "按 Conventional Commits 规范生成提交信息与 PR 描述")
    public Result<io.github.legacygraph.dto.PrDescription> describePr(
            @RequestBody PrDescribeRequest request) {
        return Result.ok(prDescriptionAgent.generate(request.getProjectId(), request.getBranch(),
                request.getIssue(), request.getDiff()));
    }

    // ==================== 辅助：解析通用变量 → Agent 专用请求 ====================

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

    // ==================== 请求 DTO ====================

    @lombok.Data
    public static class SqlAnalyzeRequest {
        private String projectId;
        private String sqlKey;
        private String sql;
        private String schemaInfo;
    }

    @lombok.Data
    public static class ReportInsightRequest {
        private String projectId;
        private String metrics;
        private String gaps;
    }

    @lombok.Data
    public static class RefactorRequest {
        private String projectId;
        private String target;
        private String smellType;
        private String code;
    }

    @lombok.Data
    public static class ChangeImpactRequest {
        private String projectId;
        private String changeTarget;
        private String changeDescription;
        private String dependencies;
    }

    @lombok.Data
    public static class MigrationRequest {
        private String projectId;
        private String migrationDirection;
        private String sourcePath;
        private String code;
        private String customRules;
    }

    @lombok.Data
    public static class PrDescribeRequest {
        private String projectId;
        private String branch;
        private String issue;
        private String diff;
    }

    @lombok.Data
    public static class RunAgentRequest {
        @io.swagger.v3.oas.annotations.media.Schema(description = "Agent类型，可选值见 agentRegistry", example = "codefact")
        private String agentType;

        @io.swagger.v3.oas.annotations.media.Schema(description = "项目ID", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
        private String projectId;

        @io.swagger.v3.oas.annotations.media.Schema(description = "Agent参数字典，key为参数名，value为参数值")
        private Map<String, String> variables;
    }
}
