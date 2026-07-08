package io.github.legacygraph.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.ScanStep;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.entity.AiScanJob;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.repository.AiScanJobRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.service.graph.GapFinderService;
import io.github.legacygraph.task.step.AiScanStepExecutor;
import io.github.legacygraph.task.step.StepExecutionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 扫描后 AI 编排器 — Phase 1。
 *
 * <p>按"步骤执行器模式"重构：本类只保留任务生命周期管理（enqueue / recordSkipped /
 * AI_ORCHESTRATION 任务的 QUEUED→RUNNING→终态）与 orchestrate 的控制流编排
 * （ScanStep 状态机推进、取消检查、autoGenerateTestCase 门控），具体业务逻辑委托给
 * {@link AiScanStepExecutor} 步骤执行器。</p>
 *
 * <p>扫描成功后，按 {@link AiScanConfig} 开关依次执行文档/代码事实抽取、功能到代码映射、
 * 功能映射对齐、测试用例生成、低置信审核准备、知识缺口扫描、代码理解增强等步骤。
 * 所有 AI 结果默认 PENDING_CONFIRM，每个子任务独立容错：单步失败不会中断整体编排。</p>
 */
@Slf4j
@Component
public class AiScanOrchestrator {

    private final ScanTaskRepository scanTaskRepository;
    private final ScanTaskRecorder scanTaskRecorder;
    private final AiScanJobRepository aiScanJobRepository;
    private final ObjectMapper objectMapper;
    private final List<AiScanStepExecutor> stepExecutors;
    private final GapFinderService gapFinderService;

    public AiScanOrchestrator(ScanTaskRepository scanTaskRepository,
                              ScanTaskRecorder scanTaskRecorder,
                              AiScanJobRepository aiScanJobRepository,
                              ObjectMapper objectMapper,
                              List<AiScanStepExecutor> stepExecutors,
                              @Autowired(required = false) GapFinderService gapFinderService) {
        this.scanTaskRepository = scanTaskRepository;
        this.scanTaskRecorder = scanTaskRecorder;
        this.aiScanJobRepository = aiScanJobRepository;
        this.objectMapper = objectMapper;
        // 按 order 排序，固定步骤执行顺序
        List<AiScanStepExecutor> sorted = new ArrayList<>(stepExecutors);
        sorted.sort(Comparator.comparingInt(AiScanStepExecutor::getOrder));
        this.stepExecutors = sorted;
        this.gapFinderService = gapFinderService;
    }

    /**
     * 将 AI 增强任务排入异步队列，不阻塞基础扫描完成。
     * 基础扫描完成后，由 AiScanJobWorker 定期拉取 PENDING job 异步执行。
     */
    public void enqueue(String projectId, String versionId, AiScanConfig config) {
        AiScanJob job = new AiScanJob();
        job.setProjectId(projectId);
        job.setVersionId(versionId);
        job.setStatus("PENDING");
        try {
            job.setConfigJson(objectMapper.writeValueAsString(config));
        } catch (Exception e) {
            log.warn("Failed to serialize AI config for job: {}", e.getMessage());
        }
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        aiScanJobRepository.insert(job);
        log.info("AI scan job enqueued: projectId={}, versionId={}, jobId={}", projectId, versionId, job.getId());

        // 创建 AI_ORCHESTRATION 类型的 ScanTask（状态为 QUEUED）
        // 让前端进度 API 能实时看到该阶段，避免显示为"已跳过"
        ScanTask aiTask = scanTaskRecorder.createTask(projectId, versionId, "AI_ORCHESTRATION", "AI智能分析");
        // 立即标记为 QUEUED（createTask 默认是 RUNNING）
        aiTask.setTaskStatus("QUEUED");
        aiTask.setUpdatedAt(LocalDateTime.now());
        try {
            scanTaskRepository.updateById(aiTask);
        } catch (Exception e) {
            log.warn("Failed to set AI_ORCHESTRATION task to QUEUED: {}", e.getMessage());
        }
        log.info("Created AI_ORCHESTRATION ScanTask (QUEUED): projectId={}, versionId={}, taskId={}",
                projectId, versionId, aiTask.getId());
    }

    /**
     * 记录 AI 编排跳过状态（enableAi=false 时的替代路径）。
     */
    public void recordSkipped(String projectId, String versionId) {
        ScanTask task = scanTaskRecorder.createTask(projectId, versionId, "AI_ORCHESTRATION", "AI 编排");
        scanTaskRecorder.completeTask(task, "AI 编排已跳过：enableAi=false", null, "SKIPPED");
        log.info("AI orchestration recorded as skipped: projectId={}, versionId={}", projectId, versionId);
    }

    /**
     * 执行扫描后 AI 编排。未启用 AI 时直接返回。
     *
     * @param isCancelled 取消检查函数，每个子阶段前调用；为 null 时不检查
     * @param jobId 当前任务 ID，用于更新状态机步骤；可为 null（向后兼容）
     */
    public void orchestrate(String projectId, String versionId, AiScanConfig config,
                            BooleanSupplier isCancelled, String jobId) {
        if (config == null || !config.isEnableAi()) {
            log.info("AI orchestration skipped (enableAi=false): versionId={}", versionId);
            // 尝试复用已创建的 AI_ORCHESTRATION task（由 enqueue() 创建）
            ScanTask skipTask = findExistingAiOrchestrationTask(projectId, versionId);
            if (skipTask == null) {
                skipTask = createTask(projectId, versionId, "AI_ORCHESTRATION", "AI 编排");
            }
            completeTask(skipTask,
                    "⚠ AI 编排已跳过：enableAi=false（未启用 AI 归纳）。"
                    + "业务图谱（业务域/流程/功能/对象/角色）将不会生成。"
                    + "如需生成业务图谱，请在 scanScope 中设置 enableAi=true。",
                    null);
            if (gapFinderService != null) {
                try {
                    gapFinderService.scanGaps(projectId, versionId);
                } catch (Exception gapEx) {
                    log.warn("Knowledge gap scan failed (non-blocking): versionId={}, err={}",
                            versionId, gapEx.getMessage());
                }
            }
            return;
        }
        log.info("Starting AI orchestration: projectId={}, versionId={}, config={}",
                projectId, versionId, config);

        // enqueue 时创建了 AI_ORCHESTRATION 任务（QUEUED），这里标记 RUNNING
        markAiOrchestrationRunning(projectId, versionId);

        StepExecutionContext ctx = StepExecutionContext.builder()
                .projectId(projectId)
                .versionId(versionId)
                .config(config)
                .cancellationChecker(isCancelled)
                .build();

        boolean succeeded = false;
        try {
            // 按 order 遍历各步骤执行器：先门控判断（shouldExecute），再检查取消，
            // 再推进状态机步骤（updateStep），最后 execute。
            // DOC_EXTRACT/CODE_EXTRACT 顺序执行（共享抽取线程池 4 路 LLM）。
            // AI_GAP_FINDING 与 AI_CODE_UNDERSTANDING 同为 ENHANCE 步骤，各自 updateStep(ENHANCE)（幂等，可接受）。
            for (AiScanStepExecutor executor : stepExecutors) {
                if (!executor.shouldExecute(ctx)) {
                    continue;
                }
                if (isCancelled != null && isCancelled.getAsBoolean()) {
                    log.info("AI orchestration cancelled before step {}: versionId={}",
                            executor.getStepName(), versionId);
                    updateStep(jobId, ScanStep.FAILED);
                    return;
                }
                updateStep(jobId, executor.getScanStep());
                executor.execute(ctx);
            }

            // INDEX → COMPLETE
            updateStep(jobId, ScanStep.INDEX);
            updateStep(jobId, ScanStep.COMPLETE);

            log.info("AI orchestration completed: versionId={}", versionId);
            succeeded = true;
        } finally {
            // 无论成功/取消/异常，都把 AI_ORCHESTRATION 任务从 QUEUED/RUNNING 推进到终态
            // （否则前端扫描详情里 AI 智能分析一直显示 QUEUED）
            completeAiOrchestrationTask(projectId, versionId, succeeded);
        }
    }

    /**
     * 向后兼容的旧签名
     */
    public void orchestrate(String projectId, String versionId, AiScanConfig config,
                            BooleanSupplier isCancelled) {
        orchestrate(projectId, versionId, config, isCancelled, null);
    }

    /**
     * 更新任务状态机步骤
     */
    private void updateStep(String jobId, ScanStep step) {
        if (jobId == null) return;
        try {
            AiScanJob update = new AiScanJob();
            update.setId(jobId);
            update.setCurrentStep(step.name());
            update.setUpdatedAt(LocalDateTime.now());
            aiScanJobRepository.updateById(update);
            log.debug("Job {} step updated to {}", jobId, step.name());
        } catch (Exception e) {
            log.warn("Failed to update job step: jobId={}, step={}, error={}", jobId, step.name(), e.getMessage());
        }
    }

    /**
     * 查找已存在的 AI_ORCHESTRATION 类型的 ScanTask（由 enqueue() 预创建）。
     */
    private ScanTask findExistingAiOrchestrationTask(String projectId, String versionId) {
        try {
            LambdaQueryWrapper<ScanTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ScanTask::getProjectId, projectId)
                    .eq(ScanTask::getVersionId, versionId)
                    .eq(ScanTask::getTaskType, "AI_ORCHESTRATION")
                    .last("LIMIT 1");
            return scanTaskRepository.selectOne(wrapper);
        } catch (Exception e) {
            log.warn("Failed to find existing AI_ORCHESTRATION task: {}", e.getMessage());
            return null;
        }
    }

    /** 将 enqueue 创建的 AI_ORCHESTRATION 任务（QUEUED）标记为 RUNNING。 */
    private void markAiOrchestrationRunning(String projectId, String versionId) {
        ScanTask task = findExistingAiOrchestrationTask(projectId, versionId);
        if (task == null || !"QUEUED".equals(task.getTaskStatus())) {
            return;
        }
        task.setTaskStatus("RUNNING");
        task.setUpdatedAt(LocalDateTime.now());
        try {
            scanTaskRepository.updateById(task);
        } catch (Exception e) {
            log.warn("Failed to set AI_ORCHESTRATION to RUNNING: {}", e.getMessage());
        }
    }

    /**
     * 完成 AI_ORCHESTRATION 任务（SUCCESS/FAILED），避免一直卡在 QUEUED/RUNNING。
     * 已是终态（SUCCESS/FAILED/SKIPPED/WARNING）则跳过。
     */
    private void completeAiOrchestrationTask(String projectId, String versionId, boolean succeeded) {
        ScanTask task = findExistingAiOrchestrationTask(projectId, versionId);
        if (task == null) {
            return;
        }
        String st = task.getTaskStatus();
        if ("SUCCESS".equals(st) || "FAILED".equals(st) || "SKIPPED".equals(st) || "WARNING".equals(st)) {
            return;
        }
        if (succeeded) {
            completeTask(task, "AI 智能分析完成", null);
        } else {
            completeTask(task, "AI 智能分析未完成（取消或异常）", "cancelled or failed");
        }
    }

    private ScanTask createTask(String projectId, String versionId, String taskType, String taskName) {
        if (scanTaskRecorder != null) {
            return scanTaskRecorder.createTask(projectId, versionId, taskType, taskName);
        }
        // fallback: 测试环境 scanTaskRecorder 可能为 null
        ScanTask task = new ScanTask();
        task.setId(io.github.legacygraph.util.IdUtil.fastUUID());
        task.setProjectId(projectId);
        task.setVersionId(versionId);
        task.setTaskType(taskType);
        task.setTaskName(taskName);
        task.setTaskStatus("RUNNING");
        task.setStartedAt(LocalDateTime.now());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        scanTaskRepository.insert(task);
        return task;
    }

    private void completeTask(ScanTask task, String summary, String error) {
        String terminalStatus;
        if (error != null) {
            terminalStatus = "FAILED";
        } else if (summary != null && summary.startsWith("⚠")) {
            terminalStatus = "WARNING";
        } else {
            terminalStatus = "SUCCESS";
        }
        if (scanTaskRecorder != null) {
            scanTaskRecorder.completeTask(task, summary, error,
                    "SUCCESS".equals(terminalStatus) ? null : terminalStatus);
            return;
        }
        // fallback: 测试环境
        try {
            if (summary != null) {
                task.setOutputSummary(objectMapper.writeValueAsString(summary));
            }
        } catch (Exception e) {
            // 先转义反斜杠，再转义双引号（顺序不能颠倒，否则会二次转义）
            task.setOutputSummary("\"" + (summary != null ? summary.replace("\\", "\\\\").replace("\"", "\\\"") : "") + "\"");
        }
        task.setErrorMessage(error);
        task.setTaskStatus(terminalStatus);
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        scanTaskRepository.updateById(task);
    }
}
