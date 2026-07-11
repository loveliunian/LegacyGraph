package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.dto.RequirementItemDTO;
import io.github.legacygraph.dto.requirement.DataLineageResponse;
import io.github.legacygraph.dto.requirement.ImpactResult;
import io.github.legacygraph.dto.requirement.ItemPatchRequest;
import io.github.legacygraph.dto.requirement.LinkedTarget;
import io.github.legacygraph.entity.AcceptanceCriterion;
import io.github.legacygraph.entity.Requirement;
import io.github.legacygraph.entity.RequirementItem;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.AcceptanceCriterionRepository;
import io.github.legacygraph.repository.RequirementItemRepository;
import io.github.legacygraph.repository.RequirementRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import io.github.legacygraph.service.requirement.AcceptanceVerificationService;
import io.github.legacygraph.service.requirement.ImpactSubgraphService;
import io.github.legacygraph.service.requirement.RequirementExtractionService;
import io.github.legacygraph.service.requirement.RequirementGraphBuilder;
import io.github.legacygraph.service.requirement.RequirementDataLineageService;
import io.github.legacygraph.service.requirement.RequirementLinkingService;
import io.github.legacygraph.service.requirement.RequirementPatchService;
import io.github.legacygraph.util.IdUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final RequirementLinkingService linkingService;
    private final ImpactSubgraphService impactService;
    private final AcceptanceVerificationService acceptanceVerificationService;
    private final RequirementPatchService patchService;
    private final RequirementRepository requirementRepository;
    private final RequirementItemRepository itemRepository;
    private final AcceptanceCriterionRepository criterionRepository;
    private final ScanVersionRepository scanVersionRepository;
    private final ObjectMapper objectMapper;
    private final RequirementDataLineageService dataLineageService;

    public RequirementController(RequirementExtractionService extractionService,
                                 RequirementGraphBuilder graphBuilder,
                                 RequirementLinkingService linkingService,
                                 ImpactSubgraphService impactService,
                                 AcceptanceVerificationService acceptanceVerificationService,
                                 RequirementPatchService patchService,
                                 RequirementRepository requirementRepository,
                                 RequirementItemRepository itemRepository,
                                 AcceptanceCriterionRepository criterionRepository,
                                 ScanVersionRepository scanVersionRepository,
                                 ObjectMapper objectMapper,
                                 RequirementDataLineageService dataLineageService) {
        this.extractionService = extractionService;
        this.graphBuilder = graphBuilder;
        this.linkingService = linkingService;
        this.impactService = impactService;
        this.acceptanceVerificationService = acceptanceVerificationService;
        this.patchService = patchService;
        this.requirementRepository = requirementRepository;
        this.itemRepository = itemRepository;
        this.criterionRepository = criterionRepository;
        this.scanVersionRepository = scanVersionRepository;
        this.objectMapper = objectMapper;
        this.dataLineageService = dataLineageService;
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
        req.setOpenQuestionsJson(writeJsonSafe(analysis.getOpenQuestions()));
        req.setCreatedAt(LocalDateTime.now());
        requirementRepository.insert(req);

        // 2. 保存需求条目 + 验收条件 + 约束
        if (analysis.getItems() != null) {
            for (RequirementItemDTO dto : analysis.getItems()) {
                RequirementItem item = new RequirementItem();
                item.setRequirementId(req.getId());
                item.setCode(dto.getCode());
                item.setText(dto.getText());
                item.setConstraintsJson(writeJsonSafe(dto.getConstraints()));
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

    @Log(value = "需求影响分析", type = Log.OperationType.QUERY)
    @PostMapping("/requirements/{requirementId}/impact")
    @Operation(summary = "需求影响分析",
            description = "将需求条目链接到图谱节点（三步策略），并提取影响子图")
    public Result<ImpactResult> impact(
            @PathVariable String projectId,
            @PathVariable String requirementId) {
        Requirement req = requirementRepository.selectById(requirementId);
        if (req == null) {
            throw new IllegalArgumentException("需求不存在: " + requirementId);
        }
        RequirementAnalysis analysis = rebuildAnalysis(req);
        String versionId = resolveLatestVersionId(projectId);

        // 1. 链接需求条目到图谱节点并创建 AFFECTS 边
        List<LinkedTarget> targets = linkingService.link(
                projectId, versionId, requirementId, analysis);
        // 2. 从链接目标出发提取影响子图（G-19：同时回写影响标记到 Neo4j 节点）
        ImpactResult result = impactService.extract(projectId, versionId, requirementId, targets);

        log.info("Requirement impact analyzed: projectId={}, requirementId={}, targets={}, impactedNodes={}",
                projectId, requirementId, targets.size(), result.getImpactedNodes().size());
        return Result.success(result);
    }

    @Log(value = "补充需求开放问题", type = Log.OperationType.UPDATE)
    @PostMapping("/requirements/{requirementId}/clarify")
    @Operation(summary = "回答需求的开放问题并重新分析",
            description = "将用户回答合并到原需求文本，重新调用LLM抽取，更新需求条目")
    public Result<RequirementResponse> clarify(
            @PathVariable String projectId,
            @PathVariable String requirementId,
            @RequestBody ClarifyRequest request) {
        // 1. 加载原需求
        Requirement req = requirementRepository.selectById(requirementId);
        if (req == null) {
            throw new IllegalArgumentException("需求不存在: " + requirementId);
        }
        // 2. 将用户回答合并到需求文本
        StringBuilder combinedText = new StringBuilder(req.getText());
        if (request.getAnswers() != null && !request.getAnswers().isEmpty()) {
            combinedText.append("\n\n## 补充信息\n");
            for (Map.Entry<String, String> entry : request.getAnswers().entrySet()) {
                combinedText.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
        // 3. 重新调用 LLM 抽取
        RequirementAnalysis analysis = extractionService.extract(projectId, combinedText.toString());
        // 4. 更新需求主表
        req.setText(combinedText.toString());
        req.setGoal(analysis.getGoal());
        req.setOpenQuestionsJson(writeJsonSafe(analysis.getOpenQuestions()));
        req.setUpdatedAt(LocalDateTime.now());
        requirementRepository.updateById(req);
        // 5. 删除旧条目（含验收条件），保存新条目
        LambdaQueryWrapper<RequirementItem> oldItemWrapper = new LambdaQueryWrapper<>();
        oldItemWrapper.eq(RequirementItem::getRequirementId, requirementId);
        List<RequirementItem> oldItems = itemRepository.selectList(oldItemWrapper);
        for (RequirementItem oldItem : oldItems) {
            LambdaQueryWrapper<AcceptanceCriterion> acWrapper = new LambdaQueryWrapper<>();
            acWrapper.eq(AcceptanceCriterion::getRequirementItemId, oldItem.getId());
            criterionRepository.delete(acWrapper);
        }
        itemRepository.delete(oldItemWrapper);
        if (analysis.getItems() != null) {
            for (RequirementItemDTO dto : analysis.getItems()) {
                RequirementItem item = new RequirementItem();
                item.setRequirementId(req.getId());
                item.setCode(dto.getCode());
                item.setText(dto.getText());
                item.setConstraintsJson(writeJsonSafe(dto.getConstraints()));
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
        // 6. 构建图谱
        String versionId = resolveLatestVersionId(projectId);
        RequirementGraphBuilder.BuildResult buildResult =
                graphBuilder.build(projectId, versionId, requirementId, analysis);

        log.info("Requirement clarified: projectId={}, requirementId={}, nodes={}, items={}",
                projectId, requirementId, buildResult.nodeIds().size(), buildResult.itemCount());
        return Result.success(new RequirementResponse(requirementId, analysis, buildResult.nodeIds(), buildResult.itemCount()));
    }

    // ==================== G-22: 需求条目增量更新 ====================

    @Log(value = "增量更新需求条目", type = Log.OperationType.UPDATE)
    @PatchMapping("/requirements/{requirementId}/items/{itemCode}")
    @Operation(summary = "增量更新单条需求条目",
            description = "部分更新单条 RequirementItem（text / constraints / acceptanceCriteria），仅更新非 null 字段")
    public Result<RequirementItem> patchItem(
            @PathVariable String projectId,
            @PathVariable String requirementId,
            @PathVariable String itemCode,
            @RequestBody ItemPatchRequest patch) {
        RequirementItem item = patchService.patchItem(requirementId, itemCode, patch);
        return Result.success(item);
    }

    @Log(value = "增量clarify需求条目", type = Log.OperationType.UPDATE)
    @PostMapping("/requirements/{requirementId}/items/{itemCode}/clarify")
    @Operation(summary = "增量clarify单条需求条目",
            description = "对单条 RequirementItem 增量 clarify，将问答追加到条目文本（LLM 调用后续接入）")
    public Result<RequirementItem> clarifyItem(
            @PathVariable String projectId,
            @PathVariable String requirementId,
            @PathVariable String itemCode,
            @RequestBody List<RequirementPatchService.QA> answers) {
        RequirementItem item = patchService.clarifyItem(requirementId, itemCode, answers);
        return Result.success(item);
    }

    // ==================== G-14: 验收条件闭环验证 ====================

    @Log(value = "触发需求闭环验证", type = Log.OperationType.UPDATE)
    @PostMapping("/requirements/{requirementId}/verify")
    @Operation(summary = "触发需求闭环验证",
            description = "按 verificationType 自动验证需求下所有验收条件，"
                    + "AUTOMATIC/NONE 自动置 VERIFIED，MANUAL 保持 PENDING 等待人工勾选")
    public Result<List<AcceptanceCriterion>> verifyRequirement(
            @PathVariable String projectId,
            @PathVariable String requirementId,
            @RequestBody(required = false) VerifyRequest request) {
        String verifier = request != null && request.getVerifier() != null
                ? request.getVerifier() : "system";
        List<AcceptanceCriterion> criteria =
                acceptanceVerificationService.verifyByRequirement(requirementId, verifier);
        return Result.success(criteria);
    }

    @Log(value = "验证单条验收条件", type = Log.OperationType.UPDATE)
    @PostMapping("/acceptance-criteria/{criterionId}/verify")
    @Operation(summary = "验证单条验收条件（人工勾选）",
            description = "将单条验收条件标记为 VERIFIED，可附带验证备注与证据链接")
    public Result<AcceptanceCriterion> verifySingle(
            @PathVariable String projectId,
            @PathVariable String criterionId,
            @RequestBody(required = false) VerifySingleRequest request) {
        String verifier = request != null && request.getVerifier() != null
                ? request.getVerifier() : "anonymous";
        String note = request != null ? request.getNote() : null;
        String evidenceUrl = request != null ? request.getEvidenceUrl() : null;
        AcceptanceCriterion ac =
                acceptanceVerificationService.verifySingle(criterionId, verifier, note, evidenceUrl);
        return Result.success(ac);
    }

    @Log(value = "豁免验收条件", type = Log.OperationType.UPDATE)
    @PostMapping("/acceptance-criteria/{criterionId}/waive")
    @Operation(summary = "豁免一条验收条件",
            description = "将验收条件标记为 WAIVED，需提供豁免原因")
    public Result<AcceptanceCriterion> waive(
            @PathVariable String projectId,
            @PathVariable String criterionId,
            @RequestBody WaiveRequest request) {
        AcceptanceCriterion ac =
                acceptanceVerificationService.waive(criterionId, request.getVerifier(), request.getReason());
        return Result.success(ac);
    }

    @Log(value = "标记验收失败", type = Log.OperationType.UPDATE)
    @PostMapping("/acceptance-criteria/{criterionId}/fail")
    @Operation(summary = "标记一条验收条件为 FAILED",
            description = "将验收条件标记为 FAILED，需提供失败原因。"
                    + "FAILED 不视为闭环，所属需求不会被推进到 DONE；"
                    + "后续可通过 /verify 端点重新验证切换回 VERIFIED。")
    public Result<AcceptanceCriterion> fail(
            @PathVariable String projectId,
            @PathVariable String criterionId,
            @RequestBody WaiveRequest request) {
        AcceptanceCriterion ac =
                acceptanceVerificationService.fail(criterionId, request.getVerifier(), request.getReason());
        return Result.success(ac);
    }

    @Log(value = "获取需求闭环报告", type = Log.OperationType.QUERY)
    @GetMapping("/requirements/{requirementId}/closure-report")
    @Operation(summary = "获取需求闭环报告",
            description = "返回需求下所有条目与验收条件的验证状态明细及闭环统计")
    public Result<Map<String, Object>> closureReport(
            @PathVariable String projectId,
            @PathVariable String requirementId) {
        return Result.success(acceptanceVerificationService.closureReport(requirementId));
    }

    // ==================== G-16: 需求-表/字段反向溯源 ====================

    @Log(value = "需求-表/字段反向溯源", type = Log.OperationType.QUERY)
    @GetMapping("/requirements/{requirementId}/data-lineage")
    @Operation(summary = "获取需求的数据血缘",
            description = "从需求出发沿 AFFECTS 边找到目标节点，再反向追溯 CALLS/READS/WRITES/MAPS_TO/HAS_COLUMN "
                    + "等边到达 Table/Column 节点，聚合为受影响表清单与汇总摘要")
    public Result<DataLineageResponse> dataLineage(
            @PathVariable String projectId,
            @PathVariable String requirementId) {
        Requirement req = requirementRepository.selectById(requirementId);
        if (req == null) {
            throw new IllegalArgumentException("需求不存在: " + requirementId);
        }
        String versionId = resolveLatestVersionId(projectId);
        DataLineageResponse result = dataLineageService.traceDataLineage(projectId, versionId, requirementId);
        log.info("Requirement data-lineage traced: projectId={}, requirementId={}, tables={}",
                projectId, requirementId, result.getSummary().getTableCount());
        return Result.success(result);
    }

    // ==================== G-17: 契约生成已迁出至 ContractController ====================
    // 原 /requirements/{requirementId}/contract 端点已拆出到 ContractController
    // （新路径 /contracts/requirements/{requirementId}）。
    // 旧路径由 ContractLegacyRedirectController 兼容，标记为 @Deprecated。

    /**
     * 从数据库重建需求分析结果（用于影响分析接口）。
     * <p>V67 起 constraints 与 openQuestions 均持久化，重建时恢复。</p>
     */
    private RequirementAnalysis rebuildAnalysis(Requirement req) {
        RequirementAnalysis analysis = new RequirementAnalysis();
        analysis.setGoal(req.getGoal());
        analysis.setOpenQuestions(readStringList(req.getOpenQuestionsJson()));
        LambdaQueryWrapper<RequirementItem> itemWrapper = new LambdaQueryWrapper<>();
        itemWrapper.eq(RequirementItem::getRequirementId, req.getId());
        List<RequirementItem> items = itemRepository.selectList(itemWrapper);
        for (RequirementItem item : items) {
            RequirementItemDTO dto = new RequirementItemDTO();
            dto.setCode(item.getCode());
            dto.setText(item.getText());
            dto.setConstraints(readStringList(item.getConstraintsJson()));
            LambdaQueryWrapper<AcceptanceCriterion> acWrapper = new LambdaQueryWrapper<>();
            acWrapper.eq(AcceptanceCriterion::getRequirementItemId, item.getId());
            List<AcceptanceCriterion> acs = criterionRepository.selectList(acWrapper);
            dto.setAcceptanceCriteria(acs.stream().map(AcceptanceCriterion::getText).toList());
            analysis.getItems().add(dto);
        }
        return analysis;
    }

    /**
     * 将对象序列化为 JSON 字符串，失败返回 null。
     */
    private String writeJsonSafe(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize {}: {}", value.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * JSON 数组字符串 → List<String>，失败返回空列表。
     */
    @SuppressWarnings("unchecked")
    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> list = objectMapper.readValue(json, List.class);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            log.warn("Failed to deserialize string list: {}", e.getMessage());
            return new ArrayList<>();
        }
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
        if (version == null) {
            throw new IllegalStateException(
                    "项目 " + projectId + " 无可用扫描版本，请先完成至少一次扫描");
        }
        return version.getId();
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

    @Data
    public static class ClarifyRequest {
        /** key=问题, value=回答 */
        private Map<String, String> answers;
    }

    @Data
    public static class VerifyRequest {
        /** 验证人 */
        private String verifier;
    }

    @Data
    public static class VerifySingleRequest {
        /** 验证人 */
        private String verifier;
        /** 验证备注 */
        private String note;
        /** 证据链接 */
        private String evidenceUrl;
    }

    @Data
    public static class WaiveRequest {
        /** 操作人 */
        private String verifier;
        /** 豁免原因 */
        private String reason;
    }
}
