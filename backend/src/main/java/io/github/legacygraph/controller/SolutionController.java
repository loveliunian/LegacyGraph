package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.annotation.Log;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.dto.RequirementItemDTO;
import io.github.legacygraph.dto.requirement.ImpactResult;
import io.github.legacygraph.dto.requirement.LinkedTarget;
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
import io.github.legacygraph.service.solution.SolutionToChangeTaskBridge;
import io.github.legacygraph.service.solution.SolutionVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
    private final RequirementLinkingService linkingService;
    private final ImpactSubgraphService impactService;
    private final SolutionRepository solutionRepository;
    private final SolutionStepRepository stepRepository;
    private final RequirementRepository requirementRepository;
    private final RequirementItemRepository itemRepository;
    private final AcceptanceCriterionRepository criterionRepository;
    private final ScanVersionRepository scanVersionRepository;
    private final SolutionToChangeTaskBridge solutionToChangeTaskBridge;
    private final ObjectMapper objectMapper;

    public SolutionController(SolutionPlanner planner,
                              SolutionVerifier verifier,
                              RequirementLinkingService linkingService,
                              ImpactSubgraphService impactService,
                              SolutionRepository solutionRepository,
                              SolutionStepRepository stepRepository,
                              RequirementRepository requirementRepository,
                              RequirementItemRepository itemRepository,
                              AcceptanceCriterionRepository criterionRepository,
                              ScanVersionRepository scanVersionRepository,
                              SolutionToChangeTaskBridge solutionToChangeTaskBridge,
                              ObjectMapper objectMapper) {
        this.planner = planner;
        this.verifier = verifier;
        this.linkingService = linkingService;
        this.impactService = impactService;
        this.solutionRepository = solutionRepository;
        this.stepRepository = stepRepository;
        this.requirementRepository = requirementRepository;
        this.itemRepository = itemRepository;
        this.criterionRepository = criterionRepository;
        this.scanVersionRepository = scanVersionRepository;
        this.solutionToChangeTaskBridge = solutionToChangeTaskBridge;
        this.objectMapper = objectMapper;
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
        // 2. 从链接目标出发提取影响子图
        ImpactResult impactResult = impactService.extract(projectId, versionId, targets);
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
        solution.setVerificationErrors(writeJsonSafe(result.getErrors()));
        solution.setUpdatedAt(LocalDateTime.now());
        solutionRepository.updateById(solution);

        log.info("Solution verified: solutionId={}, passed={}, status={}",
                solutionId, result.isPassed(), newStatus);
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

    // ==================== 请求 / 响应 DTO ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenerateRequest {
        private String requirementId;
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
