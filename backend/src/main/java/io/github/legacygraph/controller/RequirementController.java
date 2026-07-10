package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.dto.RequirementItemDTO;
import io.github.legacygraph.entity.AcceptanceCriterion;
import io.github.legacygraph.entity.Requirement;
import io.github.legacygraph.entity.RequirementItem;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.AcceptanceCriterionRepository;
import io.github.legacygraph.repository.RequirementItemRepository;
import io.github.legacygraph.repository.RequirementRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import io.github.legacygraph.service.requirement.RequirementExtractionService;
import io.github.legacygraph.service.requirement.RequirementGraphBuilder;
import io.github.legacygraph.util.IdUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 需求结构化 Controller（Task 6）。
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}")
@Tag(name = "需求结构化", description = "需求抽取与图谱构建")
public class RequirementController {

    private final RequirementExtractionService extractionService;
    private final RequirementGraphBuilder graphBuilder;
    private final RequirementRepository requirementRepository;
    private final RequirementItemRepository itemRepository;
    private final AcceptanceCriterionRepository criterionRepository;
    private final ScanVersionRepository scanVersionRepository;

    public RequirementController(RequirementExtractionService extractionService,
                                 RequirementGraphBuilder graphBuilder,
                                 RequirementRepository requirementRepository,
                                 RequirementItemRepository itemRepository,
                                 AcceptanceCriterionRepository criterionRepository,
                                 ScanVersionRepository scanVersionRepository) {
        this.extractionService = extractionService;
        this.graphBuilder = graphBuilder;
        this.requirementRepository = requirementRepository;
        this.itemRepository = itemRepository;
        this.criterionRepository = criterionRepository;
        this.scanVersionRepository = scanVersionRepository;
    }

    @Log(value = "分析需求并构建图谱", type = Log.OperationType.CREATE)
    @PostMapping("/requirements/analyze")
    @Operation(summary = "分析需求文本并构建图谱",
            description = "调用LLM结构化抽取需求，返回分析结果并写入图谱（不落库）")
    public Result<RequirementResponse> analyze(
            @PathVariable String projectId,
            @RequestBody RequirementRequest request) {
        RequirementAnalysis analysis = extractionService.extract(projectId, request.getText());

        String requirementId = IdUtil.fastUUID();
        String versionId = resolveLatestVersionId(projectId);
        RequirementGraphBuilder.BuildResult buildResult =
                graphBuilder.build(projectId, versionId, requirementId, analysis);

        log.info("Requirement analyzed: projectId={}, nodes={}, items={}",
                projectId, buildResult.nodeIds().size(), buildResult.itemCount());
        return Result.success(new RequirementResponse(
                requirementId, analysis, buildResult.nodeIds(), buildResult.itemCount()));
    }

    @Log(value = "保存需求并构建图谱", type = Log.OperationType.CREATE)
    @PostMapping("/requirements")
    @Operation(summary = "保存需求并构建图谱", description = "保存需求到数据库并构建需求图谱")
    public Result<RequirementResponse> save(
            @PathVariable String projectId,
            @RequestBody RequirementRequest request) {
        RequirementAnalysis analysis = extractionService.extract(projectId, request.getText());
        String versionId = resolveLatestVersionId(projectId);

        // 1. 保存需求主表
        Requirement req = new Requirement();
        req.setProjectId(projectId);
        req.setText(request.getText());
        req.setGoal(analysis.getGoal());
        req.setStatus("ANALYZED");
        req.setCreatedAt(LocalDateTime.now());
        requirementRepository.insert(req);

        // 2. 保存需求条目 + 验收条件
        if (analysis.getItems() != null) {
            for (RequirementItemDTO dto : analysis.getItems()) {
                RequirementItem item = new RequirementItem();
                item.setRequirementId(req.getId());
                item.setCode(dto.getCode());
                item.setText(dto.getText());
                item.setCreatedAt(LocalDateTime.now());
                itemRepository.insert(item);

                if (dto.getAcceptanceCriteria() != null) {
                    for (String ac : dto.getAcceptanceCriteria()) {
                        if (ac == null || ac.isBlank()) {
                            continue;
                        }
                        AcceptanceCriterion criterion = new AcceptanceCriterion();
                        criterion.setRequirementItemId(item.getId());
                        criterion.setText(ac);
                        criterion.setCreatedAt(LocalDateTime.now());
                        criterionRepository.insert(criterion);
                    }
                }
            }
        }

        // 3. 构建图谱
        RequirementGraphBuilder.BuildResult buildResult =
                graphBuilder.build(projectId, versionId, req.getId(), analysis);

        log.info("Requirement saved: projectId={}, requirementId={}, nodes={}, items={}",
                projectId, req.getId(), buildResult.nodeIds().size(), buildResult.itemCount());
        return Result.success(new RequirementResponse(
                req.getId(), analysis, buildResult.nodeIds(), buildResult.itemCount()));
    }

    /**
     * 解析项目最新的扫描版本 ID（按创建时间倒序取第一条）。
     */
    private String resolveLatestVersionId(String projectId) {
        LambdaQueryWrapper<ScanVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ScanVersion::getProjectId, projectId)
                .orderByDesc(ScanVersion::getStartedAt)
                .last("LIMIT 1");
        ScanVersion version = scanVersionRepository.selectOne(wrapper);
        return version != null ? version.getId() : null;
    }

    // ==================== 请求 / 响应 DTO ====================

    @Data
    public static class RequirementRequest {
        private String text;
    }

    @Data
    @AllArgsConstructor
    public static class RequirementResponse {
        /** 需求 ID（保存后为 DB 主键，分析时为临时标识） */
        private String requirementId;
        /** LLM 抽取的分析结果 */
        private RequirementAnalysis analysis;
        /** 创建的图谱节点 ID 列表 */
        private List<String> createdNodeIds;
        /** 需求条目数 */
        private int itemCount;
    }
}
