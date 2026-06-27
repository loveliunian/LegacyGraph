package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.service.GraphMergeService;
import io.github.legacygraph.service.GraphQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/lg/projects/{projectId}")
@Tag(name = "图谱查询", description = "查询调用链、影响范围、业务视图、功能视图")
public class GraphQueryController {

    private final GraphQueryService graphQueryService;
    private final GraphMergeService graphMergeService;
    private final GraphNodeRepository nodeRepository;

    public GraphQueryController(GraphQueryService graphQueryService,
                                GraphMergeService graphMergeService,
                                GraphNodeRepository nodeRepository) {
        this.graphQueryService = graphQueryService;
        this.graphMergeService = graphMergeService;
        this.nodeRepository = nodeRepository;
    }

    @GetMapping("/graph/api-chain")
    @Operation(summary = "查询接口完整调用链")
    public Result<List<Map<String, Object>>> getApiChain(
            @PathVariable String projectId,
            String versionId,
            String api) {
        try {
            List<Map<String, Object>> result = graphQueryService.getApiCallChain(versionId, api);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/graph/table-impact")
    @Operation(summary = "查询表影响范围")
    public Result<List<Map<String, Object>>> getTableImpact(
            @PathVariable String projectId,
            String versionId,
            String tableName) {
        try {
            List<Map<String, Object>> result = graphQueryService.getTableImpact(versionId, tableName);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/graph/feature-view")
    @Operation(summary = "查询功能图谱视图")
    public Result<Map<String, Object>> getFeatureView(
            @PathVariable String projectId,
            String versionId,
            String module) {
        try {
            Map<String, Object> result = graphQueryService.getFeatureView(versionId, module);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/graph/business-view")
    @Operation(summary = "查询业务图谱视图")
    public Result<Map<String, Object>> getBusinessView(
            @PathVariable String projectId,
            String versionId,
            String domain) {
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
}
