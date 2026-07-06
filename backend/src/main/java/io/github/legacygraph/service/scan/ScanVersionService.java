package io.github.legacygraph.service.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.legacygraph.common.ErrorCode;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.CreateScanVersionRequest;
import io.github.legacygraph.dto.ScanPhase;
import io.github.legacygraph.dto.ScanProgressResponse;
import io.github.legacygraph.task.ScanPhaseRegistry;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.entity.*;
import io.github.legacygraph.exception.BusinessException;
import io.github.legacygraph.repository.*;
import io.github.legacygraph.service.graph.GraphCacheInvalidator;
import io.github.legacygraph.service.system.CacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class ScanVersionService extends ServiceImpl<ScanVersionRepository, ScanVersion> {

    private final ScanTaskRepository scanTaskRepository;
    private final ScanVersionRepository scanVersionRepository;
    private final CacheService cacheService;
    private final GraphCacheInvalidator graphCacheInvalidator;
    private final Neo4jGraphDao neo4jGraphDao;
    private final GraphNodeRepository graphNodeRepository;
    private final GraphEdgeRepository graphEdgeRepository;
    private final FactRepository factRepository;
    private final EvidenceRepository evidenceRepository;
    private final NodeEvidenceRepository nodeEvidenceRepository;
    private final EdgeEvidenceRepository edgeEvidenceRepository;
    private final DocumentRepository documentRepository;
    private final DocChunkRepository docChunkRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestRunRepository testRunRepository;
    private final TestResultRepository testResultRepository;
    private final TestAssertionRepository testAssertionRepository;
    private final RuntimeTraceRepository runtimeTraceRepository;
    private final ReviewRecordRepository reviewRecordRepository;
    private final KnowledgeClaimRepository knowledgeClaimRepository;
    private final GapTaskRepository gapTaskRepository;
    private final MigrationRiskRepository migrationRiskRepository;

    /** 进度缓存 key 前缀 */
    private static final String PROGRESS_KEY = "scan:progress:";
    /** 运行中进度缓存 TTL（短，吸收高频轮询同时不长时间陈旧） */
    private static final Duration RUNNING_TTL = Duration.ofSeconds(3);
    /** 终态进度缓存 TTL（长，结果不再变化） */
    private static final Duration TERMINAL_TTL = Duration.ofMinutes(30);

    /** Self-injection for @Async to work on internal calls */
    @Lazy
    @Autowired
    private ScanVersionService self;

    public ScanVersionService(ScanTaskRepository scanTaskRepository,
                              ScanVersionRepository scanVersionRepository,
                              CacheService cacheService,
                              GraphCacheInvalidator graphCacheInvalidator,
                              Neo4jGraphDao neo4jGraphDao,
                              GraphNodeRepository graphNodeRepository,
                              GraphEdgeRepository graphEdgeRepository,
                              FactRepository factRepository,
                              EvidenceRepository evidenceRepository,
                              NodeEvidenceRepository nodeEvidenceRepository,
                              EdgeEvidenceRepository edgeEvidenceRepository,
                              DocumentRepository documentRepository,
                              DocChunkRepository docChunkRepository,
                              TestCaseRepository testCaseRepository,
                              TestRunRepository testRunRepository,
                              TestResultRepository testResultRepository,
                              TestAssertionRepository testAssertionRepository,
                              RuntimeTraceRepository runtimeTraceRepository,
                              ReviewRecordRepository reviewRecordRepository,
                              KnowledgeClaimRepository knowledgeClaimRepository,
                              GapTaskRepository gapTaskRepository,
                              MigrationRiskRepository migrationRiskRepository) {
        this.scanTaskRepository = scanTaskRepository;
        this.scanVersionRepository = scanVersionRepository;
        this.cacheService = cacheService;
        this.graphCacheInvalidator = graphCacheInvalidator;
        this.neo4jGraphDao = neo4jGraphDao;
        this.graphNodeRepository = graphNodeRepository;
        this.graphEdgeRepository = graphEdgeRepository;
        this.factRepository = factRepository;
        this.evidenceRepository = evidenceRepository;
        this.nodeEvidenceRepository = nodeEvidenceRepository;
        this.edgeEvidenceRepository = edgeEvidenceRepository;
        this.documentRepository = documentRepository;
        this.docChunkRepository = docChunkRepository;
        this.testCaseRepository = testCaseRepository;
        this.testRunRepository = testRunRepository;
        this.testResultRepository = testResultRepository;
        this.testAssertionRepository = testAssertionRepository;
        this.runtimeTraceRepository = runtimeTraceRepository;
        this.reviewRecordRepository = reviewRecordRepository;
        this.knowledgeClaimRepository = knowledgeClaimRepository;
        this.gapTaskRepository = gapTaskRepository;
        this.migrationRiskRepository = migrationRiskRepository;
    }

    /** 版本号日期格式 */
    private static final DateTimeFormatter VERSION_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 生成不易重复的版本号：scan-YYYYMMDD-HHmmss-XXXX
     */
    private boolean isCompletedTaskStatus(String status) {
        return "SUCCESS".equals(status)
                || "WARNING".equals(status)
                || "SKIPPED".equals(status);
    }

    /** AI 阶段聚合结果：把所有 AI_* 子任务归纳为 AI_ORCHESTRATION 阶段的单一状态。 */
    private static final class AiPhaseAggregate {
        String status;
        int total;
        int completed;
        LocalDateTime startedAt;
        LocalDateTime finishedAt;
    }

    /**
     * 聚合所有 AI_* 子任务（AI_DOC_EXTRACT / AI_CODE_EXTRACT / AI_FEATURE_MAPPING 等），
     * 归纳为 AI_ORCHESTRATION 阶段的整体状态。
     * <ul>
     *   <li>任一子任务 RUNNING → 阶段 RUNNING</li>
     *   <li>否则任一 FAILED → 阶段 FAILED</li>
     *   <li>否则全部为完成态（SUCCESS/WARNING/SKIPPED）→ 任一 WARNING 则 WARNING，全 SKIPPED 则 SKIPPED，否则 SUCCESS</li>
     *   <li>否则（存在 PENDING 等）→ RUNNING</li>
     * </ul>
     * @return 无任何 AI_* 子任务时返回 null（由调用方回退到 PENDING 展示）
     */
    private AiPhaseAggregate aggregateAiSubtasks(List<ScanTask> tasks) {
        List<ScanTask> aiTasks = new ArrayList<>();
        for (ScanTask t : tasks) {
            String type = t.getTaskType();
            if (type != null && type.startsWith("AI_") && !"AI_ORCHESTRATION".equals(type)) {
                aiTasks.add(t);
            }
        }
        if (aiTasks.isEmpty()) {
            return null;
        }

        boolean anyRunning = false;
        boolean anyFailed = false;
        boolean anyWarning = false;
        boolean allSkipped = true;
        boolean allTerminal = true;
        int completed = 0;
        LocalDateTime minStarted = null;
        LocalDateTime maxFinished = null;

        for (ScanTask t : aiTasks) {
            String s = t.getTaskStatus();
            if ("RUNNING".equals(s)) anyRunning = true;
            if ("FAILED".equals(s)) anyFailed = true;
            if ("WARNING".equals(s)) anyWarning = true;
            if (!"SKIPPED".equals(s)) allSkipped = false;
            if (isCompletedTaskStatus(s)) {
                completed++;
            } else {
                allTerminal = false;
            }
            if (t.getStartedAt() != null && (minStarted == null || t.getStartedAt().isBefore(minStarted))) {
                minStarted = t.getStartedAt();
            }
            if (t.getFinishedAt() != null && (maxFinished == null || t.getFinishedAt().isAfter(maxFinished))) {
                maxFinished = t.getFinishedAt();
            }
        }

        AiPhaseAggregate agg = new AiPhaseAggregate();
        agg.total = aiTasks.size();
        agg.completed = completed;
        agg.startedAt = minStarted;
        if (anyRunning) {
            agg.status = "RUNNING";
        } else if (anyFailed) {
            agg.status = "FAILED";
        } else if (allTerminal) {
            agg.status = allSkipped ? "SKIPPED" : (anyWarning ? "WARNING" : "SUCCESS");
            agg.finishedAt = maxFinished;
        } else {
            // 存在 PENDING 等未开始子任务，视为进行中
            agg.status = "RUNNING";
        }
        return agg;
    }

    private static String generateVersionNo() {
        String datePart = LocalDateTime.now().format(VERSION_DATE_FMT);
        String randPart = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
        return "scan-" + datePart + "-" + randPart;
    }

    /**
     * 创建扫描版本
     */
    @Transactional
    public ScanVersion createScanVersion(String projectId, CreateScanVersionRequest request) {
        // 版本号自动生成（空时兜底）
        String versionNo = request.getVersionNo();
        if (versionNo == null || versionNo.isBlank()) {
            versionNo = generateVersionNo();
        }

        // 检查同项目下版本号是否已存在
        LambdaQueryWrapper<ScanVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ScanVersion::getProjectId, projectId);
        wrapper.eq(ScanVersion::getVersionNo, versionNo);
        if (scanVersionRepository.selectCount(wrapper) > 0) {
            // 如果是自动生成的仍然碰撞（极端情况），重试一次
            if (request.getVersionNo() == null || request.getVersionNo().isBlank()) {
                versionNo = generateVersionNo() + "-r";
                wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(ScanVersion::getProjectId, projectId);
                wrapper.eq(ScanVersion::getVersionNo, versionNo);
                if (scanVersionRepository.selectCount(wrapper) > 0) {
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "版本号生成冲突，请重试");
                }
            } else {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "扫描版本已存在: " + request.getVersionNo());
            }
        }

        ScanVersion version = new ScanVersion();
        version.setProjectId(projectId);
        version.setVersionNo(versionNo);
        version.setBranchName(request.getBranchName());
        version.setCommitId(request.getCommitId());
        version.setScanScope(request.getScanScope());
        version.setScanStatus("CREATED");
        version.setCreatedAt(LocalDateTime.now());
        version.setUpdatedAt(LocalDateTime.now());

        scanVersionRepository.insert(version);
        return version;
    }

    /**
     * 获取扫描进度（缓存优先：减轻前端高频轮询对 DB 的压力）。
     * 运行中以短 TTL 缓存吸收轮询峰值，终态以长 TTL 缓存。
     * <p>
     * 返回增强的进度信息：各阶段总项数/已处理数、当前处理项、预估剩余时间、
     * 阶段顺序与当前阶段索引。
     * </p>
     * <p>
     * ETA 算法改进：
     * 1. 浮点除法替代整数除法，慢速场景不再得 0
     * 2. 最小样本保护：处理不到 3 项时显示"计算中"
     * 3. 历史基准：利用同项目已完成扫描的平均每项耗时
     * 4. 混合加权：实时速率与历史基准按样本量加权
     * 5. 后续阶段 ETA：历史均值替代固定 30s 兜底
     * </p>
     */
    public ScanProgressResponse getScanProgress(String versionId) {
        String cacheKey = PROGRESS_KEY + versionId;
        ScanProgressResponse cached = cacheService.get(cacheKey, ScanProgressResponse.class);
        if (cached != null) {
            return cached;
        }

        ScanVersion version = scanVersionRepository.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("扫描版本不存在: " + versionId);
        }

        LambdaQueryWrapper<ScanTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ScanTask::getVersionId, versionId);
        List<ScanTask> tasks = scanTaskRepository.selectList(wrapper);

        String projectId = version.getProjectId();

        // 构建所有阶段的完整列表（含 PENDING 阶段）
        List<ScanPhase> allPhases = ScanPhaseRegistry.getAllPhases();
        // 按 taskType 索引已执行的任务
        Map<String, ScanTask> taskByType = new LinkedHashMap<>();
        for (ScanTask task : tasks) {
            taskByType.put(task.getTaskType(), task);
        }

        List<ScanProgressResponse.TaskProgress> taskProgressList = new ArrayList<>();
        int totalTasks = tasks.size();
        int completedTasks = 0;
        int currentPhaseIndex = -1;

        for (ScanPhase phase : allPhases) {
            ScanTask task = taskByType.get(phase.getTaskType());
            ScanProgressResponse.TaskProgress tp = new ScanProgressResponse.TaskProgress();
            tp.setTaskType(phase.getTaskType());
            tp.setPhaseName(phase.getPhaseName());

            // AI_ORCHESTRATION 阶段特殊处理：AI 实际运行时不会创建 task_type=AI_ORCHESTRATION 的任务，
            // 而是创建 AI_DOC_EXTRACT / AI_CODE_EXTRACT / AI_FEATURE_MAPPING 等子任务。
            // 若无直接的 AI_ORCHESTRATION 任务（仅 enableAi=false 跳过时才有），则聚合这些子任务的状态，
            // 避免该阶段在 AI 已成功运行的情况下仍显示为 PENDING（"未完成"）。
            if (task == null && "AI_ORCHESTRATION".equals(phase.getTaskType())) {
                AiPhaseAggregate agg = aggregateAiSubtasks(tasks);
                if (agg != null) {
                    tp.setStatus(agg.status);
                    tp.setFactCount(0);
                    tp.setTotalItems(agg.total);
                    tp.setProcessedItems(agg.completed);
                    tp.setStartedAt(agg.startedAt);
                    tp.setFinishedAt(agg.finishedAt);
                    tp.setEstimatedSecondsRemaining(-1L);
                    if (isCompletedTaskStatus(agg.status)) {
                        completedTasks++;
                    }
                    if ("RUNNING".equals(agg.status) && currentPhaseIndex < 0) {
                        currentPhaseIndex = phase.getOrder();
                    }
                    taskProgressList.add(tp);
                    continue;
                } else {
                    // 无 AI 子任务：若版本已终态则标记为 SKIPPED，否则保持 PENDING
                    String versionStatus = version.getScanStatus();
                    if ("SUCCESS".equals(versionStatus) || "FAILED".equals(versionStatus) || "CANCELLED".equals(versionStatus)) {
                        tp.setStatus("SKIPPED");
                        tp.setFactCount(0);
                        tp.setTotalItems(0);
                        tp.setProcessedItems(0);
                        tp.setEstimatedSecondsRemaining(-1L);
                        completedTasks++;
                        taskProgressList.add(tp);
                        continue;
                    }
                }
            }

            if (task != null) {
                tp.setStatus(task.getTaskStatus());
                tp.setFactCount(0);
                tp.setTotalItems(task.getTotalItems());
                tp.setProcessedItems(task.getProcessedItems());
                tp.setCurrentItem(task.getCurrentItem());
                tp.setStartedAt(task.getStartedAt());
                tp.setFinishedAt(task.getFinishedAt());

                // 计算本阶段 ETA（混合算法：实时速率 + 历史基准）
                if ("RUNNING".equals(task.getTaskStatus())
                        && task.getTotalItems() != null && task.getTotalItems() > 0
                        && task.getProcessedItems() != null && task.getProcessedItems() > 0
                        && task.getStartedAt() != null) {
                    tp.setEstimatedSecondsRemaining(
                            computeBlendedEta(projectId, task));
                } else {
                    tp.setEstimatedSecondsRemaining(-1L);
                }

                if (isCompletedTaskStatus(task.getTaskStatus())) {
                    completedTasks++;
                }
                if ("RUNNING".equals(task.getTaskStatus()) && currentPhaseIndex < 0) {
                    currentPhaseIndex = phase.getOrder();
                }
            } else {
                tp.setStatus("PENDING");
                tp.setFactCount(0);
                tp.setTotalItems(0);
                tp.setProcessedItems(0);
                // 第一个 PENDING 阶段视为下一个待执行
                if (currentPhaseIndex < 0 && !taskProgressList.isEmpty()) {
                    currentPhaseIndex = phase.getOrder();
                }
            }
            taskProgressList.add(tp);
        }

        // 如果全部完成，currentPhaseIndex = 最后一个阶段
        if (currentPhaseIndex < 0 && !taskProgressList.isEmpty()) {
            currentPhaseIndex = taskProgressList.size() - 1;
        }

        int progress = totalTasks > 0 ? (completedTasks * 100 / totalTasks) : 0;
        // 版本终态强制收敛进度：SUCCESS → 100%；FAILED/CANCELLED/PAUSED 保持当前计算值
        if ("SUCCESS".equals(version.getScanStatus())) {
            progress = 100;
        }

        // 整体 ETA：取当前运行阶段的预估剩余时间，加上后续阶段的历史均值（无历史时兜底 30s）
        Long overallEta = -1L;
        if (currentPhaseIndex >= 0 && currentPhaseIndex < taskProgressList.size()) {
            ScanProgressResponse.TaskProgress currentTp = taskProgressList.get(currentPhaseIndex);
            if (currentTp.getEstimatedSecondsRemaining() != null && currentTp.getEstimatedSecondsRemaining() > 0) {
                overallEta = currentTp.getEstimatedSecondsRemaining();
                // 加上后续未开始阶段的历史均值预估
                for (int i = currentPhaseIndex + 1; i < taskProgressList.size(); i++) {
                    ScanProgressResponse.TaskProgress tp = taskProgressList.get(i);
                    if ("PENDING".equals(tp.getStatus())) {
                        Double avgDuration = getHistoricalPhaseDuration(projectId, tp.getTaskType());
                        overallEta += avgDuration != null ? Math.round(avgDuration) : 30;
                    }
                }
            }
        }

        ScanProgressResponse response = new ScanProgressResponse(
                versionId, version.getScanStatus(), progress, taskProgressList,
                allPhases, currentPhaseIndex, overallEta);

        boolean terminal = "SUCCESS".equals(version.getScanStatus())
                || "FAILED".equals(version.getScanStatus())
                || "CANCELLED".equals(version.getScanStatus());
        cacheService.put(cacheKey, response, terminal ? TERMINAL_TTL : RUNNING_TTL);
        return response;
    }

    // ==================== ETA 预估算法 ====================

    /** 最小样本数：处理不到此数量时不显示实时 ETA（数据不稳定） */
    private static final int MIN_SAMPLE_FOR_REALTIME = 3;
    /** 历史基准查询的最大样本数 */
    private static final int HISTORY_SAMPLE_LIMIT = 5;
    /** 后续阶段无历史时的默认兜底时间（秒） */
    private static final long DEFAULT_PHASE_FALLBACK_SECONDS = 30;

    /**
     * 混合 ETA 计算：实时速率 + 历史基准加权。
     * <p>
     * 权重策略：已处理项数越多，越信任实时数据。
     * processed=3 → 实时权重 0.12, processed=10 → 0.4, processed=25+ → 0.9
     * </p>
     *
     * @param projectId 项目 ID（用于查历史基准）
     * @param task      当前运行中的子任务
     * @return 预估剩余秒数，-1 表示无法预估
     */
    private long computeBlendedEta(String projectId, ScanTask task) {
        double elapsedSeconds = Duration.between(task.getStartedAt(), LocalDateTime.now()).toMillis() / 1000.0;
        int processed = task.getProcessedItems();
        int remaining = task.getTotalItems() - processed;

        // 实时速率（浮点除法，解决慢速场景）
        double currentRate = elapsedSeconds > 0 ? processed / elapsedSeconds : 0; // items/sec
        long realtimeEta = currentRate > 0.001 ? Math.round(remaining / currentRate) : -1;

        // 历史基准
        Double histSecPerItem = getHistoricalSecondsPerItem(projectId, task.getTaskType());
        long histEta = histSecPerItem != null ? Math.round(remaining * histSecPerItem) : -1;

        // 混合加权
        if (processed < MIN_SAMPLE_FOR_REALTIME) {
            // 样本不足：优先用历史，无历史则不显示
            return histEta > 0 ? histEta : -1;
        }

        if (realtimeEta > 0 && histEta > 0) {
            // 两者都有：按样本量加权
            double realtimeWeight = Math.min(0.9, processed / 25.0);
            double histWeight = 1.0 - realtimeWeight;
            return Math.round(realtimeEta * realtimeWeight + histEta * histWeight);
        } else if (realtimeEta > 0) {
            return realtimeEta;
        } else if (histEta > 0) {
            return histEta;
        }
        return -1;
    }

    /**
     * 查询同项目同类型历史任务的平均每项耗时（秒）。
     * 取最近 N 次成功完成的任务，计算总耗时/总项数。
     *
     * @param projectId 项目 ID
     * @param taskType  阶段类型（如 ADAPTER_SCAN）
     * @return 平均每项耗时（秒），无历史数据时返回 null
     */
    private Double getHistoricalSecondsPerItem(String projectId, String taskType) {
        try {
            LambdaQueryWrapper<ScanTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ScanTask::getProjectId, projectId)
                    .eq(ScanTask::getTaskType, taskType)
                    .eq(ScanTask::getTaskStatus, "SUCCESS")
                    .isNotNull(ScanTask::getFinishedAt)
                    .isNotNull(ScanTask::getStartedAt)
                    .orderByDesc(ScanTask::getCreatedAt)
                    .last("LIMIT " + HISTORY_SAMPLE_LIMIT);
            List<ScanTask> history = scanTaskRepository.selectList(wrapper);

            double totalSec = 0;
            int totalItems = 0;
            for (ScanTask t : history) {
                long sec = Duration.between(t.getStartedAt(), t.getFinishedAt()).getSeconds();
                int items = t.getProcessedItems() != null ? t.getProcessedItems() : 0;
                if (items > 0 && sec > 0) {
                    totalSec += sec;
                    totalItems += items;
                }
            }
            return totalItems > 0 ? totalSec / totalItems : null;
        } catch (Exception e) {
            log.debug("Failed to compute historical seconds per item for {}/{}: {}",
                    projectId, taskType, e.getMessage());
            return null;
        }
    }

    /**
     * 查询同项目某阶段历史平均总耗时（秒）。
     * 用于后续 PENDING 阶段的粗略预估。
     *
     * @param projectId 项目 ID
     * @param taskType  阶段类型
     * @return 平均总耗时（秒），无历史数据时返回 null
     */
    private Double getHistoricalPhaseDuration(String projectId, String taskType) {
        try {
            LambdaQueryWrapper<ScanTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ScanTask::getProjectId, projectId)
                    .eq(ScanTask::getTaskType, taskType)
                    .eq(ScanTask::getTaskStatus, "SUCCESS")
                    .isNotNull(ScanTask::getFinishedAt)
                    .isNotNull(ScanTask::getStartedAt)
                    .orderByDesc(ScanTask::getCreatedAt)
                    .last("LIMIT " + HISTORY_SAMPLE_LIMIT);
            List<ScanTask> history = scanTaskRepository.selectList(wrapper);

            if (history.isEmpty()) {
                return null;
            }
            return history.stream()
                    .mapToDouble(t -> Duration.between(t.getStartedAt(), t.getFinishedAt()).getSeconds())
                    .average()
                    .orElse(DEFAULT_PHASE_FALLBACK_SECONDS);
        } catch (Exception e) {
            log.debug("Failed to compute historical phase duration for {}/{}: {}",
                    projectId, taskType, e.getMessage());
            return null;
        }
    }

    /**
     * 更新扫描状态
     */
    @Transactional
    public void updateScanStatus(String versionId, String status) {
        ScanVersion version = scanVersionRepository.selectById(versionId);
        if (version != null) {
            version.setScanStatus(status);
            if ("RUNNING".equals(status)) {
                version.setStartedAt(LocalDateTime.now());
            } else if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
                version.setFinishedAt(LocalDateTime.now());
            }
            version.setUpdatedAt(LocalDateTime.now());
            scanVersionRepository.updateById(version);
            cacheService.evict(PROGRESS_KEY + versionId);
        }
    }

    /**
     * 删除扫描版本及其关联的所有数据（物理删除）。
     * <p>优化：批量删除扫描任务、Neo4j 异步清理、PG 表并行删除。</p>
     * <p>S10 修复：移除 @Transactional，因为 CompletableFuture.runAsync() 在独立线程执行，
     * 无法共享事务上下文。改为"先删关联数据（并行），最后删版本记录"的弱一致性模型。</p>
     */
    public void deleteScanVersion(String versionId) {
        ScanVersion version = scanVersionRepository.selectById(versionId);
        if (version == null) {
            return;
        }
        String projectId = version.getProjectId();

        // 1. 批量删除扫描任务（原 N+1 → 1 次 SQL）
        LambdaQueryWrapper<ScanTask> taskWrapper = new LambdaQueryWrapper<>();
        taskWrapper.eq(ScanTask::getVersionId, versionId);
        scanTaskRepository.delete(taskWrapper);

        // 2. Neo4j 子图异步删除 — M14 修复：移到 PG 删除全部完成后再执行，
        //    避免 PG 删除失败时 Neo4j 图谱已被删除导致数据不一致。
        //    （原代码在此处直接 self.deleteNeo4jGraphAsync，与 PG 删除并行执行）

        // 3. PG 图谱数据并行删除（虚拟线程，I/O 密集）
        CompletableFuture<Void> graphFuture = CompletableFuture.runAsync(() -> {
            try {
                graphNodeRepository.delete(new QueryWrapper<GraphNode>().eq("version_id", versionId));
                graphEdgeRepository.delete(new QueryWrapper<GraphEdge>().eq("version_id", versionId));
            } catch (Exception e) {
                log.warn("S10: 删除版本级PG图谱失败: versionId={}, error={}", versionId, e.getMessage());
            }
        });

        // 4. 事实删除
        CompletableFuture<Void> factFuture = CompletableFuture.runAsync(() -> {
            try {
                factRepository.delete(new QueryWrapper<Fact>().eq("version_id", versionId));
            } catch (Exception e) {
                log.warn("S10: 删除事实数据失败: versionId={}, error={}", versionId, e.getMessage());
            }
        });

        // 5. 证据删除（先查 ID 再批量删，避免字符串拼接子查询）
        CompletableFuture<Void> evidenceFuture = CompletableFuture.runAsync(() -> {
            try {
                List<Evidence> evidenceList = evidenceRepository.selectList(
                        new QueryWrapper<Evidence>().select("id").eq("version_id", versionId));
                if (!evidenceList.isEmpty()) {
                    List<String> evidenceIds = evidenceList.stream().map(Evidence::getId).toList();
                    nodeEvidenceRepository.delete(new QueryWrapper<NodeEvidence>().in("evidence_id", evidenceIds));
                    edgeEvidenceRepository.delete(new QueryWrapper<EdgeEvidence>().in("evidence_id", evidenceIds));
                    evidenceRepository.delete(new QueryWrapper<Evidence>().eq("version_id", versionId));
                }
            } catch (Exception e) {
                log.warn("S10: 删除证据数据失败: versionId={}, error={}", versionId, e.getMessage());
            }
        });

        // 6. 文档和向量数据保留（删除扫描版本不清理文档和向量，文档属于项目级别资源）

        // 7. 测试数据删除
        CompletableFuture<Void> testFuture = CompletableFuture.runAsync(() -> {
            try {
                List<TestCase> testCases = testCaseRepository.selectList(
                        new QueryWrapper<TestCase>().select("id").eq("version_id", versionId));
                if (!testCases.isEmpty()) {
                    List<String> testCaseIds = testCases.stream().map(TestCase::getId).toList();
                    testAssertionRepository.delete(new QueryWrapper<TestAssertion>().in("test_case_id", testCaseIds));
                }
                testResultRepository.delete(new QueryWrapper<TestResult>().eq("version_id", versionId));
                testRunRepository.delete(new QueryWrapper<TestRun>().eq("version_id", versionId));
                testCaseRepository.delete(new QueryWrapper<TestCase>().eq("version_id", versionId));
            } catch (Exception e) {
                log.warn("S10: 删除测试数据失败: versionId={}, error={}", versionId, e.getMessage());
            }
        });

        // 8. 审计/知识/缺口/风险删除
        CompletableFuture<Void> auditFuture = CompletableFuture.runAsync(() -> {
            try {
                runtimeTraceRepository.delete(new QueryWrapper<RuntimeTrace>().eq("version_id", versionId));
                reviewRecordRepository.delete(new QueryWrapper<ReviewRecord>().eq("version_id", versionId));
                knowledgeClaimRepository.delete(new QueryWrapper<KnowledgeClaim>().eq("version_id", versionId));
                gapTaskRepository.delete(new QueryWrapper<GapTask>().eq("version_id", versionId));
                migrationRiskRepository.delete(new QueryWrapper<MigrationRisk>().eq("version_id", versionId));
            } catch (Exception e) {
                log.warn("S10: 删除审计/知识/缺口/风险失败: versionId={}, error={}", versionId, e.getMessage());
            }
        });

        // 等待所有并行删除完成
        CompletableFuture.allOf(graphFuture, factFuture, evidenceFuture,
                testFuture, auditFuture).join();

        // M14 修复：PG 删除全部完成后，再执行 Neo4j 异步删除，避免 PG 回滚时 Neo4j 已被删除
        self.deleteNeo4jGraphAsync(projectId, versionId);

        // 9. 删除版本本身前，彻底清除所有相关缓存
        cacheService.evict(PROGRESS_KEY + versionId);
        graphCacheInvalidator.invalidateVersion(versionId);
        // M15 修复：精确清除缓存，避免 evictByPrefix("graph:node:") 影响其他版本
        cacheService.evictByPrefix("graph:node:" + projectId + ":" + versionId);

        scanVersionRepository.deleteById(versionId);
    }

    /**
     * 异步删除 Neo4j 子图（不影响 HTTP 响应时间）。
     */
    @Async("taskExecutor")
    public void deleteNeo4jGraphAsync(String projectId, String versionId) {
        try {
            neo4jGraphDao.deleteGraph(projectId, versionId);
        } catch (Exception e) {
            log.warn("Neo4j 子图异步删除失败：projectId={}, versionId={}, err={}",
                    projectId, versionId, e.getMessage(), e);
        }
    }

    /**
     * 获取扫描日志
     * 查询指定扫描版本的所有扫描任务，返回格式化的日志列表
     */
    public List<Map<String, Object>> getScanLogs(String versionId) {
        List<ScanTask> tasks = scanTaskRepository.lambdaQuery()
                .eq(ScanTask::getVersionId, versionId)
                .orderByAsc(ScanTask::getCreatedAt)
                .list();

        List<Map<String, Object>> logs = new ArrayList<>();
        for (ScanTask task : tasks) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("time", task.getCreatedAt() != null ? task.getCreatedAt().toString() : "-");
            entry.put("type", "FAILED".equals(task.getTaskStatus()) ? "ERROR" : "INFO");
            entry.put("message", task.getTaskName() != null
                    ? "任务 [" + task.getTaskName() + "] 状态: " + task.getTaskStatus()
                    : "扫描子任务状态: " + task.getTaskStatus());
            if (task.getErrorMessage() != null) {
                entry.put("error", task.getErrorMessage());
            }
            logs.add(entry);
        }

        if (logs.isEmpty()) {
            logs.add(Map.of(
                    "time", LocalDateTime.now().toString(),
                    "type", "INFO",
                    "message", "暂无扫描日志记录"
            ));
        }

        return logs;
    }
}
