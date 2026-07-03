package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.ChangeImpactAgent;
import io.github.legacygraph.agent.adapter.MigrationAgentAdapter;
import io.github.legacygraph.agent.adapter.RefactorAgentAdapter;
import io.github.legacygraph.dto.ChangeImpactAnalysis;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.graph.PatchPlan;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.entity.PatchFile;
import io.github.legacygraph.entity.PrTask;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.entity.ValidationGate;
import io.github.legacygraph.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 变更任务编排服务（增强版2）— 见 doc §ChangeTask 落地模块、§任务状态机。
 * <p>
 * 编排任务状态机、锁定范围、调用 Agent、落库 Patch/ValidationGate。
 * 状态：OPEN → IMPACT_READY → PATCH_DRAFTED → VALIDATING
 *       → VALIDATION_PASSED/VALIDATION_FAILED → REVIEW_PENDING
 *       → PR_READY/PR_CREATED → MERGED/REJECTED/ROLLED_BACK
 * </p>
 *
 * <h3>Phase 0-2 长事务拆分</h3>
 * <p>仅短 TX 方法（createTask/registerGates）保留 @Transactional；
 *   涉及 LLM/Agent 调用、门禁执行、测试等待的 refreshImpact/generatePatch/runValidation
 *   不再在 @Transactional 内执行长 IO，改为：读→长IO→短 TX 写。</p>
 */
@Slf4j
@Service
public class ChangeTaskService {

    private final ChangeTaskRepository changeTaskRepository;
    private final PatchFileRepository patchFileRepository;
    private final ValidationGateRepository validationGateRepository;
    private final ReviewRecordRepository reviewRecordRepository;
    private final ImpactSubgraphService impactSubgraphService;
    private final ChangeImpactAgent changeImpactAgent;
    private final RefactorAgentAdapter refactorAgentAdapter;
    private final MigrationAgentAdapter migrationAgentAdapter;
    private final io.github.legacygraph.agent.PatchPlanAgent patchPlanAgent;
    private final PatchPlanValidator patchPlanValidator;
    private final ValidationGateRunner validationGateRunner;
    private final PrOrchestrator prOrchestrator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ChangeTaskService(ChangeTaskRepository changeTaskRepository,
                             PatchFileRepository patchFileRepository,
                             ValidationGateRepository validationGateRepository,
                             ReviewRecordRepository reviewRecordRepository,
                             ImpactSubgraphService impactSubgraphService,
                             ChangeImpactAgent changeImpactAgent,
                             RefactorAgentAdapter refactorAgentAdapter,
                             MigrationAgentAdapter migrationAgentAdapter,
                             io.github.legacygraph.agent.PatchPlanAgent patchPlanAgent,
                             PatchPlanValidator patchPlanValidator,
                             ValidationGateRunner validationGateRunner,
                             PrOrchestrator prOrchestrator,
                             ObjectMapper objectMapper,
                             TransactionTemplate transactionTemplate) {
        this.changeTaskRepository = changeTaskRepository;
        this.patchFileRepository = patchFileRepository;
        this.validationGateRepository = validationGateRepository;
        this.reviewRecordRepository = reviewRecordRepository;
        this.impactSubgraphService = impactSubgraphService;
        this.changeImpactAgent = changeImpactAgent;
        this.refactorAgentAdapter = refactorAgentAdapter;
        this.migrationAgentAdapter = migrationAgentAdapter;
        this.patchPlanAgent = patchPlanAgent;
        this.patchPlanValidator = patchPlanValidator;
        this.validationGateRunner = validationGateRunner;
        this.prOrchestrator = prOrchestrator;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /** 创建变更任务（状态 OPEN）—— 短 TX。 */
    @Transactional
    public ChangeTask createTask(String projectId, String versionId, String taskType,
                                 String title, String inputIssue) {
        ChangeTask task = new ChangeTask();
        task.setId(UUID.randomUUID().toString());
        task.setProjectId(projectId);
        task.setVersionId(versionId);
        task.setTaskType(taskType);
        task.setTitle(title);
        task.setInputIssue(inputIssue);
        task.setStatus("OPEN");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        changeTaskRepository.insert(task);
        log.info("ChangeTask created: id={}, type={}, title={}", task.getId(), taskType, title);
        return task;
    }

    /** 查询项目下的变更任务，按最近更新时间优先。 */
    public List<ChangeTask> listTasks(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return List.of();
        }
        LambdaQueryWrapper<ChangeTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChangeTask::getProjectId, projectId)
                .orderByDesc(ChangeTask::getUpdatedAt)
                .orderByDesc(ChangeTask::getCreatedAt);
        return changeTaskRepository.selectList(wrapper);
    }

    /**
     * 基于图谱刷新影响子图（OPEN → IMPACT_READY）。
     * targetNodeId 为变更目标节点。
     * <p>Phase 0-2：移除 @Transactional，LLM 调用不再持有数据库连接。</p>
     */
    public ImpactSubgraph refreshImpact(String taskId, String targetNodeId) {
        ChangeTask task = requireTask(taskId);
        ImpactSubgraph subgraph = impactSubgraphService.extractByNode(
                task.getProjectId(), task.getVersionId(), targetNodeId);

        // Phase 3-1: AgentEnvelope 合约调用（含证据目录）
        String riskLevel = null;
        try {
            ChangeImpactAgent.ChangeImpactInput input = ChangeImpactAgent.ChangeImpactInput.builder()
                    .changeTarget(subgraph.getTargetName())
                    .changeDescription(task.getTitle())
                    .dependencies(subgraph.getDependencySummary())
                    .build();
            AgentEnvelope<ChangeImpactAgent.ChangeImpactInput> env =
                    AgentEnvelope.<ChangeImpactAgent.ChangeImpactInput>builder()
                    .projectId(task.getProjectId())
                    .taskId(taskId)
                    .agentType("ChangeImpact")
                    .input(input)
                    .build();
            ChangeImpactAnalysis analysis = changeImpactAgent.analyze(env);
            if (analysis != null && analysis.getSeverity() != null) {
                riskLevel = normalizeRisk(analysis.getSeverity());
            }
        } catch (Exception e) {
            log.warn("ChangeImpactAgent analyze failed for task {}: {}", taskId, e.getMessage());
        }

        // 短 TX 回写状态
        String finalRiskLevel = riskLevel;
        transactionTemplate.executeWithoutResult(status ->
                updateTaskAfterImpact(taskId, subgraph, finalRiskLevel));
        return subgraph;
    }

    private void updateTaskAfterImpact(String taskId, ImpactSubgraph subgraph, String riskLevel) {
        ChangeTask task = requireTask(taskId);
        task.setImpactedSubgraph(toJson(subgraph));
        if (riskLevel != null) {
            task.setRiskLevel(riskLevel);
        }
        task.setStatus("IMPACT_READY");
        task.setUpdatedAt(LocalDateTime.now());
        changeTaskRepository.updateById(task);
    }

    /**
     * 生成补丁草案（IMPACT_READY → PATCH_DRAFTED，越界/缺证据 → REVIEW_PENDING）。
     * 复用 Refactor/Migration Adapter；补丁落库前过 PatchPlanValidator 三类校验。
     * <p>Phase 0-2：移除 @Transactional，Agent 调用不再持有数据库连接。</p>
     */
    public PatchPlan generatePatch(String taskId, PatchGenRequest req) {
        ChangeTask task = requireTask(taskId);
        ImpactSubgraph subgraph = fromJson(task.getImpactedSubgraph(), ImpactSubgraph.class);

        List<String> evidenceIds = req.getEvidenceIds() != null ? req.getEvidenceIds() : List.of();

        // Agent 调用（长 IO，无 TX）
        PatchPlan plan;
        switch (task.getTaskType()) {
            case "REFACTOR" -> plan = refactorAgentAdapter.toPatchPlan(
                    taskId, task.getProjectId(), req.getTarget(), req.getSmellType(),
                    req.getCode(), req.getFilePath(), evidenceIds);
            case "UPGRADE" -> plan = migrationAgentAdapter.toPatchPlan(
                    taskId, task.getProjectId(), req.getMigrationDirection(),
                    req.getFilePath(), req.getCode(), req.getCustomRules(), evidenceIds);
            case "BUGFIX" -> plan = patchPlanAgent.generate(
                    task.getProjectId(), taskId, task.getTitle(), task.getInputIssue(),
                    subgraph, req.getEvidenceSummary(), req.getFailingTests());
            default -> throw new IllegalArgumentException(
                    "任务类型 " + task.getTaskType() + " 暂无自动补丁生成器");
        }

        if (plan == null) {
            throw new IllegalStateException("补丁生成失败：Agent 返回空计划");
        }

        // 三类校验：范围/格式/证据（无 TX）
        PatchPlanValidator.ValidationResult vr = patchPlanValidator.validate(plan, subgraph);

        // 短 TX 持久化 patch files + 状态更新
        transactionTemplate.executeWithoutResult(status ->
                persistPatchResult(taskId, plan, vr, task.getProjectId(), task.getVersionId()));
        return plan;
    }

    private void persistPatchResult(String taskId, PatchPlan plan,
                                     PatchPlanValidator.ValidationResult vr,
                                     String projectId, String versionId) {
        ChangeTask task = requireTask(taskId);

        String patchStatus = vr.isNeedsReview() ? "REVIEW_PENDING" : "DRAFT";
        for (PatchPlan.Patch p : plan.getPatches()) {
            PatchFile pf = new PatchFile();
            pf.setId(UUID.randomUUID().toString());
            pf.setChangeTaskId(taskId);
            pf.setFilePath(p.getFilePath());
            pf.setChangeType(p.getChangeType());
            pf.setPatchText(p.getPatchText() != null ? p.getPatchText() : "");
            pf.setGeneratedBy(plan.getGeneratedBy());
            pf.setEvidenceIds(toJson(p.getEvidenceIds()));
            pf.setStatus(patchStatus);
            pf.setCreatedAt(LocalDateTime.now());
            patchFileRepository.insert(pf);
        }

        task.setProposal(toJson(plan));
        task.setRiskLevel(plan.getRiskLevel());
        if (vr.isNeedsReview() || plan.isManualReviewNeeded()) {
            task.setStatus("REVIEW_PENDING");
            createReviewRecord(task, vr);
        } else {
            task.setStatus("PATCH_DRAFTED");
        }
        task.setUpdatedAt(LocalDateTime.now());
        changeTaskRepository.updateById(task);
    }

    /**
     * 登记验证门禁（PATCH_DRAFTED → VALIDATING）—— 短 TX。
     * 门禁执行体（复用 TestExecutionScheduler）留待 ValidationGateRunner；此处先落记录。
     */
    @Transactional
    public List<ValidationGate> registerGates(String taskId, List<String> gateTypes) {
        ChangeTask task = requireTask(taskId);
        List<ValidationGate> gates = new ArrayList<>(gateTypes.size());
        for (String type : gateTypes) {
            ValidationGate gate = new ValidationGate();
            gate.setId(UUID.randomUUID().toString());
            gate.setChangeTaskId(taskId);
            gate.setGateType(type);
            gate.setResult("PENDING");
            gates.add(gate);
        }
        for (ValidationGate gate : gates) {
            validationGateRepository.insert(gate);
        }
        task.setStatus("VALIDATING");
        task.setUpdatedAt(LocalDateTime.now());
        changeTaskRepository.updateById(task);
        return validationGateRepository.lambdaQuery()
                .eq(ValidationGate::getChangeTaskId, taskId).list();
    }

    public ChangeTask getTask(String taskId) {
        return changeTaskRepository.selectById(taskId);
    }

    /**
     * 执行已登记的验证门禁（VALIDATING → VALIDATION_PASSED / VALIDATION_FAILED）。
     * <p>
     * 通过 {@link ValidationGateRunner} 逐条执行门禁：STATIC/MIGRATION 走命令，
     * UNIT/API/DB/E2E 复用 TestExecutionScheduler。任一失败 → VALIDATION_FAILED 并建 ReviewRecord。
     * </p>
     * <p>Phase 0-2：移除 @Transactional，门禁执行（含命令执行、测试等待）不再持有数据库连接。</p>
     *
     * @param taskId  任务ID
     * @param caseIds 测试类门禁要跑的用例ID（可空；空则测试门禁视为跳过通过）
     * @param workingDir 命令类门禁工作目录（可空）
     * @param environment 测试环境（dev/test/prod，可空默认 test）
     */
    public ChangeTask runValidation(String taskId, List<String> caseIds,
                                    String workingDir, String environment) {
        ChangeTask task = requireTask(taskId);
        ValidationGateRunner.GateContext ctx = ValidationGateRunner.GateContext.builder()
                .projectId(task.getProjectId())
                .versionId(task.getVersionId())
                .workingDir(workingDir)
                .environment(environment != null ? environment : "test")
                .caseIds(caseIds)
                .build();

        // 门禁执行（长 IO：命令执行、测试等待，无 TX）
        boolean allPassed = validationGateRunner.runAll(taskId, ctx);

        // 短 TX 更新任务状态
        return transactionTemplate.execute(status ->
                updateTaskAfterValidation(taskId, task.getProjectId(), task.getVersionId(),
                        task.getTitle(), allPassed));
    }

    private ChangeTask updateTaskAfterValidation(String taskId, String projectId,
                                                  String versionId, String title,
                                                  boolean allPassed) {
        ChangeTask task = requireTask(taskId);
        task.setStatus(allPassed ? "VALIDATION_PASSED" : "VALIDATION_FAILED");
        task.setUpdatedAt(LocalDateTime.now());
        changeTaskRepository.updateById(task);

        if (!allPassed) {
            ReviewRecord review = new ReviewRecord();
            review.setId(UUID.randomUUID().toString());
            review.setProjectId(projectId);
            review.setVersionId(versionId);
            review.setTargetType("ChangeTask");
            review.setTargetId(task.getId());
            review.setTargetName(title);
            review.setGraphType("change");
            review.setPriority("high");
            review.setStatus("PENDING");
            review.setComment("验证门禁未全部通过，阻断 PR，需人工审核。");
            review.setCreatedAt(LocalDateTime.now());
            reviewRecordRepository.insert(review);
        }
        log.info("ChangeTask {} validation finished: status={}", taskId, task.getStatus());
        return task;
    }

    /**
     * 创建 PR 草案（VALIDATION_PASSED → PR_READY）。
     * <p>
     * 委托 {@link PrOrchestrator}：门禁未过会抛 IllegalStateException 拒绝创建；
     * 成功则落 {@code lg_pr_task} 并把任务置 PR_READY。
     * </p>
     */
    @Transactional
    public PrTask createPr(String taskId) {
        ChangeTask task = requireTask(taskId);
        PrTask prTask = prOrchestrator.createPrDraft(task, LocalDateTime.now());
        task.setStatus("PR_READY");
        task.setUpdatedAt(LocalDateTime.now());
        changeTaskRepository.updateById(task);
        log.info("ChangeTask {} PR draft ready: branch={}", taskId, prTask.getBranchName());
        return prTask;
    }

    // ==================== 内部辅助 ====================

    private ChangeTask requireTask(String taskId) {
        ChangeTask task = changeTaskRepository.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("变更任务不存在: " + taskId);
        }
        return task;
    }

    private void createReviewRecord(ChangeTask task, PatchPlanValidator.ValidationResult vr) {
        ReviewRecord review = new ReviewRecord();
        review.setId(UUID.randomUUID().toString());
        review.setProjectId(task.getProjectId());
        review.setVersionId(task.getVersionId());
        review.setTargetType("ChangeTask");
        review.setTargetId(task.getId());
        review.setTargetName(task.getTitle());
        review.setGraphType("change");
        review.setPriority("high");
        review.setStatus("PENDING");
        StringBuilder comment = new StringBuilder("变更任务补丁需人工审核。");
        if (!vr.getOutOfScopeFiles().isEmpty()) {
            comment.append(" 越界文件: ").append(String.join(", ", vr.getOutOfScopeFiles()));
        }
        if (!vr.getMissingEvidenceFiles().isEmpty()) {
            comment.append(" 缺证据文件: ").append(String.join(", ", vr.getMissingEvidenceFiles()));
        }
        review.setComment(comment.toString());
        review.setCreatedAt(LocalDateTime.now());
        reviewRecordRepository.insert(review);
        log.info("Created review record for change task {}", task.getId());
    }

    private String normalizeRisk(String risk) {
        String r = risk != null ? risk.toUpperCase() : "MEDIUM";
        if (r.contains("HIGH") || r.contains("CRITICAL") || r.contains("高")) return "HIGH";
        if (r.contains("LOW") || r.contains("低")) return "LOW";
        return "MEDIUM";
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize {}: {}", obj.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize {}: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** 补丁生成请求参数 */
    @lombok.Data
    public static class PatchGenRequest {
        private String target;
        private String smellType;
        private String code;
        private String filePath;
        private String migrationDirection;
        private String customRules;
        private List<String> evidenceIds;
        /** BUGFIX 用：证据摘要文本 */
        private String evidenceSummary;
        /** BUGFIX 用：失败测试 / 复现摘要 */
        private String failingTests;
    }
}
