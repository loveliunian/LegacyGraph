package io.github.legacygraph.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.gap.GapTaskView;
import io.github.legacygraph.entity.GapTask;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.service.graph.GapFinderService;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识断言与缺口查询控制器。
 * <p>
 * 提供 KnowledgeClaim 和 GapTask 的查询、详情和缺���解决接口。
 * 所有数据均通过 Service 层访问，不直接依赖 Repository。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/knowledge")
@Tag(name = "知识查询", description = "知识断言（Claim）与缺口（GapTask）查询与操作")
public class KnowledgeController {

    private final KnowledgeClaimService knowledgeClaimService;
    private final GapFinderService gapFinderService;
    private final ObjectMapper objectMapper;

    public KnowledgeController(KnowledgeClaimService knowledgeClaimService,
                               GapFinderService gapFinderService,
                               ObjectMapper objectMapper) {
        this.knowledgeClaimService = knowledgeClaimService;
        this.gapFinderService = gapFinderService;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询 Claim 列表（支持多条件过滤）。
     */
    @GetMapping("/claims")
    @Operation(summary = "查询知识断言列表", description = "按 projectId/versionId/subjectType/predicate/status/sourceType 过滤 Claim")
    public Result<List<KnowledgeClaim>> listClaims(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "扫描版本ID")
            @RequestParam(required = false) String versionId,
            @Parameter(description = "主体类型")
            @RequestParam(required = false) String subjectType,
            @Parameter(description = "谓词")
            @RequestParam(required = false) String predicate,
            @Parameter(description = "状态")
            @RequestParam(required = false) String status,
            @Parameter(description = "来源类型")
            @RequestParam(required = false) String sourceType,
            @Parameter(description = "返回条数上限（默认100，最大500）")
            @RequestParam(defaultValue = "100") int limit) {

        List<KnowledgeClaim> claims = knowledgeClaimService.listClaims(
                projectId, versionId, subjectType, predicate, status, sourceType, limit);
        return Result.success(claims);
    }

    /**
     * 获取单条 Claim 详情。
     */
    @GetMapping("/claims/{id}")
    @Operation(summary = "获取知识断言详情", description = "按 Claim ID 获取单条断言详情")
    public Result<KnowledgeClaim> getClaim(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "Claim ID", required = true)
            @PathVariable String id) {

        KnowledgeClaim claim = knowledgeClaimService.getClaim(projectId, id);
        if (claim == null) {
            return Result.error("Claim not found");
        }
        return Result.success(claim);
    }

    /**
     * 查询 GapTask 列表（支持多条件过滤）。
     */
    @GetMapping("/gaps")
    @Operation(summary = "查询知识缺口列表", description = "按 projectId/versionId/gapType/status/severity 过滤 GapTask")
    public Result<List<GapTaskView>> listGaps(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "扫描版本ID")
            @RequestParam(required = false) String versionId,
            @Parameter(description = "缺口类型")
            @RequestParam(required = false) String gapType,
            @Parameter(description = "状态")
            @RequestParam(required = false) String status,
            @Parameter(description = "严重度")
            @RequestParam(required = false) String severity,
            @Parameter(description = "返回条数上限（默认100，最大500）")
            @RequestParam(defaultValue = "100") int limit) {

        List<GapTask> gaps = gapFinderService.listGaps(projectId, versionId, gapType, status, severity, limit);
        List<GapTaskView> views = gaps.stream().map(this::toView).collect(Collectors.toList());
        return Result.success(views);
    }

    /**
     * 将指定 GapTask 标记为已解决。
     */
    @PostMapping("/gaps/{id}/resolve")
    @Operation(summary = "解决知识缺口", description = "将指定 GapTask 标记为 RESOLVED")
    public Result<Void> resolveGap(
            @Parameter(description = "项目ID", required = true)
            @PathVariable String projectId,
            @Parameter(description = "GapTask ID", required = true)
            @PathVariable String id) {

        boolean resolved = gapFinderService.resolveGap(projectId, id);
        if (!resolved) {
            return Result.error("Gap task not found");
        }
        return Result.success();
    }

    // ──────────── Private helpers ────────────

    private GapTaskView toView(GapTask gap) {
        return GapTaskView.builder()
                .id(gap.getId())
                .gapType(gap.getGapType())
                .gapKey(gap.getGapKey())
                .title(gap.getTitle())
                .description(gap.getDescription())
                .severity(gap.getSeverity())
                .status(gap.getStatus())
                .subjectType(gap.getSubjectType())
                .subjectKey(gap.getSubjectKey())
                .relatedClaimIds(parseJsonList(gap.getRelatedClaimIds()))
                .relatedNodeIds(parseJsonList(gap.getRelatedNodeIds()))
                .evidenceIds(parseJsonList(gap.getEvidenceIds()))
                .suggestedAction(gap.getSuggestedAction())
                .priorityScore(gap.getPriorityScore())
                .createdAt(gap.getCreatedAt())
                .updatedAt(gap.getUpdatedAt())
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonList(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty() || "[]".equals(jsonStr)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(jsonStr, List.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse JSON list: {}", jsonStr, e);
            return new ArrayList<>();
        }
    }
}
