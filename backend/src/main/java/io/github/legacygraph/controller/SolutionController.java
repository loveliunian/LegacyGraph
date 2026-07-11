package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.dto.RequirementItemDTO;
import io.github.legacygraph.dto.requirement.ImpactResult;
import io.github.legacygraph.dto.requirement.LinkedTarget;
import io.github.legacygraph.dto.solution.ApproveRequest;
import io.github.legacygraph.dto.solution.RepairSuggestion;
import io.github.legacygraph.dto.solution.SolutionPlan;
import io.github.legacygraph.dto.solution.SolutionPlanStep;
import io.github.legacygraph.dto.solution.SolutionVerificationResult;
import io.github.legacygraph.entity.AcceptanceCriterion;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.entity.Requirement;
import io.github.legacygraph.entity.RequirementItem;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.entity.Solution;
import io.github.legacygraph.entity.SolutionStep;
import io.github.legacygraph.repository.AcceptanceCriterionRepository;
import io.github.legacygraph.repository.RequirementItemRepository;
import io.github.legacygraph.repository.RequirementRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import io.github.legacygraph.repository.SolutionRepository;
import io.github.legacygraph.repository.SolutionStepRepository;
import io.github.legacygraph.service.requirement.ImpactSubgraphService;
import io.github.legacygraph.service.requirement.RequirementLinkingService;
import io.github.legacygraph.service.solution.SolutionPlanner;
import io.github.legacygraph.service.solution.SolutionRepairAdvisor;
import io.github.legacygraph.service.solution.SolutionToChangeTaskBridge;
import io.github.legacygraph.service.solution.SolutionVerifier;
import io.github.legacygraph.service.solution.SolutionReviewService;
import io.github.legacygraph.service.user.UserStoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 方案生成与校验 Controller（Task 10）。
 * <p>提供基于需求生成方案、查询方案详情、确定性校验方案 3 个接口。</p>
 */
@Slf4j
@RestController
@Tag(name = "方案生成", description = "基于需求与影响子图生成可落地实施方案")
public class SolutionController {

    /** 方案状态 */
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_READY_FOR_REVIEW = "READY_FOR_REVIEW";
    private static final String STATUS_NEEDS_INPUT = "NEEDS_INPUT";

    private final SolutionPlanner planner;
    private final SolutionVerifier verifier;
    private final SolutionRepairAdvisor repairAdvisor;
    private final RequirementLinkingService linkingService;
    private final ImpactSubgraphService impactService;
    private final SolutionRepository solutionRepository;
    private final SolutionStepRepository stepRepository;
    private final RequirementRepository requirementRepository;
    private final RequirementItemRepository itemRepository;
    private final AcceptanceCriterionRepository criterionRepository;
    private final ScanVersionRepository scanVersionRepository;
    private final SolutionToChangeTaskBridge solutionToChangeTaskBridge;
    private final SolutionReviewService solutionReviewService;
    private final ObjectMapper objectMapper;
    private final UserStoreService userStoreService;

    public SolutionController(SolutionPlanner planner,
                              SolutionVerifier verifier,
                              SolutionRepairAdvisor repairAdvisor,
                              RequirementLinkingService linkingService,
                              ImpactSubgraphService impactService,
                              SolutionRepository solutionRepository,
                              SolutionStepRepository stepRepository,
                              RequirementRepository requirementRepository,
                              RequirementItemRepository itemRepository,
                              AcceptanceCriterionRepository criterionRepository,
                              ScanVersionRepository scanVersionRepository,
                              SolutionToChangeTaskBridge solutionToChangeTaskBridge,
                              SolutionReviewService solutionReviewService,
                              ObjectMapper objectMapper,
                              UserStoreService userStoreService) {
        this.planner = planner;
        this.verifier = verifier;
        this.repairAdvisor = repairAdvisor;
        this.linkingService = linkingService;
        this.impactService = impactService;
        this.solutionRepository = solutionRepository;
        this.stepRepository = stepRepository;
        this.requirementRepository = requirementRepository;
        this.itemRepository = itemRepository;
        this.criterionRepository = criterionRepository;
        this.scanVersionRepository = scanVersionRepository;
        this.solutionToChangeTaskBridge = solutionToChangeTaskBridge;
        this.solutionReviewService = solutionReviewService;
        this.objectMapper = objectMapper;
        this.userStoreService = userStoreService;
    }

    @Log(value = "生成方案", type = Log.OperationType.CREATE)
    @PostMapping("/lg/projects/{projectId}/solutions/generate")
    @Operation(summary = "基于 requirementId 生成方案",
            description = "加载需求 → 重建分析 → 链接图谱 + 提取影响子图 → LLM 生成方案 → 落库（status=DRAFT）")
    public Result<SolutionDetailResponse> generate(
            @PathVariable String projectId,
            @RequestBody GenerateRequest request) {

        if (request == null || request.getRequirementId() == null || request.getRequirementId().isBlank()) {
            throw new IllegalArgumentException("requirementId 不能为空");
        }
        String requirementId = request.getRequirementId();

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
        ImpactResult impactResult = impactService.extract(projectId, versionId, requirementId, targets);
        // 3. 调用 LLM 生成方案计划
        SolutionPlan plan = planner.plan(projectId, versionId, analysis, impactResult);

        // 4. 持久化 Solution 主表（status=DRAFT）
        LocalDateTime now = LocalDateTime.now();
        Solution solution = new Solution();
        solution.setProjectId(projectId);
        solution.setRequirementId(requirementId);
        solution.setStatus(STATUS_DRAFT);
        solution.setSummary(plan.getSummary());
        solution.setAnalysisJson(writeJsonSafe(analysis));
        solution.setImpactResultJson(writeJsonSafe(impactResult));
        solution.setCreatedAt(now);
        solution.setUpdatedAt(now);
        solutionRepository.insert(solution);

        // 5. 持久化 SolutionStep 列表
        List<SolutionStep> stepEntities = new ArrayList<>();
        List<SolutionPlanStep> planSteps = plan.getSteps();
        for (int i = 0; i < planSteps.size(); i++) {
            SolutionPlanStep ps = planSteps.get(i);
            SolutionStep step = new SolutionStep();
            step.setSolutionId(solution.getId());
            step.setStepIndex(i);
            step.setTitle(ps.getTitle());
            step.setDescription(ps.getDescription());
            step.setFilePath(ps.getFilePath());
            step.setSymbolName(ps.getSymbolName());
            step.setEvidenceIds(writeJsonSafe(ps.getEvidenceIds()));
            step.setActionType(ps.getActionType());
            step.setTestDescription(ps.getTestDescription());
            step.setRollbackDescription(ps.getRollbackDescription());
            step.setCreatedAt(now);
            stepRepository.insert(step);
            stepEntities.add(step);
        }

        log.info("Solution generated: projectId={}, requirementId={}, solutionId={}, steps={}",
                projectId, requirementId, solution.getId(), stepEntities.size());
        return Result.success(toDetailResponse(solution, stepEntities));
    }

    @Log(value = "查询方案详情", type = Log.OperationType.QUERY)
    @GetMapping("/lg/solutions/{solutionId}")
    @Operation(summary = "获取方案详情", description = "返回方案主表信息 + 步骤列表")
    public Result<SolutionDetailResponse> detail(@PathVariable String solutionId) {
        Solution solution = solutionRepository.selectById(solutionId);
        if (solution == null) {
            throw new IllegalArgumentException("方案不存在: " + solutionId);
        }
        LambdaQueryWrapper<SolutionStep> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SolutionStep::getSolutionId, solutionId)
                .orderByAsc(SolutionStep::getStepIndex);
        List<SolutionStep> steps = stepRepository.selectList(wrapper);
        return Result.success(toDetailResponse(solution, steps));
    }

    @Log(value = "查询项目方案列表", type = Log.OperationType.QUERY)
    @GetMapping("/lg/projects/{projectId}/solutions")
    @Operation(summary = "获取项目方案列表",
            description = "返回该项目下所有方案（含步骤），按创建时间倒序")
    public Result<List<SolutionDetailResponse>> listByProject(@PathVariable String projectId) {
        LambdaQueryWrapper<Solution> solWrapper = new LambdaQueryWrapper<>();
        solWrapper.eq(Solution::getProjectId, projectId)
                .orderByDesc(Solution::getCreatedAt);
        List<Solution> solutions = solutionRepository.selectList(solWrapper);

        List<SolutionDetailResponse> list = new ArrayList<>();
        for (Solution solution : solutions) {
            LambdaQueryWrapper<SolutionStep> stepWrapper = new LambdaQueryWrapper<>();
            stepWrapper.eq(SolutionStep::getSolutionId, solution.getId())
                    .orderByAsc(SolutionStep::getStepIndex);
            List<SolutionStep> steps = stepRepository.selectList(stepWrapper);
            list.add(toDetailResponse(solution, steps));
        }
        return Result.success(list);
    }

    @Log(value = "校验方案", type = Log.OperationType.UPDATE)
    @PostMapping("/lg/solutions/{solutionId}/verify")
    @Operation(summary = "验证方案",
            description = "对方案步骤做 6 类确定性校验；通过则 status=READY_FOR_REVIEW，否则 status=NEEDS_INPUT")
    public Result<SolutionVerificationResult> verify(@PathVariable String solutionId) {
        Solution solution = solutionRepository.selectById(solutionId);
        if (solution == null) {
            throw new IllegalArgumentException("方案不存在: " + solutionId);
        }

        LambdaQueryWrapper<SolutionStep> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SolutionStep::getSolutionId, solutionId)
                .orderByAsc(SolutionStep::getStepIndex);
        List<SolutionStep> steps = stepRepository.selectList(wrapper);

        // 重建 SolutionPlan + RequirementAnalysis + ImpactResult
        SolutionPlan plan = toPlan(steps);
        RequirementAnalysis analysis = readJsonSafe(solution.getAnalysisJson(), RequirementAnalysis.class);
        if (analysis == null) {
            // 兼容老数据：从数据库重建
            Requirement req = requirementRepository.selectById(solution.getRequirementId());
            analysis = req != null ? rebuildAnalysis(req) : new RequirementAnalysis();
        }
        ImpactResult impactResult = readJsonSafe(solution.getImpactResultJson(), ImpactResult.class);
        if (impactResult == null) {
            impactResult = new ImpactResult();
        }

        String versionId = resolveLatestVersionId(solution.getProjectId());
        SolutionVerificationResult result = verifier.verify(
                solution.getProjectId(), versionId, analysis, impactResult, plan);

        // 10.6：根据校验结果更新 Solution.status
        String newStatus = result.isPassed() ? STATUS_READY_FOR_REVIEW : STATUS_NEEDS_INPUT;
        solution.setStatus(newStatus);

        // G-23：校验失败时生成智能修复建议
        List<RepairSuggestion> fixSuggestions = new ArrayList<>();
        if (!result.isPassed() && result.getErrors() != null && !result.getErrors().isEmpty()) {
            // 将错误码与描述组合为文本，供 advisor 按关键词匹配
            List<String> errorTexts = new ArrayList<>();
            for (SolutionVerificationResult.VerificationError err : result.getErrors()) {
                errorTexts.add(err.getCode() + ": " + err.getMessage());
            }
            fixSuggestions = repairAdvisor.suggest(solution.getProjectId(), solution, errorTexts);
            if (fixSuggestions == null) {
                fixSuggestions = new ArrayList<>();
            }
            result.setFixSuggestions(fixSuggestions);
        }

        // 将 errors 与 fixSuggestions 一并写入 verification_errors JSON（fixSuggestions 作为子字段）
        Map<String, Object> verificationData = new HashMap<>();
        verificationData.put("errors", result.getErrors());
        verificationData.put("fixSuggestions", fixSuggestions);
        solution.setVerificationErrors(writeJsonSafe(verificationData));

        solution.setUpdatedAt(LocalDateTime.now());
        solutionRepository.updateById(solution);

        log.info("Solution verified: solutionId={}, passed={}, status={}, fixSuggestions={}",
                solutionId, result.isPassed(), newStatus, fixSuggestions.size());
        return Result.success(result);
    }

    @Log(value = "方案转变更任务", type = Log.OperationType.CREATE)
    @PostMapping("/lg/projects/{projectId}/solutions/{solutionId}/bridge")
    @Operation(summary = "将已批准方案转为变更任务",
            description = "校验方案状态为 APPROVED，推断任务类型并创建 ChangeTask，回写 solution.changeTaskId")
    public Result<ChangeTask> bridge(@PathVariable String projectId,
                                      @PathVariable String solutionId) {
        String versionId = resolveLatestVersionId(projectId);
        ChangeTask task = solutionToChangeTaskBridge.bridge(solutionId, projectId, versionId);
        log.info("Solution bridged: projectId={}, solutionId={}, changeTaskId={}",
                projectId, solutionId, task.getId());
        return Result.success(task);
    }

    @Log(value = "审批方案", type = Log.OperationType.UPDATE)
    @PostMapping("/lg/solutions/{solutionId}/approve")
    @Operation(summary = "审批方案",
            description = "支持 APPROVE / APPROVE_WITH_REVISION / REJECT 三种决定，仅 READY_FOR_REVIEW 状态可审批。"
                    + "当方案风险评估等级为 HIGH 时，必须由 LEAD/PM 角色审批，否则返回 403 HIGH_RISK_REQUIRES_LEAD。"
                    + "角色校验由 UserStoreService.hasRole(reviewer, \"LEAD\", \"PM\") 统一处理（G-11 §13.4 强校验）。")
    public Result<SolutionDetailResponse> approve(@PathVariable String solutionId,
                                                    @RequestBody ApproveRequest request) {
        try {
            // 高风险方案必须由 LEAD/PM 角色审批（G-11 §13.4 强校验）
            if (!userStoreService.hasRole(request.getReviewer(), "LEAD", "PM")) {
                Solution pending = solutionRepository.selectById(solutionId);
                if (pending != null && isHighRisk(pending.getRiskAssessmentJson())) {
                    return Result.code(403, "HIGH_RISK_REQUIRES_LEAD");
                }
            }
            Solution updated = solutionReviewService.approve(solutionId, request);
            LambdaQueryWrapper<SolutionStep> stepWrapper = new LambdaQueryWrapper<>();
            stepWrapper.eq(SolutionStep::getSolutionId, updated.getId())
                    .orderByAsc(SolutionStep::getStepIndex);
            List<SolutionStep> steps = stepRepository.selectList(stepWrapper);
            return Result.success(toDetailResponse(updated, steps));
        } catch (IllegalStateException e) {
            return Result.error(e.getMessage());
        }
    }

    @Log(value = "驳回方案", type = Log.OperationType.UPDATE)
    @PostMapping("/lg/solutions/{solutionId}/reject")
    @Operation(summary = "驳回方案", description = "必须填写驳回原因")
    public Result<SolutionDetailResponse> reject(@PathVariable String solutionId,
                                                  @RequestBody RejectRequest request) {
        try {
            Solution updated = solutionReviewService.reject(
                    solutionId, request.getReviewer(), request.getReason());
            LambdaQueryWrapper<SolutionStep> stepWrapper = new LambdaQueryWrapper<>();
            stepWrapper.eq(SolutionStep::getSolutionId, updated.getId())
                    .orderByAsc(SolutionStep::getStepIndex);
            List<SolutionStep> steps = stepRepository.selectList(stepWrapper);
            return Result.success(toDetailResponse(updated, steps));
        } catch (IllegalStateException e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/lg/solutions/{solutionId}/audits")
    @Operation(summary = "获取方案审批历史", description = "返回方案所有状态变更审计记录")
    public Result<List<io.github.legacygraph.entity.SolutionAudit>> listAudits(
            @PathVariable String solutionId) {
        return Result.success(solutionReviewService.listAudits(solutionId));
    }

    // ==================== 辅助方法 ====================

    /**
     * 从数据库重建需求分析结果（用于 generate 接口）。
     * <p>V67 起 constraints 与 openQuestions 均持久化，重建时恢复，
     * 避免方案绕过 openQuestions 人工确认。</p>
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

    /**
     * 将 SolutionStep 实体列表转换为 SolutionPlan DTO。
     */
    private SolutionPlan toPlan(List<SolutionStep> steps) {
        SolutionPlan plan = new SolutionPlan();
        List<SolutionPlanStep> planSteps = new ArrayList<>();
        for (SolutionStep s : steps) {
            SolutionPlanStep ps = new SolutionPlanStep();
            ps.setTitle(s.getTitle());
            ps.setDescription(s.getDescription());
            ps.setFilePath(s.getFilePath());
            ps.setSymbolName(s.getSymbolName());
            ps.setEvidenceIds(readStringList(s.getEvidenceIds()));
            ps.setActionType(s.getActionType());
            ps.setTestDescription(s.getTestDescription());
            ps.setRollbackDescription(s.getRollbackDescription());
            planSteps.add(ps);
        }
        plan.setSteps(planSteps);
        return plan;
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
            return new ArrayList<>();
        }
    }

    private SolutionDetailResponse toDetailResponse(Solution solution, List<SolutionStep> steps) {
        SolutionDetailResponse resp = new SolutionDetailResponse();
        resp.setSolutionId(solution.getId());
        resp.setProjectId(solution.getProjectId());
        resp.setRequirementId(solution.getRequirementId());
        resp.setStatus(solution.getStatus());
        resp.setSummary(solution.getSummary());
        resp.setVerificationErrors(readStringList(solution.getVerificationErrors()));
        resp.setReviewer(solution.getReviewer());
        resp.setReviewComment(solution.getReviewComment());
        resp.setReviewedAt(solution.getReviewedAt());
        resp.setCreatedAt(solution.getCreatedAt());
        resp.setUpdatedAt(solution.getUpdatedAt());

        List<SolutionDetailResponse.StepDetail> stepDetails = new ArrayList<>();
        for (SolutionStep s : steps) {
            SolutionDetailResponse.StepDetail sd = new SolutionDetailResponse.StepDetail();
            sd.setId(s.getId());
            sd.setStepIndex(s.getStepIndex());
            sd.setTitle(s.getTitle());
            sd.setDescription(s.getDescription());
            sd.setFilePath(s.getFilePath());
            sd.setSymbolName(s.getSymbolName());
            sd.setEvidenceIds(readStringList(s.getEvidenceIds()));
            sd.setActionType(s.getActionType());
            sd.setTestDescription(s.getTestDescription());
            sd.setRollbackDescription(s.getRollbackDescription());
            stepDetails.add(sd);
        }
        resp.setSteps(stepDetails);
        return resp;
    }

    private String writeJsonSafe(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize {}: {}", value.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private <T> T readJsonSafe(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("Failed to deserialize {}: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    // ==================== 风险等级辅助（G-11） ====================
    // reviewer 角色判定已迁移至 UserStoreService.hasRole(reviewer, "LEAD", "PM")
    // （详见 service.user.UserStoreService 与 §13.4 强校验）

    /**
     * 解析 Solution.riskAssessmentJson，判定是否为 HIGH 风险。
     * <p>JSON 结构容错：忽略外层嵌套（riskAssessmentJson 可能是 RiskAssessment 整体或
     * 仅含 riskLevel 字段的子对象），任一字段值等于 "HIGH"（不区分大小写）即视为高风险。</p>
     *
     * @param riskAssessmentJson 风险评估 JSON 字符串
     * @return 是否为 HIGH 风险
     */
    @SuppressWarnings("unchecked")
    private boolean isHighRisk(String riskAssessmentJson) {
        if (riskAssessmentJson == null || riskAssessmentJson.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(riskAssessmentJson, Map.class);
            Object level = findRiskLevel(root);
            return level != null && "HIGH".equalsIgnoreCase(level.toString());
        } catch (Exception e) {
            log.warn("Failed to parse riskAssessmentJson: {}", e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Object findRiskLevel(Map<String, Object> node) {
        if (node == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key != null && ("riskLevel".equalsIgnoreCase(key) || "level".equalsIgnoreCase(key))) {
                return value;
            }
            if (value instanceof Map) {
                Object nested = findRiskLevel((Map<String, Object>) value);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    // ==================== 请求 / 响应 DTO ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenerateRequest {
        private String requirementId;
    }

    @Data
    @NoArgsConstructor
    public static class RejectRequest {
        private String reviewer;
        private String reason;
    }

    @Data
    @NoArgsConstructor
    public static class SolutionDetailResponse {
        private String solutionId;
        private String projectId;
        private String requirementId;
        private String status;
        private String summary;
        private List<String> verificationErrors;
        private String reviewer;
        private String reviewComment;
        private LocalDateTime reviewedAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private List<StepDetail> steps;

        @Data
        @NoArgsConstructor
        public static class StepDetail {
            private String id;
            private Integer stepIndex;
            private String title;
            private String description;
            private String filePath;
            private String symbolName;
            private List<String> evidenceIds;
            private String actionType;
            private String testDescription;
            private String rollbackDescription;
        }
    }
}
