package io.github.legacygraph.service.change;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.ChangeImpactAgent;
import io.github.legacygraph.agent.adapter.AddColumnPatchAgentAdapter;
import io.github.legacygraph.agent.adapter.MigrationAgentAdapter;
import io.github.legacygraph.agent.adapter.RefactorAgentAdapter;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.ChangeImpactAnalysis;
import io.github.legacygraph.dto.change.ChangeTaskProposal;
import io.github.legacygraph.dto.graph.AgentEnvelope;
import io.github.legacygraph.dto.graph.ImpactSubgraph;
import io.github.legacygraph.dto.graph.PatchPlan;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.entity.PatchFile;
import io.github.legacygraph.entity.PrTask;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.entity.ValidationGate;
import io.github.legacygraph.repository.*;
import io.github.legacygraph.service.test.PatchPlanValidator;
import io.github.legacygraph.service.test.ValidationGateRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.github.legacygraph.util.IdUtil;

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
    private final AddColumnPatchAgentAdapter addColumnPatchAgentAdapter;
    private final io.github.legacygraph.agent.PatchPlanAgent patchPlanAgent;
    private final PatchPlanValidator patchPlanValidator;
    private final ValidationGateRunner validationGateRunner;
    private final PrOrchestrator prOrchestrator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ColumnIngestService columnIngestService;
    private final Neo4jGraphDao neo4jGraphDao;

    public ChangeTaskService(ChangeTaskRepository changeTaskRepository,
                             PatchFileRepository patchFileRepository,
                             ValidationGateRepository validationGateRepository,
                             ReviewRecordRepository reviewRecordRepository,
                             ImpactSubgraphService impactSubgraphService,
                             ChangeImpactAgent changeImpactAgent,
                             RefactorAgentAdapter refactorAgentAdapter,
                             MigrationAgentAdapter migrationAgentAdapter,
                             AddColumnPatchAgentAdapter addColumnPatchAgentAdapter,
                             io.github.legacygraph.agent.PatchPlanAgent patchPlanAgent,
                             PatchPlanValidator patchPlanValidator,
                             ValidationGateRunner validationGateRunner,
                             PrOrchestrator prOrchestrator,
                             ObjectMapper objectMapper,
                             TransactionTemplate transactionTemplate,
                             ColumnIngestService columnIngestService,
                             Neo4jGraphDao neo4jGraphDao) {
        this.changeTaskRepository = changeTaskRepository;
        this.patchFileRepository = patchFileRepository;
        this.validationGateRepository = validationGateRepository;
        this.reviewRecordRepository = reviewRecordRepository;
        this.impactSubgraphService = impactSubgraphService;
        this.changeImpactAgent = changeImpactAgent;
        this.refactorAgentAdapter = refactorAgentAdapter;
        this.migrationAgentAdapter = migrationAgentAdapter;
        this.addColumnPatchAgentAdapter = addColumnPatchAgentAdapter;
        this.patchPlanAgent = patchPlanAgent;
        this.patchPlanValidator = patchPlanValidator;
        this.validationGateRunner = validationGateRunner;
        this.prOrchestrator = prOrchestrator;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.columnIngestService = columnIngestService;
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /** 创建变更任务（状态 OPEN）—— 短 TX。 */
    @Transactional
    public ChangeTask createTask(String projectId, String versionId, String taskType,
                                 String title, String inputIssue) {
        ChangeTask task = new ChangeTask();
        task.setId(IdUtil.fastUUID());
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
     * 更新任务的 proposal（补丁方案 JSON），不改变状态。
     * <p>用于方案桥接时注入初始代码片段，或人工补充补丁草案。</p>
     *
     * @param taskId      任务 ID
     * @param proposalJson PatchPlan JSON 字符串
     * @return 更新后的任务
     */
    @Transactional
    public ChangeTask updateProposal(String taskId, String proposalJson) {
        ChangeTask task = requireTask(taskId);
        task.setProposal(proposalJson);
        task.setUpdatedAt(LocalDateTime.now());
        changeTaskRepository.updateById(task);
        log.info("ChangeTask proposal updated: taskId={}", taskId);
        return task;
    }

    /**
     * 基于图谱刷新影响子图（OPEN → IMPACT_READY）。
     * targetNodeId 为变更目标节点。
     * <p>Phase 0-2：移除 @Transactional，LLM 调用不再持有数据库连接。</p>
     */
    public ImpactSubgraph refreshImpact(String taskId, String targetNodeId) {
        ChangeTask task = requireTask(taskId);
        ImpactSubgraph subgraph = impactSubgraphService.extractByNodeMultiHop(
                task.getProjectId(), task.getVersionId(), targetNodeId,
                io.github.legacygraph.common.TraversalDirection.TABLE_REVERSE, 3);

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
     * <p>G-12：若 task.proposal 已包含 ChangeTaskProposal 格式 JSON（由方案桥接注入），
     * 优先从 proposal.files 读取已有 unified diff，跳过 Agent 调用。
     * 仅在无 proposal 或非 ChangeTaskProposal 格式时走原有 Adapter 链路。</p>
     */
    public PatchPlan generatePatch(String taskId, PatchGenRequest req) {
        ChangeTask task = requireTask(taskId);
        ImpactSubgraph subgraph = fromJson(task.getImpactedSubgraph(), ImpactSubgraph.class);

        // G-12: 优先从已有 ChangeTaskProposal 读取 patch（方案桥接阶段注入）
        PatchPlan plan = tryBuildPlanFromProposal(task);
        if (plan == null) {
            // 无可用 ChangeTaskProposal，走原有 Agent 链路
            plan = generatePatchViaAgent(taskId, task, req, subgraph);
        }

        if (plan == null) {
            throw new IllegalStateException("补丁生成失败：Agent 返回空计划");
        }

        final PatchPlan finalPlan = plan;
        // 三类校验：范围/格式/证据（无 TX）
        PatchPlanValidator.ValidationResult vr = patchPlanValidator.validate(finalPlan, subgraph);

        // 短 TX 持久化 patch files + 状态更新
        transactionTemplate.executeWithoutResult(status ->
                persistPatchResult(taskId, finalPlan, vr, task.getProjectId(), task.getVersionId()));
        return finalPlan;
    }

    /**
     * G-12: 尝试从 task.proposal 解析 ChangeTaskProposal 并转换为 PatchPlan。
     * <p>仅当 proposal 为 ChangeTaskProposal 格式（含非空 files 数组）时返回非 null，
     * 否则返回 null（调用方应走 Agent 链路）。</p>
     */
    private PatchPlan tryBuildPlanFromProposal(ChangeTask task) {
        String proposalJson = task.getProposal();
        if (proposalJson == null || proposalJson.isBlank()) {
            return null;
        }
        try {
            ChangeTaskProposal proposal = objectMapper.readValue(
                    proposalJson, ChangeTaskProposal.class);
            if (proposal == null
                    || proposal.getFiles() == null
                    || proposal.getFiles().isEmpty()) {
                return null;
            }
            // 转换 ChangeTaskProposal.ProposalFile → PatchPlan.Patch
            List<PatchPlan.Patch> patches = new ArrayList<>();
            List<PatchPlan.ImpactedFile> impactedFiles = new ArrayList<>();
            for (ChangeTaskProposal.ProposalFile file : proposal.getFiles()) {
                patches.add(PatchPlan.Patch.builder()
                        .filePath(file.getFilePath())
                        .changeType(file.getOp())
                        .patchText(file.getDiff())
                        .evidenceIds(file.getEvidenceIds())
                        .build());
                impactedFiles.add(PatchPlan.ImpactedFile.builder()
                        .path(file.getFilePath())
                        .reason(file.getSymbolName())
                        .build());
            }
            return PatchPlan.builder()
                    .taskId(task.getId())
                    .taskType(task.getTaskType())
                    .riskLevel(task.getRiskLevel() != null ? task.getRiskLevel() : "LOW")
                    .impactedFiles(impactedFiles)
                    .patches(patches)
                    .generatedBy("solution-bridge")
                    .build();
        } catch (Exception e) {
            // 非 ChangeTaskProposal 格式（如旧 PatchPlan 或非法 JSON），走 Agent 链路
            log.debug("Proposal 不为 ChangeTaskProposal 格式，将走 Agent 链路: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 通过 Adapter 链路生成补丁（原有逻辑）。
     */
    private PatchPlan generatePatchViaAgent(String taskId, ChangeTask task,
                                            PatchGenRequest req, ImpactSubgraph subgraph) {
        List<String> evidenceIds = req.getEvidenceIds() != null ? req.getEvidenceIds() : List.of();

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
            case "ADD_COLUMN" -> plan = addColumnPatchAgentAdapter.toPatchPlan(
                    taskId, task.getProjectId(), req.getTableName(), req.getColumnName(),
                    req.getColumnType(),
                    req.getNullable() != null && req.getNullable(),
                    req.getDefaultValue(),
                    subgraph, evidenceIds);
            default -> throw new IllegalArgumentException(
                    "任务类型 " + task.getTaskType() + " 暂无自动补丁生成器");
        }
        return plan;
    }

    private void persistPatchResult(String taskId, PatchPlan plan,
                                     PatchPlanValidator.ValidationResult vr,
                                     String projectId, String versionId) {
        ChangeTask task = requireTask(taskId);

        String patchStatus = vr.isNeedsReview() ? "REVIEW_PENDING" : "DRAFT";
        for (PatchPlan.Patch p : plan.getPatches()) {
            PatchFile pf = new PatchFile();
            pf.setId(IdUtil.fastUUID());
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
            gate.setId(IdUtil.fastUUID());
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
        ChangeTask validated = transactionTemplate.execute(status ->
                updateTaskAfterValidation(taskId, task.getProjectId(), task.getVersionId(),
                        task.getTitle(), allPassed));
        // P4 闭环：ADD_COLUMN 验证通过后回写 Column 节点 + 失效语义缓存
        if (allPassed && validated != null && "ADD_COLUMN".equals(validated.getTaskType())) {
            try {
                PatchPlan plan = fromJson(validated.getProposal(), PatchPlan.class);
                columnIngestService.onValidationPassed(validated, plan);
            } catch (Exception e) {
                log.warn("Post-validation column ingest failed for task {}: {}", taskId, e.getMessage());
            }
        }
        return validated;
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
            review.setId(IdUtil.fastUUID());
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

    /**
     * PR 合并后的回调：标记相关图谱节点为 stale。
     * 不触发自动重新扫描，下次手动扫描时优先处理 stale 节点。
     */
    @Transactional
    public ChangeTask onPrMerged(String taskId) {
        ChangeTask task = requireTask(taskId);
        task.setStatus("MERGED");
        task.setUpdatedAt(LocalDateTime.now());
        changeTaskRepository.updateById(task);

        // 标记 PatchFile 涉及的图谱节点为 stale
        List<PatchFile> patches = patchFileRepository.lambdaQuery()
                .eq(PatchFile::getChangeTaskId, taskId).list();
        for (PatchFile pf : patches) {
            // 通过 filePath 在图谱中查找节点，设置 properties.stale=true
            try {
                markGraphNodesStale(task.getProjectId(), pf.getFilePath());
            } catch (Exception e) {
                log.warn("Failed to mark stale for {}: {}", pf.getFilePath(), e.getMessage());
            }
        }
        log.info("ChangeTask {} PR merged, marked {} patches as stale", taskId, patches.size());
        return task;
    }

    // ==================== 指派与领取（G-13） ====================

    /**
     * 领取任务：当前 principal 接手任务。
     * <p>仅 OPEN / IMPACT_READY 状态可领取；使用原子 UPDATE 防止并发冲突。</p>
     *
     * @param taskId    任务 ID
     * @param principal 领取人标识
     * @return 更新后的任务
     * @throws IllegalStateException 任务已被他人领取或状态不允许领取
     */
    @Transactional
    public ChangeTask claimTask(String taskId, String principal) {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("领取人 principal 不能为空");
        }
        ChangeTask task = requireTask(taskId);

        String status = task.getStatus();
        if (!"OPEN".equals(status) && !"IMPACT_READY".equals(status)) {
            throw new IllegalStateException(
                    "任务状态不允许领取: " + status + "（仅 OPEN / IMPACT_READY 可领取）");
        }

        // 原子更新：只有 assignee 为空时才更新，防止并发领取
        UpdateWrapper<ChangeTask> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", taskId)
                .isNull("assignee")
                .set("assignee", principal)
                .set("assignee_type", "USER")
                .set("claimed_at", LocalDateTime.now())
                .set("updated_at", LocalDateTime.now());
        int updated = changeTaskRepository.update(null, updateWrapper);

        if (updated == 0) {
            throw new IllegalStateException("任务已被他人领取，请刷新后重试");
        }

        ChangeTask claimed = requireTask(taskId);
        log.info("ChangeTask {} claimed by {}", taskId, principal);
        return claimed;
    }

    /**
     * 指派任务：将任务指派给指定的用户/团队/角色。
     * <p>仅 Lead/PM 角色可调用（权限校验在 controller 层做）。</p>
     *
     * @param taskId       任务 ID
     * @param assignee     被指派人/团队/角色标识
     * @param assigneeType 指派类型：USER / TEAM / ROLE
     * @param dueAt        截止时间（可选）
     * @return 更新后的任务
     */
    @Transactional
    public ChangeTask assignTask(String taskId, String assignee, String assigneeType,
                                  LocalDateTime dueAt) {
        if (assignee == null || assignee.isBlank()) {
            throw new IllegalArgumentException("被指派人 assignee 不能为空");
        }
        if (assigneeType == null || assigneeType.isBlank()) {
            assigneeType = "USER";
        }
        String type = assigneeType.toUpperCase();
        if (!"USER".equals(type) && !"TEAM".equals(type) && !"ROLE".equals(type)) {
            throw new IllegalArgumentException("无效的 assigneeType: " + assigneeType
                    + "（应为 USER / TEAM / ROLE）");
        }

        ChangeTask task = requireTask(taskId);
        task.setAssignee(assignee);
        task.setAssigneeType(type);
        task.setDueAt(dueAt);
        if (task.getClaimedAt() == null && "USER".equals(type)) {
            task.setClaimedAt(LocalDateTime.now());
        }
        task.setUpdatedAt(LocalDateTime.now());
        changeTaskRepository.updateById(task);

        log.info("ChangeTask {} assigned to {} ({})", taskId, assignee, type);
        return task;
    }

    /**
     * 查询项目下的变更任务，支持按 assignee 和状态过滤。
     *
     * @param projectId 项目 ID
     * @param assignee  被指派人（可选，null 表示不过滤）
     * @param status    状态（可选，null 表示不过滤）
     * @return 任务列表，按更新时间倒序
     */
    public List<ChangeTask> listTasks(String projectId, String assignee, String status) {
        if (projectId == null || projectId.isBlank()) {
            return List.of();
        }
        LambdaQueryWrapper<ChangeTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChangeTask::getProjectId, projectId);
        if (assignee != null && !assignee.isBlank()) {
            wrapper.eq(ChangeTask::getAssignee, assignee);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(ChangeTask::getStatus, status);
        }
        wrapper.orderByDesc(ChangeTask::getUpdatedAt)
                .orderByDesc(ChangeTask::getCreatedAt);
        return changeTaskRepository.selectList(wrapper);
    }

    // ==================== 内部辅助 ====================

    private ChangeTask requireTask(String taskId) {
        ChangeTask task = changeTaskRepository.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("变更任务不存在: " + taskId);
        }
        return task;
    }

    /**
     * 按 sourcePath 查找图谱节点并标记 stale=true。
     * versionId 传 null 以覆盖项目下所有版本。
     */
    private void markGraphNodesStale(String projectId, String filePath) {
        List<Map<String, Object>> nodes = neo4jGraphDao.findNodesBySourcePath(projectId, null, filePath);
        for (Map<String, Object> node : nodes) {
            Object nodeId = node.get("nodeId");
            if (nodeId != null) {
                neo4jGraphDao.setNodeProperty(nodeId.toString(), "stale", true);
            }
        }
    }

    private void createReviewRecord(ChangeTask task, PatchPlanValidator.ValidationResult vr) {
        ReviewRecord review = new ReviewRecord();
        review.setId(IdUtil.fastUUID());
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
        /** ADD_COLUMN 用：目标表名 */
        private String tableName;
        /** ADD_COLUMN 用：字段名 */
        private String columnName;
        /** ADD_COLUMN 用：字段类型（如 VARCHAR(32)） */
        private String columnType;
        /** ADD_COLUMN 用：是否可空 */
        private Boolean nullable;
        /** ADD_COLUMN 用：默认值（可空） */
        private String defaultValue;
    }
}
