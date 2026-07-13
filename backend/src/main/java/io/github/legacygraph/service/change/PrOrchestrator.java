package io.github.legacygraph.service.change;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.PrDescriptionAgent;
import io.github.legacygraph.dto.PrDescription;
import io.github.legacygraph.dto.change.CreatePrRequest;
import io.github.legacygraph.dto.change.DraftFile;
import io.github.legacygraph.dto.change.PrRequest;
import io.github.legacygraph.dto.change.PrResult;
import io.github.legacygraph.dto.change.PrStatusInfo;
import io.github.legacygraph.entity.ChangeTask;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.PatchFile;
import io.github.legacygraph.entity.PrTask;
import io.github.legacygraph.entity.ValidationGate;
import io.github.legacygraph.repository.ChangeTaskRepository;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.PatchFileRepository;
import io.github.legacygraph.repository.PrTaskRepository;
import io.github.legacygraph.repository.ValidationGateRepository;
import io.github.legacygraph.service.pr.GitProviderAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import io.github.legacygraph.util.IdUtil;

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
    private final CodeRepoRepository codeRepoRepository;
    private final ChangeTaskRepository changeTaskRepository;
    private final ObjectProvider<List<GitProviderAdapter>> gitProviderAdaptersProvider;

    private static final DateTimeFormatter BRANCH_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public PrOrchestrator(PrTaskRepository prTaskRepository,
                          PatchFileRepository patchFileRepository,
                          ValidationGateRepository validationGateRepository,
                          PrDescriptionAgent prDescriptionAgent,
                          ObjectMapper objectMapper,
                          CodeRepoRepository codeRepoRepository,
                          ChangeTaskRepository changeTaskRepository,
                          ObjectProvider<List<GitProviderAdapter>> gitProviderAdaptersProvider) {
        this.prTaskRepository = prTaskRepository;
        this.patchFileRepository = patchFileRepository;
        this.validationGateRepository = validationGateRepository;
        this.prDescriptionAgent = prDescriptionAgent;
        this.objectMapper = objectMapper;
        this.codeRepoRepository = codeRepoRepository;
        this.changeTaskRepository = changeTaskRepository;
        this.gitProviderAdaptersProvider = gitProviderAdaptersProvider;
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
        prTask.setId(IdUtil.fastUUID());
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

    // ==================== 阶段三-3.2: PR 真正落地 ====================

    /**
     * 创建 PR 草案并推送到远程仓库（阶段三-3.2）。
     * <p>
     * 完整流程：
     * <ol>
     *   <li>创建本地分支（基于 targetBranch）</li>
     *   <li>应用补丁文件（DraftFile）</li>
     *   <li>提交并推送（git CLI）</li>
     *   <li>创建 PR（GitHub/GitLab API）</li>
     *   <li>回写 PrTask 的 prUrl 和 prStatus</li>
     * </ol>
     * </p>
     *
     * @param request PR 创建请求
     * @return PR 创建结果
     */
    public PrResult createAndPushPr(PrRequest request) {
        // ① 查询变更任务
        ChangeTask task = changeTaskRepository.selectById(request.getChangeTaskId());
        if (task == null) {
            return PrResult.builder()
                    .success(false)
                    .failureReason("变更任务不存在: " + request.getChangeTaskId())
                    .build();
        }

        // ② 查询 CodeRepo 获取本地路径和远程 URL
        CodeRepo repo = resolveCodeRepo(task.getProjectId());
        if (repo == null || repo.getLocalPath() == null || repo.getLocalPath().isBlank()) {
            return PrResult.builder()
                    .success(false)
                    .failureReason("项目未配置代码仓库本地路径，无法执行 Git 操作")
                    .build();
        }

        String provider = repo.getProvider() != null ? repo.getProvider() : "github";
        GitProviderAdapter adapter = resolveGitProvider(provider);
        if (adapter == null) {
            return PrResult.builder()
                    .success(false)
                    .failureReason("未找到 Git 提供商适配器: " + provider + "（请检查配置 legacygraph.pr." + provider + ".enabled）")
                    .build();
        }

        String sourceBranch = request.getSourceBranch();
        String targetBranch = request.getTargetBranch() != null
                ? request.getTargetBranch()
                : (repo.getDefaultBranch() != null ? repo.getDefaultBranch() : "main");

        try {
            // ③ 创建本地分支
            createLocalBranch(repo.getLocalPath(), sourceBranch, targetBranch);

            // ④ 应用补丁文件
            applyFilesToLocal(repo.getLocalPath(), request.getFiles());

            // ⑤ 提交并推送（漏点 ⑧ 修复：传入 accessToken，commitAndPush 内部按需注入 remote URL）
            String commitMessage = request.getCommitMessage() != null
                    ? request.getCommitMessage() : "Apply patch for " + request.getChangeTaskId();
            String commitSha = commitAndPush(repo.getLocalPath(), sourceBranch, commitMessage,
                    repo.getGitUrl(), repo.getAuthType(), repo.getUsername(),
                    repo.getAccessToken());

            // ⑥ 创建 PR
            CreatePrRequest createPrRequest = CreatePrRequest.builder()
                    .repoUrl(repo.getGitUrl())
                    .sourceBranch(sourceBranch)
                    .targetBranch(targetBranch)
                    .prTitle(request.getPrTitle())
                    .prBody(request.getPrBody())
                    .reviewers(null) // 可扩展：从 reviewTeam 解析
                    .build();
            PrResult prResult = adapter.createPullRequest(createPrRequest);

            // ⑦ 回写 PrTask
            if (prResult.isSuccess()) {
                updatePrTaskOnSuccess(request.getChangeTaskId(), sourceBranch, prResult);
            }

            prResult.setCommitSha(commitSha);
            return prResult;

        } catch (Exception e) {
            log.error("createAndPushPr failed for task {}: {}", request.getChangeTaskId(), e.getMessage());
            return PrResult.builder()
                    .success(false)
                    .failureReason("PR 推送失败: " + e.getMessage())
                    .branchName(sourceBranch)
                    .build();
        }
    }

    /**
     * 查询 PR 状态（阶段三-3.2 + 偏移 ③ 修复）。
     * <p>通过 PrTask → ChangeTask → projectId 的明确链查找到正确的 CodeRepo，
     * 再根据 CodeRepo.provider 选择适配器查询 PR 状态。多仓配置时不会取错。</p>
     */
    public PrStatusInfo getPrStatus(String prTaskId) {
        PrTask prTask = prTaskRepository.selectById(prTaskId);
        if (prTask == null || prTask.getPrUrl() == null) {
            return PrStatusInfo.builder()
                    .status("UNKNOWN")
                    .build();
        }

        // 偏移 ③ 修复：明确链查 prTask → changeTask → projectId → CodeRepo
        CodeRepo repo = resolveRepoByPrTask(prTask);
        if (repo != null) {
            GitProviderAdapter adapter = resolveGitProvider(
                    repo.getProvider() != null ? repo.getProvider() : "github");
            if (adapter != null) {
                return adapter.getPrStatus(prTask.getPrUrl());
            }
        }
        return PrStatusInfo.builder()
                .prUrl(prTask.getPrUrl())
                .status(prTask.getPrStatus())
                .build();
    }

    /**
     * 偏移 ③ 修复：通过 PrTask → ChangeTask → projectId → CodeRepo 链查找到仓库。
     * 找不到时降级为 null（调用方回退到 PrTask 自身 prStatus）。
     */
    private CodeRepo resolveRepoByPrTask(PrTask prTask) {
        if (prTask == null || prTask.getChangeTaskId() == null) {
            return null;
        }
        ChangeTask task = changeTaskRepository.selectById(prTask.getChangeTaskId());
        if (task == null || task.getProjectId() == null) {
            return null;
        }
        return resolveCodeRepo(task.getProjectId());
    }

    /**
     * 处理 PR 合并回调（阶段三-3.2）。
     *
     * @param prTaskId PrTask ID
     */
    @Transactional
    public void onPrMerged(String prTaskId) {
        PrTask prTask = prTaskRepository.selectById(prTaskId);
        if (prTask == null) {
            log.warn("onPrMerged: PrTask not found: {}", prTaskId);
            return;
        }
        prTask.setPrStatus("MERGED");
        prTaskRepository.updateById(prTask);

        // 更新 ChangeTask 状态为 MERGED
        ChangeTask task = changeTaskRepository.selectById(prTask.getChangeTaskId());
        if (task != null) {
            task.setStatus("MERGED");
            task.setUpdatedAt(LocalDateTime.now());
            changeTaskRepository.updateById(task);
        }
        log.info("PR merged: prTaskId={}, changeTaskId={}", prTaskId, prTask.getChangeTaskId());
    }

    // ==================== Git CLI 操作 ====================

    /** 创建本地分支 */
    private void createLocalBranch(String localPath, String branchName, String baseBranch) throws Exception {
        runGitCommand(localPath, "git", "checkout", baseBranch);
        runGitCommand(localPath, "git", "checkout", "-b", branchName);
        log.info("Local branch created: {} (based on {}) at {}", branchName, baseBranch, localPath);
    }

    /** 应用文件变更到本地仓库 */
    private void applyFilesToLocal(String localPath, List<DraftFile> files) throws Exception {
        if (files == null || files.isEmpty()) {
            return;
        }
        Path repoPath = Paths.get(localPath);
        for (DraftFile file : files) {
            if (file.getFilePath() == null || file.getFilePath().isBlank()) {
                continue;
            }
            Path fullPath = repoPath.resolve(file.getFilePath());
            String changeType = file.getChangeType() != null ? file.getChangeType().toUpperCase() : "MODIFY";
            switch (changeType) {
                case "DELETE" -> Files.deleteIfExists(fullPath);
                case "CREATE", "MODIFY" -> {
                    Files.createDirectories(fullPath.getParent());
                    String content = file.getNewContent() != null ? file.getNewContent() : "";
                    Files.writeString(fullPath, content, StandardCharsets.UTF_8);
                }
            }
        }
        // git add 所有变更
        runGitCommand(localPath, "git", "add", "-A");
    }

    /**
     * 提交并推送（漏点 ⑧ 修复：注入 accessToken）。
     * <p>当 authType=TOKEN 且 username + accessToken 非空时，把 token 注入到 remote URL 中：
     * https://username:token@host/owner/repo.git。
     * 其他场景按原 remote 推送。</p>
     */
    private String commitAndPush(String localPath, String branchName, String commitMessage,
                                  String remoteUrl, String authType, String username,
                                  String accessToken) throws Exception {
        runGitCommand(localPath, "git", "commit", "-m", commitMessage);
        String effectiveRemote = remoteUrl;
        if ("TOKEN".equalsIgnoreCase(authType)
                && username != null && !username.isBlank()
                && accessToken != null && !accessToken.isBlank()
                && remoteUrl != null && remoteUrl.startsWith("https://")) {
            effectiveRemote = "https://" + username + ":" + accessToken + "@"
                    + remoteUrl.substring("https://".length());
        }
        runGitCommand(localPath, "git", "push", "-u", effectiveRemote, branchName);
        String sha = runGitCommand(localPath, "git", "rev-parse", "HEAD").trim();
        log.info("Committed and pushed: branch={}, sha={}", branchName, sha);
        return sha;
    }

    /** 执行 Git 命令 */
    private String runGitCommand(String workingDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new java.io.File(workingDir));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Git 命令超时: " + String.join(" ", command));
        }
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("Git 命令失败 (" + process.exitValue() + "): "
                    + String.join(" ", command) + "\n" + output);
        }
        return output;
    }

    // ==================== 辅助 ====================

    /** 根据 projectId 查询 CodeRepo */
    private CodeRepo resolveCodeRepo(String projectId) {
        if (projectId == null) {
            return null;
        }
        try {
            List<CodeRepo> repos = codeRepoRepository.lambdaQuery()
                    .eq(CodeRepo::getProjectId, projectId)
                    .isNotNull(CodeRepo::getLocalPath)
                    .list();
            for (CodeRepo repo : repos) {
                if (repo.getLocalPath() != null && !repo.getLocalPath().isBlank()) {
                    return repo;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve CodeRepo for project {}: {}", projectId, e.getMessage());
        }
        return null;
    }

    /** 根据 provider ID 查找 GitProviderAdapter */
    private GitProviderAdapter resolveGitProvider(String provider) {
        List<GitProviderAdapter> adapters = gitProviderAdaptersProvider.getIfAvailable();
        if (adapters == null || adapters.isEmpty()) {
            return null;
        }
        return adapters.stream()
                .filter(a -> a.getProviderId().equalsIgnoreCase(provider))
                .findFirst()
                .orElse(null);
    }

    /** PR 创建成功后更新 PrTask */
    private void updatePrTaskOnSuccess(String changeTaskId, String branchName, PrResult prResult) {
        try {
            PrTask prTask = prTaskRepository.lambdaQuery()
                    .eq(PrTask::getChangeTaskId, changeTaskId)
                    .one();
            if (prTask != null) {
                prTask.setPrUrl(prResult.getPrUrl());
                prTask.setPrStatus("CREATED");
                prTask.setBranchName(branchName);
                prTaskRepository.updateById(prTask);
            } else {
                // 创建新的 PrTask
                PrTask newTask = new PrTask();
                newTask.setId(IdUtil.fastUUID());
                newTask.setChangeTaskId(changeTaskId);
                newTask.setBranchName(branchName);
                newTask.setPrUrl(prResult.getPrUrl());
                newTask.setPrStatus("CREATED");
                newTask.setCreatedAt(LocalDateTime.now());
                prTaskRepository.insert(newTask);
            }
        } catch (Exception e) {
            log.warn("Failed to update PrTask for changeTask {}: {}", changeTaskId, e.getMessage());
        }
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
