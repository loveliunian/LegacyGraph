package io.github.legacygraph.controller;

import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.builder.FeatureSliceBuilder;
import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.dto.graph.FeatureSlice;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.service.GraphMergeService;
import io.github.legacygraph.service.GraphQueryService;
import io.github.legacygraph.service.GapFinderService;
import io.github.legacygraph.service.KnowledgeClaimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * 知识图谱查询控制器
 * 提供知识图谱的各种查询功能：
 * <ul>
 *   <li>接口调用链查询：从入口API逐层向下追踪完整调用关系</li>
 *   <li>表影响范围分析：查询哪些业务模块依赖某个数据库表</li>
 *   <li>功能视图查询：按模块展示功能模块间的依赖关系</li>
 *   <li>业务视图查询：按业务域展示业务关系</li>
 *   <li>图谱合并：对重复节点进行合并去重</li>
 * </ul>
 */
@RestController
@RequestMapping("/lg/projects/{projectId}")
@Tag(name = "图谱查询", description = "接口调用链、表影响范围、功能/业务视图查询，以及图谱合并")
public class GraphQueryController {

    private final GraphQueryService graphQueryService;
    private final GraphMergeService graphMergeService;
    private final Neo4jGraphDao neo4jGraphDao;
    private final FeatureSliceBuilder featureSliceBuilder;
    private final KnowledgeClaimService knowledgeClaimService;
    private final GapFinderService gapFinderService;

    /**
     * 构造函数注入
     * @param graphQueryService 图谱查询服务
     * @param graphMergeService 图谱合并服务
     * @param neo4jGraphDao Neo4j图数据访问层
     */
    public GraphQueryController(GraphQueryService graphQueryService,
                                GraphMergeService graphMergeService,
                                Neo4jGraphDao neo4jGraphDao,
                                FeatureSliceBuilder featureSliceBuilder,
                                KnowledgeClaimService knowledgeClaimService,
                                GapFinderService gapFinderService) {
        this.graphQueryService = graphQueryService;
        this.graphMergeService = graphMergeService;
        this.neo4jGraphDao = neo4jGraphDao;
        this.featureSliceBuilder = featureSliceBuilder;
        this.knowledgeClaimService = knowledgeClaimService;
        this.gapFinderService = gapFinderService;
    }

    /**
     * 查询接口完整调用链
     * 从指定的入口API出发，向下追踪完整的方法调用链路
     * @param projectId 项目ID
     * @param versionId 扫描版本ID
     * @param api API接口路径或方法签名
     * @return 调用链节点列表，包含层级关系
     */
    @GetMapping("/graph/api-chain")
    @Operation(summary = "查询接口完整调用链", description = "从入口API出发，逐层向下追踪完整的方法调用链路")
    public Result<List<Map<String, Object>>> getApiChain(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "扫描版本ID", required = true)
            @RequestParam(required = false) String versionId,
            @Parameter(description = "API接口路径或方法签名", required = true)
            @RequestParam String api) {
        List<Map<String, Object>> result = graphQueryService.getApiCallChain(projectId, versionId, api);
        return Result.success(result);
    }

    /**
     * 查询表影响范围
     * 分析哪些应用、哪些API接口依赖指定的数据库表，用于评估变更影响
     * @param projectId 项目ID
     * @param versionId 扫描版本ID
     * @param tableName 数据库表名
     * @return 影响范围，包含依赖该表的所有API和应用模块
     */
    @GetMapping("/graph/table-impact")
    @Operation(summary = "查询表影响范围", description = "分析哪些API和模块依赖指定的数据库表，用于评估变更影响")
    public Result<List<Map<String, Object>>> getTableImpact(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "扫描版本ID", required = true)
            @RequestParam(required = false) String versionId,
            @Parameter(description = "数据库表名", required = true)
            @RequestParam String tableName) {
        List<Map<String, Object>> result = graphQueryService.getTableImpact(projectId, versionId, tableName);
        return Result.success(result);
    }

    /**
     * 获取版本中所有数据库表节点（仅节点，不含边，轻量查询）
     * 用于数据血缘页面快速加载表列表
     */
    @GetMapping("/graph/tables")
    @Operation(summary = "获取数据库表节点列表", description = "查询指定版本的所有Table类型节点，仅返回节点不返回边，用于轻量表列表展示")
    public Result<List<Map<String, Object>>> getTablesNodes(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "扫描版本ID", required = true)
            @RequestParam(required = false) String versionId) {
        List<Map<String, Object>> result = graphQueryService.getTablesNodes(projectId, versionId);
        return Result.success(result);
    }

    /**
     * 查询功能图谱视图
     * 按功能模块展示知识图谱，输出该模块下的所有节点和边关系
     * @param projectId 项目ID
     * @param versionId 扫描版本ID
     * @param module 模块名称
     * @return 功能视图数据，包含节点和边
     */
    @GetMapping("/graph/feature-view")
    @Operation(summary = "查询功能图谱视图", description = "按功能模块展示知识图谱，输出该模块下的所有节点和边关系")
    public Result<Map<String, Object>> getFeatureView(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "扫描版本ID", required = true)
            @RequestParam(required = false) String versionId,
            @Parameter(description = "模块名称", required = false)
            @RequestParam(required = false) String module) {
        Map<String, Object> result = graphQueryService.getFeatureView(projectId, versionId, module);
        return Result.success(result);
    }

    /**
     * 查询业务图谱视图
     * 按业务域展示知识图谱，输出该业务域下的所有业务节点和关系
     * @param projectId 项目ID
     * @param versionId 扫描版本ID
     * @param domain 业务域名称
     * @return 业务视图数据，包含节点和边
     */
    @GetMapping("/graph/business-view")
    @Operation(summary = "查询业务图谱视图", description = "按业务域展示知识图谱，输出该业务域下的所有业务节点和关系")
    public Result<Map<String, Object>> getBusinessView(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "扫描版本ID", required = true)
            @RequestParam(required = false) String versionId,
            @Parameter(description = "业务域名称", required = false)
            @RequestParam(required = false) String domain) {
        Map<String, Object> result = graphQueryService.getBusinessView(projectId, versionId, domain);
        return Result.success(result);
    }

    // ==================== 图谱合并接口 ====================

    @GetMapping("/graph/merge/candidates")
    @Operation(summary = "获取合并候选对", description = "为指定项目和节点类型查找可能需要合并的候选对")
    public Result<List<GraphMergeService.MergeCandidate>> getMergeCandidates(
            @PathVariable String projectId,
            @RequestParam String nodeType) {
        List<GraphMergeService.MergeCandidate> candidates = graphMergeService.findMergeCandidates(projectId, nodeType);
        return Result.success(candidates);
    }

    @PostMapping("/graph/merge/decide")
    @Operation(summary = "LLM决策是否合并", description = "使用大语言模型判断两个节点是否应该合并")
    public Result<GraphMergeDecision> decideMerge(
            @PathVariable String projectId,
            @RequestBody GraphMergeService.MergeCandidate candidate) {
        GraphNode a = neo4jGraphDao.findNodeById(candidate.getNodeAId()).orElse(null);
        GraphNode b = neo4jGraphDao.findNodeById(candidate.getNodeBId()).orElse(null);
        if (a == null || b == null) {
            return Result.error("节点不存在");
        }
        GraphMergeDecision decision = graphMergeService.decideMerge(projectId, a, b, candidate);
        return Result.success(decision);
    }

    @PostMapping("/graph/merge/execute")
    @Operation(summary = "执行合并", description = "将合并节点合并到目标节点，标记合并节点为删除")
    @Transactional
    public Result<Void> executeMerge(
            @PathVariable String projectId,
            @RequestParam String targetNodeId,
            @RequestParam String mergeNodeId) {
        graphMergeService.executeMerge(projectId, targetNodeId, mergeNodeId);
        return Result.success();
    }

    /**
     * 获取统一图谱全量数据
     * 查询指定扫描版本的所有节点和边，用于统一图谱完整展示
     * @param projectId 项目ID
     * @param versionId 扫描版本ID
     * @param minConfidence 最低置信度过滤
     * @return 统一图谱数据，包含所有节点和边
     */
    @GetMapping("/graph/unified")
    @Operation(summary = "获取统一图谱全量数据", description = "查询指定扫描版本的所有节点和边，支持按置信度和状态过滤")
    public Result<Map<String, Object>> getUnifiedGraph(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "扫描版本ID", required = true)
            @RequestParam(required = false) String versionId,
            @Parameter(description = "最低置信度", required = false)
            @RequestParam(defaultValue = "0.0") Double minConfidence,
            @Parameter(description = "状态过滤：CONFIRMED/PENDING_CONFIRM/REJECTED", required = false)
            @RequestParam(required = false) String statusFilter,
            jakarta.servlet.http.HttpServletRequest request) {
        // 兼容前端 ?params[versionId]=xxx 嵌套格式
        if (versionId == null || versionId.isBlank()) {
            versionId = request.getParameter("params[versionId]");
        }
        Map<String, Object> result = graphQueryService.getUnifiedGraph(versionId, minConfidence, statusFilter);
        return Result.success(result);
    }

    /**
     * 获取功能切片列表：总图上的 Feature → Page → API → Method → SQL → Table 投影视图。
     */
    @GetMapping("/graph/feature-slices")
    @Operation(summary = "获取功能切片列表", description = "按 Feature 节点构建功能切片投影视图")
    public Result<List<FeatureSlice>> getFeatureSlices(
            @PathVariable String projectId,
            @RequestParam(required = false) String versionId) {
        return Result.success(featureSliceBuilder.buildAllSlices(projectId, versionId));
    }

    /**
     * 获取单个功能切片详情。sliceId 使用 Feature 节点 ID。
     */
    @GetMapping("/graph/feature-slices/{sliceId}")
    @Operation(summary = "获取功能切片详情", description = "按 Feature 节点 ID 构建单个功能切片详情")
    public Result<FeatureSlice> getFeatureSliceDetail(
            @PathVariable String projectId,
            @PathVariable String sliceId) {
        return Result.success(featureSliceBuilder.buildSliceById(projectId, sliceId));
    }

    /**
     * 获取图谱质量统计。
     */
    @GetMapping("/graph/quality")
    @Operation(summary = "获取图谱质量统计", description = "返回节点、边、证据、AI-only/runtime-only 等质量指标")
    public Result<Map<String, Object>> getGraphQualityReport(
            @PathVariable String projectId,
            @RequestParam(required = false) String versionId) {
        Map<String, Object> stats = new LinkedHashMap<>(versionId != null && !versionId.isBlank()
                ? neo4jGraphDao.versionGraphStats(projectId, versionId)
                : neo4jGraphDao.graphStats(projectId));
        ensureQualityDefaults(stats);
        appendClaimGapStats(stats, projectId, versionId);
        return Result.success(stats);
    }

    /**
     * 获取漂移队列（已下沉到 GraphQueryService）。
     */
    @GetMapping("/graph/drift-queue")
    @Operation(summary = "获取漂移队列", description = "返回静态-only、运行时-only、文档-only、低置信等待处理项")
    public Result<Map<String, Object>> getDriftQueue(
            @PathVariable String projectId,
            @RequestParam(required = false, defaultValue = "all") String type) {
        return Result.success(graphQueryService.getDriftQueue(projectId, type));
    }

    /**
     * 获取项目扫描版本列表（分页）
     * 查询项目的所有扫描版本，用于选择展示哪个版本的图谱
     * @param projectId 项目ID
     * @param query 分页参数
     * @return 分页后的扫描版本列表，包含节点和边数量统计
     */
    @GetMapping("/scan-versions")
    @Operation(summary = "获取扫描版本列表", description = "分页查询项目的扫描版本，包含统计信息")
    public Result<PageResult<Map<String, Object>>> getScanVersions(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            PageQuery query) {
        PageResult<Map<String, Object>> result = graphQueryService.getScanVersions(
                projectId, query.getPageNum(), query.getPageSize());
        return Result.success(result);
    }

    private void ensureQualityDefaults(Map<String, Object> stats) {
        stats.putIfAbsent("totalNodes", 0L);
        stats.putIfAbsent("confirmedNodes", 0L);
        stats.putIfAbsent("pendingNodes", 0L);
        stats.putIfAbsent("avgConfidence", 0.0);
        stats.putIfAbsent("withEvidenceCount", 0L);
        stats.putIfAbsent("noEvidenceNodes", 0L);
        stats.putIfAbsent("aiOnlyNodes", 0L);
        stats.putIfAbsent("totalEdges", 0L);
        stats.putIfAbsent("confirmedEdges", 0L);
        stats.putIfAbsent("pendingEdges", 0L);
        stats.putIfAbsent("noEvidenceEdges", 0L);
        stats.putIfAbsent("aiOnlyEdges", 0L);
        stats.putIfAbsent("runtimeOnlyEdges", 0L);
        stats.putIfAbsent("claimCount", 0L);
        stats.putIfAbsent("confirmedClaimCount", 0L);
        stats.putIfAbsent("pendingClaimCount", 0L);
        stats.putIfAbsent("conflictedClaimCount", 0L);
        stats.putIfAbsent("aiOnlyClaimCount", 0L);
        stats.putIfAbsent("gapCount", 0L);
        stats.putIfAbsent("openGapCount", 0L);
        stats.putIfAbsent("highSeverityGapCount", 0L);
        stats.putIfAbsent("gapCountByType", Collections.emptyMap());
    }

    private void appendClaimGapStats(Map<String, Object> stats, String projectId, String versionId) {
        if (versionId == null || versionId.isBlank()
                || knowledgeClaimService == null || gapFinderService == null) {
            return;
        }
        try {
            Map<String, Long> claimStatusCounts = knowledgeClaimService.countClaimsByStatus(projectId, versionId);
            stats.put("claimCount", claimStatusCounts.values().stream().mapToLong(Long::longValue).sum());
            stats.put("confirmedClaimCount", claimStatusCounts.getOrDefault("CONFIRMED", 0L));
            stats.put("pendingClaimCount", claimStatusCounts.getOrDefault("PENDING_CONFIRM", 0L));
            stats.put("conflictedClaimCount", claimStatusCounts.getOrDefault("CONFLICTED", 0L));
            stats.put("aiOnlyClaimCount", knowledgeClaimService.countAiOnlyClaims(projectId, versionId));

            Map<String, Long> gapStatusCounts = gapFinderService.countGapsByStatus(projectId, versionId);
            stats.put("gapCount", gapStatusCounts.values().stream().mapToLong(Long::longValue).sum());
            stats.put("openGapCount", gapStatusCounts.getOrDefault("OPEN", 0L)
                    + gapStatusCounts.getOrDefault("REOPENED", 0L));
            stats.put("highSeverityGapCount", gapFinderService.countHighSeverityGaps(projectId, versionId));
            stats.put("gapCountByType", gapFinderService.countGapsByType(projectId, versionId));
        } catch (Exception e) {
            stats.putIfAbsent("claimGapMetricsError", e.getMessage());
        }
    }
}
