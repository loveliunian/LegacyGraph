package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.service.GraphMergeService;
import io.github.legacygraph.service.GraphQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    private final GraphNodeRepository nodeRepository;

    /**
     * 构造函数注入
     * @param graphQueryService 图谱查询服务
     * @param graphMergeService 图谱合并服务
     * @param nodeRepository 图节点数据访问层
     */
    public GraphQueryController(GraphQueryService graphQueryService,
                                GraphMergeService graphMergeService,
                                GraphNodeRepository nodeRepository) {
        this.graphQueryService = graphQueryService;
        this.graphMergeService = graphMergeService;
        this.nodeRepository = nodeRepository;
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
            @RequestParam String versionId,
            @Parameter(description = "API接口路径或方法签名", required = true)
            @RequestParam String api) {
        try {
            List<Map<String, Object>> result = graphQueryService.getApiCallChain(versionId, api);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
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
            @RequestParam String versionId,
            @Parameter(description = "数据库表名", required = true)
            @RequestParam String tableName) {
        try {
            List<Map<String, Object>> result = graphQueryService.getTableImpact(versionId, tableName);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
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
            @RequestParam String versionId,
            @Parameter(description = "模块名称", required = true)
            @RequestParam String module) {
        try {
            Map<String, Object> result = graphQueryService.getFeatureView(versionId, module);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
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
            @RequestParam String versionId,
            @Parameter(description = "业务域名称", required = true)
            @RequestParam String domain) {
        try {
            Map<String, Object> result = graphQueryService.getBusinessView(versionId, domain);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
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
        GraphNode a = nodeRepository.selectById(candidate.getNodeAId());
        GraphNode b = nodeRepository.selectById(candidate.getNodeBId());
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
    @Operation(summary = "获取统一图谱全量数据", description = "查询指定扫描版本的所有节点和边，过滤后返回用于可视化")
    public Result<Map<String, Object>> getUnifiedGraph(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "扫描版本ID", required = true)
            @RequestParam String versionId,
            @Parameter(description = "最低置信度", required = false)
            @RequestParam(defaultValue = "0.0") Double minConfidence) {
        try {
            Map<String, Object> result = graphQueryService.getUnifiedGraph(versionId, minConfidence);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 获取项目扫描版本列表
     * 查询项目的所有扫描版本，用于选择展示哪个版本的图谱
     * @param projectId 项目ID
     * @return 扫描版本列表，包含节点和边数量统计
     */
    @GetMapping("/scan-versions")
    @Operation(summary = "获取扫描版本列表", description = "查询项目的所有扫描版本，包含统计信息")
    public Result<List<Map<String, Object>>> getScanVersions(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId) {
        try {
            List<Map<String, Object>> result = graphQueryService.getScanVersions(projectId);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
