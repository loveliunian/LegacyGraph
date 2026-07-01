package io.github.legacygraph.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.PrDescriptionAgent;
import io.github.legacygraph.dto.PrDescription;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.entity.PatchFile;
import io.github.legacygraph.entity.PrTask;
import io.github.legacygraph.entity.ValidationGate;
import io.github.legacygraph.repository.PatchFileRepository;
import io.github.legacygraph.repository.PrTaskRepository;
import io.github.legacygraph.repository.ValidationGateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PR 编排器（可用版）— 见 doc §安全与回滚策略、§PrOrchestrator。
 * <p>
 * 受控产出 PR 草案，强约束：
 * </p>
 * <ul>
 *   <li>AI 只创建 feature branch，分支名固定前缀，不直推 main/master。</li>
 *   <li><b>未过门禁不建 PR</b>：任务状态必须为 VALIDATION_PASSED 且无 FAILED 门禁。</li>
 *   <li>reviewer 策略按任务类型：bugfix 1 人、upgrade 2 人、涉及 schema 变更须 DBA。</li>
 *   <li>始终生成回滚计划（回滚脚本/标签/环境镜像占位）。</li>
 * </ul>
 * <p>本类只落 {@code lg_pr_task} 并生成 PR 文案，真正的 Git 推送 / GitHub/GitLab API
 * 调用留待接入具体 VCS 适配器（prStatus 从 DRAFT 起步）。</p>
 */
@Slf4j
@Service
public class PrOrchestrator {

    private final PrTaskRepository prTaskRepository;
    private final PatchFileRepository patchFileRepository;
    private final ValidationGateRepository validationGateRepository;
    private final PrDescriptionAgent prDescriptionAgent;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter BRANCH_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public PrOrchestrator(PrTaskRepository prTaskRepository,
                          PatchFileRepository patchFileRepository,
                          ValidationGateRepository validationGateRepository,
                          PrDescriptionAgent prDescriptionAgent,
                          ObjectMapper objectMapper) {
        this.prTaskRepository = prTaskRepository;
        this.patchFileRepository = patchFileRepository;
        this.validationGateRepository = validationGateRepository;
        this.prDescriptionAgent = prDescriptionAgent;
        this.objectMapper = objectMapper;
    }

    /**
     * 为变更任务创建 PR 草案。
     *
     * @param task 变更任务（须已通过验证门禁）
     * @return 落库的 PrTask（prStatus=DRAFT）
     * @throws IllegalStateException 门禁未通过时拒绝创建
     */
    @Transactional
    public PrTask createPrDraft(ChangeTask task, LocalDateTime now) {
        // ① 门禁校验：未过不建 PR
        assertGatesPassed(task);

        // ② 受控分支名（feature 前缀，不触碰 main/master）
        String ts = now != null ? now.format(BRANCH_TS) : "draft";
        String branchName = String.format("legacygraph/%s/%s-%s",
                task.getTaskType() != null ? task.getTaskType().toLowerCase() : "change",
                shortId(task.getId()), ts);

        // ③ 生成 PR 文案（复用 PrDescriptionAgent）
        String diff = buildDiffSummary(task.getId());
        PrDescription description = null;
        try {
            description = prDescriptionAgent.generate(
                    task.getProjectId(), branchName, task.getInputIssue(), diff);
        } catch (Exception e) {
            log.warn("PrDescriptionAgent failed for task {}: {}", task.getId(), e.getMessage());
        }

        // ④ reviewer 策略 + 回滚计划
        String reviewerPolicy = toJson(buildReviewerPolicy(task));
        String rollbackPlan = toJson(buildRollbackPlan(task, branchName));

        PrTask prTask = new PrTask();
        prTask.setId(UUID.randomUUID().toString());
        prTask.setChangeTaskId(task.getId());
        prTask.setBranchName(branchName);
        prTask.setPrUrl(null); // 真正推送后回填
        prTask.setPrStatus("DRAFT");
        prTask.setReviewerPolicy(reviewerPolicy);
        prTask.setRollbackPlan(rollbackPlan);
        prTask.setCreatedAt(now != null ? now : LocalDateTime.now());
        prTaskRepository.insert(prTask);

        log.info("PR draft created for task {}: branch={}, reviewers={}",
                task.getId(), branchName, reviewerPolicy);
        // PR 文案暂存于日志/后续接口返回；如需持久化可扩展 PrTask 字段
        if (description != null) {
            log.debug("PR title: {}", description.getPrTitle());
        }
        return prTask;
    }

    // ==================== 内部 ====================

    /** 门禁校验：任务须 VALIDATION_PASSED 且不存在 FAILED 门禁。 */
    private void assertGatesPassed(ChangeTask task) {
        if (!"VALIDATION_PASSED".equals(task.getStatus())) {
            throw new IllegalStateException(
                    "任务未通过验证门禁（当前状态 " + task.getStatus() + "），不允许创建 PR");
        }
        List<ValidationGate> gates = validationGateRepository.lambdaQuery()
                .eq(ValidationGate::getChangeTaskId, task.getId())
                .list();
        boolean anyFailed = gates.stream().anyMatch(g -> "FAILED".equals(g.getResult()));
        boolean anyPending = gates.stream().anyMatch(g ->
                "PENDING".equals(g.getResult()) || "RUNNING".equals(g.getResult()));
        if (anyFailed) {
            throw new IllegalStateException("存在未通过的验证门禁，不允许创建 PR");
        }
        if (anyPending) {
            throw new IllegalStateException("存在未完成的验证门禁，不允许创建 PR");
        }
    }

    /** reviewer 策略：bugfix 1 人、upgrade 2 人；涉及 schema 变更强制 DBA。 */
    private Map<String, Object> buildReviewerPolicy(ChangeTask task) {
        String type = task.getTaskType() != null ? task.getTaskType() : "";
        int minReviewers = "UPGRADE".equals(type) ? 2 : 1;
        boolean dbaRequired = touchesSchema(task.getId()) || "UPGRADE".equals(type);
        return Map.of(
                "minReviewers", minReviewers,
                "dbaRequired", dbaRequired,
                "reason", "bugfix=1, upgrade=2, schema 变更须 DBA");
    }

    /** 回滚计划：回滚脚本、回滚标签、环境镜像版本（占位，接 VCS/DBA 后补实）。 */
    private Map<String, Object> buildRollbackPlan(ChangeTask task, String branchName) {
        return Map.of(
                "strategy", "revert-branch",
                "revertRef", branchName,
                "rollbackTag", "pre-" + shortId(task.getId()),
                "dbBackupRequired", touchesSchema(task.getId()),
                "note", "合并后保留回滚脚本/标签/环境镜像版本");
    }

    /** 是否触碰 schema：补丁文件涉及迁移/SQL 目录时视为 schema 变更。 */
    private boolean touchesSchema(String changeTaskId) {
        List<PatchFile> patches = patchFileRepository.lambdaQuery()
                .eq(PatchFile::getChangeTaskId, changeTaskId)
                .list();
        return patches.stream().anyMatch(p -> {
            String fp = p.getFilePath() != null ? p.getFilePath().toLowerCase() : "";
            return fp.contains("/migration/") || fp.endsWith(".sql")
                    || fp.contains("schema") || fp.contains("flyway");
        });
    }

    /** 汇总补丁作为 PR diff 摘要输入。 */
    private String buildDiffSummary(String changeTaskId) {
        List<PatchFile> patches = patchFileRepository.lambdaQuery()
                .eq(PatchFile::getChangeTaskId, changeTaskId)
                .list();
        if (patches.isEmpty()) {
            return "（无补丁文件）";
        }
        return patches.stream()
                .map(p -> String.format("%s (%s)\n%s",
                        p.getFilePath(), p.getChangeType(),
                        p.getPatchText() != null ? p.getPatchText() : ""))
                .collect(Collectors.joining("\n---\n"));
    }

    private String shortId(String id) {
        if (id == null) return "unknown";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize PR metadata: {}", e.getMessage());
            return null;
        }
    }
}
