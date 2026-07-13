package io.github.legacygraph.controller;

import io.github.legacygraph.agent.GraphMergeAgent;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.service.graph.GraphMergeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱合并 Controller（graph-merge-optimization-plan.md §五.1 P6）。
 * <p>
 * 提供前端"合并候选面板"所需的 3 个 endpoint：
 * <ul>
 *   <li>{@code GET  /graph/merge/candidates} — 列出指定类型的合并候选对</li>
 *   <li>{@code POST /graph/merge/decide}    — 对中间档候选调用 LLM 复审</li>
 *   <li>{@code POST /graph/merge/execute}   — 执行合并（target ← merge）</li>
 * </ul>
 * </p>
 *
 * <p>所有 endpoint 路径前缀 {@code /lg/projects/{projectId}/graph/merge}，
 * 与前端 {@code src/api/merge.api.ts} 调用对齐。</p>
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/graph/merge")
@RequiredArgsConstructor
@Tag(name = "图谱合并", description = "业务域节点合并候选与裁决")
public class GraphMergeController {

    /** 业务域默认节点类型（与 GraphMergeService.runMergeForProject 默认值对齐） */
    private static final List<String> DEFAULT_NODE_TYPES = Arrays.asList(
            "BusinessDomain", "BusinessProcess", "BusinessObject", "BusinessRule", "Role"
    );

    private final GraphMergeService graphMergeService;
    private final GraphMergeAgent graphMergeAgent;

    /**
     * 获取合并候选对（按节点类型）。
     *
     * @param projectId 项目 ID
     * @param nodeType  节点类型（缺省时遍历业务域默认类型并合并返回）
     * @return 候选对列表（按综合相似度降序）
     */
    @GetMapping("/candidates")
    @Operation(summary = "合并候选对", description = "按节点类型返回已评分的合并候选对（6 维评分）")
    public Result<List<GraphMergeService.MergeCandidate>> listCandidates(
            @PathVariable @NotBlank String projectId,
            @RequestParam(required = false) String nodeType) {
        try {
            List<String> types = (nodeType == null || nodeType.isBlank())
                    ? DEFAULT_NODE_TYPES
                    : List.of(nodeType);
            List<GraphMergeService.MergeCandidate> all = new ArrayList<>();
            for (String t : types) {
                try {
                    all.addAll(graphMergeService.findMergeCandidates(projectId, t));
                } catch (Exception e) {
                    log.warn("GraphMergeController.listCandidates failed for project={}, type={}: {}",
                            projectId, t, e.getMessage());
                }
            }
            all.sort((a, b) -> Double.compare(b.getSimilarityScore(), a.getSimilarityScore()));
            return Result.success(all);
        } catch (Exception e) {
            log.warn("GraphMergeController.listCandidates failed: {}", e.getMessage(), e);
            return Result.error("查询合并候选失败: " + e.getMessage());
        }
    }

    /**
     * 对中间档候选调用 LLM 复审，返回更准确的三档决策。
     * <p>
     * 用于前端"LLM 审核"按钮：用户对候选点击后，把已有 6 维评分与候选对 id 一起提交，
     * 本接口从 Neo4j 重新查出节点后委托 {@link GraphMergeAgent#decideMerge} 决策。
     * </p>
     *
     * @param projectId 项目 ID
     * @param request   候选对 id（nodeAId, nodeBId）+ 已有 5 维评分（可选；缺省则实时计算）
     */
    @PostMapping("/decide")
    @Operation(summary = "LLM 复审候选", description = "对中间档候选调用 LLM 做更准确的合并决策")
    public Result<GraphMergeDecision> decideMerge(
            @PathVariable @NotBlank String projectId,
            @RequestBody MergeDecideRequest request) {
        if (request == null || request.getNodeAId() == null || request.getNodeBId() == null) {
            return Result.error("nodeAId / nodeBId 不能为空");
        }
        try {
            GraphNode nodeA = graphMergeService.findNodeByIdPublic(request.getNodeAId());
            GraphNode nodeB = graphMergeService.findNodeByIdPublic(request.getNodeBId());
            if (nodeA == null || nodeB == null) {
                return Result.error("节点不存在");
            }

            // 各维评分：调用方传入优先；缺省则实时跑 scoreCandidate
            double[] scores = resolveScores(projectId, request, nodeA, nodeB);
            GraphMergeDecision llmDecision;
            try {
                llmDecision = graphMergeAgent.decideMerge(
                        projectId, nodeA, nodeB,
                        scores[0], // name
                        scores[1], // semantic
                        scores[2], // struct
                        scores[3], // neighbor (用 evidence 兜底)
                        scores[4]  // evidence
                );
            } catch (Exception llmEx) {
                // LLM 调用失败（如无 API key）→ 回退到规则评分
                log.warn("GraphMergeController.decideMerge LLM failed, fallback to rule: {}",
                        llmEx.getMessage());
                GraphMergeService.MergeCandidate cand = graphMergeService.scoreCandidatePublic(projectId, nodeA, nodeB);
                llmDecision = graphMergeService.decideMerge(projectId, nodeA, nodeB, cand);
            }
            return Result.success(llmDecision);
        } catch (Exception e) {
            log.warn("GraphMergeController.decideMerge failed: {}", e.getMessage(), e);
            return Result.error("LLM 复审失败: " + e.getMessage());
        }
    }

    /**
     * 执行合并：将 {@code mergeNodeId} 合并到 {@code targetNodeId}。
     *
     * @param projectId     项目 ID
     * @param targetNodeId  保留节点（target）
     * @param mergeNodeId   被合并节点（source）
     */
    @PostMapping("/execute")
    @Operation(summary = "执行合并", description = "将 mergeNode 合并到 targetNode，保留 lineage")
    public Result<Map<String, Object>> executeMerge(
            @PathVariable @NotBlank String projectId,
            @RequestParam @NotBlank String targetNodeId,
            @RequestParam @NotBlank String mergeNodeId) {
        try {
            graphMergeService.executeMerge(projectId, targetNodeId, mergeNodeId);
            Map<String, Object> body = new HashMap<>();
            body.put("targetNodeId", targetNodeId);
            body.put("mergeNodeId", mergeNodeId);
            body.put("mergedAt", java.time.LocalDateTime.now().toString());
            return Result.success(body);
        } catch (Exception e) {
            log.warn("GraphMergeController.executeMerge failed (target={}, merge={}): {}",
                    targetNodeId, mergeNodeId, e.getMessage(), e);
            return Result.error("合并失败: " + e.getMessage());
        }
    }

    // ==================== helpers ====================

    /** 解析 5 维评分（name/semantic/struct/neighbor/evidence） */
    private double[] resolveScores(String projectId, MergeDecideRequest request,
                                   GraphNode nodeA, GraphNode nodeB) {
        double name = request.getNameScore();
        double semantic = request.getSemanticScore();
        double struct = request.getStructScore();
        double evidence = request.getEvidenceScore();
        double neighbor = request.getNeighborScore();

        boolean anyProvided = name > 0 || semantic > 0 || struct > 0 || evidence > 0 || neighbor > 0;
        if (anyProvided) {
            // 调用方只缺 neighbor 时用 evidence 替；name/semantic/struct 缺省补 0
            if (neighbor <= 0) neighbor = evidence;
            return new double[]{name, semantic, struct, neighbor, evidence};
        }
        // 一个都没传：实时算一遍
        GraphMergeService.MergeCandidate c = graphMergeService.scoreCandidatePublic(projectId, nodeA, nodeB);
        double nbr = c.getStructScore(); // LLM 接口的 neighbor 字段用 struct 兜底（同为邻域语义）
        return new double[]{
                c.getNameScore(), c.getSemanticScore(), c.getStructScore(), nbr, c.getEvidenceScore()
        };
    }

    /** 复审请求体 */
    public static class MergeDecideRequest {
        private String nodeAId;
        private String nodeBId;
        private double nameScore;
        private double semanticScore;
        private double structScore;
        private double neighborScore;
        private double evidenceScore;

        public String getNodeAId() { return nodeAId; }
        public void setNodeAId(String nodeAId) { this.nodeAId = nodeAId; }
        public String getNodeBId() { return nodeBId; }
        public void setNodeBId(String nodeBId) { this.nodeBId = nodeBId; }
        public double getNameScore() { return nameScore; }
        public void setNameScore(double nameScore) { this.nameScore = nameScore; }
        public double getSemanticScore() { return semanticScore; }
        public void setSemanticScore(double semanticScore) { this.semanticScore = semanticScore; }
        public double getStructScore() { return structScore; }
        public void setStructScore(double structScore) { this.structScore = structScore; }
        public double getNeighborScore() { return neighborScore; }
        public void setNeighborScore(double neighborScore) { this.neighborScore = neighborScore; }
        public double getEvidenceScore() { return evidenceScore; }
        public void setEvidenceScore(double evidenceScore) { this.evidenceScore = evidenceScore; }
    }
}
