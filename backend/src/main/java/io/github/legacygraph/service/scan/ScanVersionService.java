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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class ScanVersionService extends ServiceImpl<ScanVersionRepository, ScanVersion> {

    private final ScanTaskRepository scanTaskRepository;
    private final ScanVersionRepository scanVersionRepository;
    private final CacheService cacheService;
    private final GraphCacheInvalidator graphCacheInvalidator;
    private final Neo4jGraphDao neo4jGraphDao;
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

    /**
     * 历史耗时统计的跨请求缓存：key = projectId + ":" + taskType，value = 查询结果。
     * <p>
     * 单次 progress 请求会调用 getHistoricalPhaseDuration / getHistoricalSecondsPerItem 共 25-42 次，
     * 查询条件固定（projectId + taskType + SUCCESS），结果短期内不变。
     * 此缓存使相同 key 的查询在一次请求内只执行一次 DB 查询，后续命中缓存。
     * TTL 60s 平衡了跨请求复用与数据新鲜度。
     * </p>
     */
    private final Map<String, Double> historicalDurationCache = new ConcurrentHashMap<>();
    private final Map<String, Double> historicalSecPerItemCache = new ConcurrentHashMap<>();
    private static final Duration HISTORICAL_CACHE_TTL = Duration.ofSeconds(60);
    private volatile long historicalCacheClearedAt = 0;
    /** 哨兵值：表示"已查过 DB 但无历史数据"，避免 null 不缓存导致每次重复空查 DB */
    private static final double NO_HISTORY_SENTINEL = -1.0;

    /** Self-injection for @Async to work on internal calls */
    @Lazy
    @Autowired
    private ScanVersionService self;

    public ScanVersionService(ScanTaskRepository scanTaskRepository,
                              ScanVersionRepository scanVersionRepository,
                              CacheService cacheService,
                              GraphCacheInvalidator graphCacheInvalidator,
                              Neo4jGraphDao neo4jGraphDao,
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
        List<ScanTask> aiTasks;
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
        int totalItemsSum = 0;
        int processedItemsSum = 0;
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
            int tTotal = t.getTotalItems() != null ? t.getTotalItems() : 0;
            int tProcessed = t.getProcessedItems() != null ? t.getProcessedItems() : 0;
            if (tTotal > 0) {
                totalItemsSum += tTotal;
                // 每个子任务的 processedItems 不超过其 totalItems，避免进度超过100%
                processedItemsSum += Math.min(tProcessed, tTotal);
            } else if (isCompletedTaskStatus(s)) {
                // 无 totalItems 但已完成的任务，计为1项
                totalItemsSum += 1;
                processedItemsSum += 1;
            }
        }

        AiPhaseAggregate agg = new AiPhaseAggregate();
        agg.aiTasks = aiTasks;
        agg.total = totalItemsSum > 0 ? totalItemsSum : aiTasks.size();
        agg.completed = processedItemsSum > 0 ? Math.min(processedItemsSum, agg.total) : completed;
        agg.startedAt = minStarted;
        if (anyRunning) {
            agg.status = "RUNNING";
        } else if (anyFailed) {
            agg.status = "FAILED";
        } else if (allTerminal) {
            agg.status = allSkipped ? "SKIPPED" : (anyWarning ? "WARNING" : "SUCCESS");
            agg.finishedAt = maxFinished;
        } else {
            agg.status = "RUNNING";
        }
        return agg;
    }

    /**
     * 计算 AI 编排阶段（AI_ORCHESTRATION，聚合多个 AI_* 子任务）的预计剩余时间。
     *
     * <p>AI 阶段运行时拆为 AI_DOC_EXTRACT / AI_CODE_EXTRACT / AI_FEATURE_MAPPING 等子任务，
     * 非单个 task，原聚合路径硬编码 -1 导致前端"AI 智能分析"不显示 ETA。此处按子任务状态汇总：
     * <ul>
     *   <li>已完成/失败子任务：不计剩余</li>
     *   <li>运行中子任务：有 total/processed items 时用 {@link #computeBlendedEta}（实时+历史混合）；
     *       无 items 时退化到 max(0, 历史均时 - 已耗时)</li>
     *   <li>未开始子任务：历史均时（无历史兜底 {@link #DEFAULT_PHASE_FALLBACK_SECONDS}）</li>
     * </ul>
     * @return 剩余秒数；无任何可估因子时返回 -1（前端隐藏）
     */
    private long computeAiOrchestrationEta(String projectId, List<ScanTask> tasks, AiPhaseAggregate agg) {
        boolean hasEstimate = false;
        LocalDateTime now = LocalDateTime.now();
        List<ScanTask> aiTasks = agg != null && agg.aiTasks != null ? agg.aiTasks : new ArrayList<>();
        if (aiTasks.isEmpty()) {
            for (ScanTask t : tasks) {
                String type = t.getTaskType();
                if (type != null && type.startsWith("AI_") && !"AI_ORCHESTRATION".equals(type)) {
                    aiTasks.add(t);
                }
            }
        }

        List<Long> runningEtas = new ArrayList<>();
        List<Long> pendingEtas = new ArrayList<>();

        for (ScanTask t : aiTasks) {
            String type = t.getTaskType();
            String s = t.getTaskStatus();
            if (isCompletedTaskStatus(s) || "FAILED".equals(s)) {
                continue;
            }
            if ("RUNNING".equals(s) && t.getStartedAt() != null) {
                long taskEta;
                if (t.getTotalItems() != null && t.getTotalItems() > 0
                        && t.getProcessedItems() != null && t.getProcessedItems() > 0) {
                    taskEta = computeBlendedEta(projectId, t);
                } else {
                    long elapsed = Duration.between(t.getStartedAt(), now).getSeconds();
                    Double hist = getHistoricalPhaseDuration(projectId, type);
                    taskEta = hist != null ? Math.max(0, Math.round(hist) - elapsed) : DEFAULT_PHASE_FALLBACK_SECONDS * 2;
                }
                if (taskEta > 0) {
                    runningEtas.add(taskEta);
                    hasEstimate = true;
                }
            } else {
                Double hist = getHistoricalPhaseDuration(projectId, type);
                long taskEta = hist != null ? Math.round(hist) : DEFAULT_PHASE_FALLBACK_SECONDS * 2;
                pendingEtas.add(taskEta);
                hasEstimate = true;
            }
        }

        long maxRunningEta = runningEtas.isEmpty() ? 0 : runningEtas.stream().max(Long::compareTo).get();
        long maxPendingEta = pendingEtas.isEmpty() ? 0 : pendingEtas.stream().max(Long::compareTo).get();

        return hasEstimate ? (maxRunningEta + maxPendingEta) : -1L;
    }

    // ==================== AI 子阶段构建（展开显示） ====================

    /**
     * 构建 AI 子阶段列表，用于在 AI_ORCHESTRATION 阶段下方展开显示各子环节。
     * <p>
     * 合并并行步骤（AI_DOC_EXTRACT + AI_CODE_EXTRACT → "文档与代码事实抽取"），
     * 其余串行步骤各自独立展示。共 7 个子阶段，每个带状态、进度条、耗时和 ETA。
     *
     * @return 子阶段列表；无任何 AI_* 子任务时返回 null
     */
    private List<ScanProgressResponse.TaskProgress> buildAiSubPhases(String projectId, List<ScanTask> tasks) {
        // 同 taskType 多条时保留最新的一条，避免重试场景覆盖
        Map<String, ScanTask> aiTaskMap = new LinkedHashMap<>();
        for (ScanTask t : tasks) {
            String type = t.getTaskType();
            if (type != null && type.startsWith("AI_") && !"AI_ORCHESTRATION".equals(type)) {
                ScanTask existing = aiTaskMap.get(type);
                if (existing == null || (t.getCreatedAt() != null
                        && (existing.getCreatedAt() == null || t.getCreatedAt().isAfter(existing.getCreatedAt())))) {
                    aiTaskMap.put(type, t);
                }
            }
        }
        if (aiTaskMap.isEmpty()) {
            return null;
        }

        List<ScanProgressResponse.TaskProgress> subPhases = new ArrayList<>();
        for (ScanPhaseRegistry.AiSubPhaseDef def : ScanPhaseRegistry.getAiSubPhaseDefs()) {
            List<ScanTask> matched = new ArrayList<>();
            for (String srcType : def.sourceTaskTypes) {
                ScanTask t = aiTaskMap.get(srcType);
                if (t != null) {
                    matched.add(t);
                }
            }
            if (matched.isEmpty()) {
                // 子环节尚未开始
                ScanProgressResponse.TaskProgress sp = new ScanProgressResponse.TaskProgress();
                sp.setTaskType(def.displayTaskType);
                sp.setPhaseName(def.displayName);
                sp.setStatus("PENDING");
                sp.setFactCount(0);
                sp.setTotalItems(0);
                sp.setProcessedItems(0);
                sp.setEstimatedSecondsRemaining(-1L);
                subPhases.add(sp);
                continue;
            }
            if (matched.size() == 1) {
                subPhases.add(buildSubPhaseFromTask(projectId, def.displayTaskType, def.displayName, matched.get(0)));
            } else {
                subPhases.add(buildMergedSubPhase(projectId, def.displayTaskType, def.displayName, matched));
            }
        }
        return subPhases;
    }

    /** 从单个 ScanTask 构建子阶段 TaskProgress */
    private ScanProgressResponse.TaskProgress buildSubPhaseFromTask(
            String projectId, String taskType, String phaseName, ScanTask t) {
        ScanProgressResponse.TaskProgress sp = new ScanProgressResponse.TaskProgress();
        sp.setTaskType(taskType);
        sp.setPhaseName(phaseName);
        sp.setStatus(t.getTaskStatus());
        sp.setFactCount(0);
        int total = t.getTotalItems() != null ? t.getTotalItems() : 0;
        int processed = t.getProcessedItems() != null ? t.getProcessedItems() : 0;
        sp.setTotalItems(total);
        sp.setProcessedItems(total > 0 ? Math.min(processed, total) : processed);
        sp.setCurrentItem(t.getCurrentItem());
        sp.setStartedAt(t.getStartedAt());
        sp.setFinishedAt(t.getFinishedAt());
        sp.setEstimatedSecondsRemaining(computeSubPhaseEta(projectId, t));
        return sp;
    }

    /** 从多个并行 ScanTask 合并构建子阶段 TaskProgress（如 DOC_EXTRACT + CODE_EXTRACT） */
    private ScanProgressResponse.TaskProgress buildMergedSubPhase(
            String projectId, String taskType, String phaseName, List<ScanTask> matched) {
        boolean anyRunning = false;
        boolean anyFailed = false;
        boolean anyWarning = false;
        boolean allSkipped = true;
        boolean allTerminal = true;
        int totalItemsSum = 0;
        int processedItemsSum = 0;
        LocalDateTime minStarted = null;
        LocalDateTime maxFinished = null;

        for (ScanTask t : matched) {
            String s = t.getTaskStatus();
            if ("RUNNING".equals(s)) anyRunning = true;
            if ("FAILED".equals(s)) anyFailed = true;
            if ("WARNING".equals(s)) anyWarning = true;
            if (!"SKIPPED".equals(s)) allSkipped = false;
            if (!isCompletedTaskStatus(s)) allTerminal = false;
            int tTotal = t.getTotalItems() != null ? t.getTotalItems() : 0;
            int tProcessed = t.getProcessedItems() != null ? t.getProcessedItems() : 0;
            if (tTotal > 0) {
                totalItemsSum += tTotal;
                processedItemsSum += Math.min(tProcessed, tTotal);
            } else if (isCompletedTaskStatus(s)) {
                totalItemsSum += 1;
                processedItemsSum += 1;
            }
            if (t.getStartedAt() != null && (minStarted == null || t.getStartedAt().isBefore(minStarted))) {
                minStarted = t.getStartedAt();
            }
            if (t.getFinishedAt() != null && (maxFinished == null || t.getFinishedAt().isAfter(maxFinished))) {
                maxFinished = t.getFinishedAt();
            }
        }

        String status;
        if (anyRunning) {
            status = "RUNNING";
        } else if (anyFailed) {
            status = "FAILED";
        } else if (allTerminal) {
            status = allSkipped ? "SKIPPED" : (anyWarning ? "WARNING" : "SUCCESS");
        } else {
            status = "RUNNING";
        }

        ScanProgressResponse.TaskProgress sp = new ScanProgressResponse.TaskProgress();
        sp.setTaskType(taskType);
        sp.setPhaseName(phaseName);
        sp.setStatus(status);
        sp.setFactCount(0);
        sp.setTotalItems(totalItemsSum);
        sp.setProcessedItems(totalItemsSum > 0 ? Math.min(processedItemsSum, totalItemsSum) : 0);
        sp.setStartedAt(minStarted);
        sp.setFinishedAt(maxFinished);
        // 并行执行：ETA 取各子任务 ETA 的最大值
        if ("RUNNING".equals(status)) {
            long maxEta = 0;
            boolean hasEstimate = false;
            for (ScanTask t : matched) {
                long eta = computeSubPhaseEta(projectId, t);
                if (eta > 0) {
                    hasEstimate = true;
                    maxEta = Math.max(maxEta, eta);
                }
            }
            sp.setEstimatedSecondsRemaining(hasEstimate ? maxEta : -1L);
        } else {
            sp.setEstimatedSecondsRemaining(-1L);
        }
        return sp;
    }

    /** 计算单个 AI 子任务的 ETA */
    private long computeSubPhaseEta(String projectId, ScanTask t) {
        String s = t.getTaskStatus();
        if (isCompletedTaskStatus(s) || "FAILED".equals(s)) {
            return -1L;
        }
        if ("RUNNING".equals(s) && t.getStartedAt() != null) {
            if (t.getTotalItems() != null && t.getTotalItems() > 0
                    && t.getProcessedItems() != null && t.getProcessedItems() > 0) {
                return computeBlendedEta(projectId, t);
            }
            long elapsed = Duration.between(t.getStartedAt(), LocalDateTime.now()).getSeconds();
            Double hist = getHistoricalPhaseDuration(projectId, t.getTaskType());
            return hist != null ? Math.max(0, Math.round(hist) - elapsed) : DEFAULT_PHASE_FALLBACK_SECONDS * 2;
        }
        // PENDING
        Double hist = getHistoricalPhaseDuration(projectId, t.getTaskType());
        return hist != null ? Math.round(hist) : DEFAULT_PHASE_FALLBACK_SECONDS * 2;
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

        // 清理过期的历史统计缓存（超过 TTL 的整批清理，避免逐 key 过期开销）
        long now = System.currentTimeMillis();
        if (now - historicalCacheClearedAt > HISTORICAL_CACHE_TTL.toMillis()) {
            historicalDurationCache.clear();
            historicalSecPerItemCache.clear();
            historicalCacheClearedAt = now;
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
        // 按 taskType 索引已执行的任务（同 taskType 多条时保留最新的一条，避免重试场景覆盖）
        Map<String, ScanTask> taskByType = new LinkedHashMap<>();
        for (ScanTask task : tasks) {
            ScanTask existing = taskByType.get(task.getTaskType());
            if (existing == null || (task.getCreatedAt() != null
                    && (existing.getCreatedAt() == null || task.getCreatedAt().isAfter(existing.getCreatedAt())))) {
                taskByType.put(task.getTaskType(), task);
            }
        }

        List<ScanProgressResponse.TaskProgress> taskProgressList = new ArrayList<>();
        int totalWeight = 0;
        int completedWeight = 0;
        int currentPhaseIndex = -1;

        for (ScanPhase phase : allPhases) {
            int phaseWeight = phase.getWeight();
            totalWeight += phaseWeight;

            ScanTask task = taskByType.get(phase.getTaskType());
            ScanProgressResponse.TaskProgress tp = new ScanProgressResponse.TaskProgress();
            tp.setTaskType(phase.getTaskType());
            tp.setPhaseName(phase.getPhaseName());

            // AI_ORCHESTRATION 阶段特殊处理：AI 实际运行时会创建 AI_DOC_EXTRACT / AI_CODE_EXTRACT / AI_FEATURE_MAPPING 等子任务。
            // 始终聚合这些子任务的状态，以正确显示进度条（totalItems/processedItems）。
            if ("AI_ORCHESTRATION".equals(phase.getTaskType())) {
                AiPhaseAggregate agg = aggregateAiSubtasks(tasks);
                if (agg != null) {
                    tp.setStatus(agg.status);
                    tp.setFactCount(0);
                    tp.setTotalItems(agg.total);
                    tp.setProcessedItems(agg.completed);
                    tp.setStartedAt(agg.startedAt);
                    tp.setFinishedAt(agg.finishedAt);
                    // AI 聚合阶段 RUNNING 时按子任务历史均时汇总 ETA；终态不显示
                    if ("RUNNING".equals(agg.status)) {
                        long eta = computeAiOrchestrationEta(projectId, tasks, agg);
                        if (eta < 0) {
                            Double histAiDuration = getHistoricalPhaseDuration(projectId, "AI_ORCHESTRATION");
                            if (histAiDuration != null) {
                                eta = Math.round(histAiDuration);
                            } else {
                                eta = DEFAULT_PHASE_FALLBACK_SECONDS * 5;
                            }
                        }
                        tp.setEstimatedSecondsRemaining(eta);
                    } else {
                        tp.setEstimatedSecondsRemaining(-1L);
                    }
                    if (isCompletedTaskStatus(agg.status)) {
                        completedWeight += phaseWeight;
                    }
                    if ("RUNNING".equals(agg.status) && currentPhaseIndex < 0) {
                        currentPhaseIndex = phase.getOrder();
                        int partialWeight = calculateRunningPhasePartialWeight(agg);
                        completedWeight += partialWeight;
                    }
                    // 展开显示 AI 内部子环节（合并并行步骤）
                    tp.setSubPhases(buildAiSubPhases(projectId, tasks));
                    taskProgressList.add(tp);
                    continue;
                } else {
                    if (task != null) {
                        tp.setStatus(task.getTaskStatus());
                        tp.setFactCount(0);
                        tp.setTotalItems(task.getTotalItems());
                        tp.setProcessedItems(task.getProcessedItems());
                        tp.setCurrentItem(task.getCurrentItem());
                        tp.setStartedAt(task.getStartedAt());
                        tp.setFinishedAt(task.getFinishedAt());
                        if ("RUNNING".equals(task.getTaskStatus())) {
                            long eta = computeAiOrchestrationEta(projectId, tasks, null);
                            if (eta < 0) {
                                Double histAiDuration = getHistoricalPhaseDuration(projectId, "AI_ORCHESTRATION");
                                if (histAiDuration != null) {
                                    eta = Math.round(histAiDuration);
                                } else {
                                    eta = DEFAULT_PHASE_FALLBACK_SECONDS * 5;
                                }
                            }
                            tp.setEstimatedSecondsRemaining(eta);
                        } else {
                            tp.setEstimatedSecondsRemaining(-1L);
                        }
                        if (isCompletedTaskStatus(task.getTaskStatus())) {
                            completedWeight += phaseWeight;
                        }
                        if ("RUNNING".equals(task.getTaskStatus()) && currentPhaseIndex < 0) {
                            currentPhaseIndex = phase.getOrder();
                        }
                        taskProgressList.add(tp);
                        continue;
                    }
                    String versionStatus = version.getScanStatus();
                    if ("SUCCESS".equals(versionStatus) || "FAILED".equals(versionStatus) || "CANCELLED".equals(versionStatus)) {
                        tp.setStatus("SKIPPED");
                        tp.setFactCount(0);
                        tp.setTotalItems(0);
                        tp.setProcessedItems(0);
                        tp.setEstimatedSecondsRemaining(-1L);
                        completedWeight += phaseWeight;
                        taskProgressList.add(tp);
                        continue;
                    } else if ("RUNNING".equals(versionStatus) || "QUEUED".equals(versionStatus)) {
                        tp.setStatus("RUNNING");
                        tp.setFactCount(0);
                        tp.setTotalItems(0);
                        tp.setProcessedItems(0);
                        long eta = computeAiOrchestrationEta(projectId, tasks, null);
                        if (eta < 0) {
                            Double histAiDuration = getHistoricalPhaseDuration(projectId, "AI_ORCHESTRATION");
                            if (histAiDuration != null) {
                                eta = Math.round(histAiDuration);
                            } else {
                                eta = DEFAULT_PHASE_FALLBACK_SECONDS * 5;
                            }
                        }
                        tp.setEstimatedSecondsRemaining(eta);
                        if (currentPhaseIndex < 0) {
                            currentPhaseIndex = phase.getOrder();
                        }
                        taskProgressList.add(tp);
                        continue;
                    }
                }
            }

            if (task != null) {
                tp.setStatus(task.getTaskStatus());
                tp.setFactCount(0);
                int taskTotal = task.getTotalItems() != null ? task.getTotalItems() : 0;
                int taskProcessed = task.getProcessedItems() != null ? task.getProcessedItems() : 0;
                tp.setTotalItems(taskTotal);
                tp.setProcessedItems(taskTotal > 0 ? Math.min(taskProcessed, taskTotal) : taskProcessed);
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
                } else if ("RUNNING".equals(task.getTaskStatus()) && task.getStartedAt() != null) {
                    long eta = -1;
                    Double histDuration = getHistoricalPhaseDuration(projectId, task.getTaskType());
                    if (histDuration != null) {
                        eta = Math.round(histDuration);
                    } else {
                        eta = DEFAULT_PHASE_FALLBACK_SECONDS * 5;
                    }
                    tp.setEstimatedSecondsRemaining(eta);
                } else {
                    tp.setEstimatedSecondsRemaining(-1L);
                }

                if (isCompletedTaskStatus(task.getTaskStatus())) {
                    completedWeight += phaseWeight;
                }
                if ("RUNNING".equals(task.getTaskStatus()) && currentPhaseIndex < 0) {
                    currentPhaseIndex = phase.getOrder();
                    int partialWeight = calculateRunningPhasePartialWeight(task, phaseWeight);
                    completedWeight += partialWeight;
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

        int progress = totalWeight > 0 ? (completedWeight * 100 / totalWeight) : 0;
        progress = Math.min(100, Math.max(0, progress));
        // 版本终态强制收敛进度：SUCCESS → 100%；FAILED/CANCELLED/PAUSED 保持当前计算值
        if ("SUCCESS".equals(version.getScanStatus())) {
            progress = 100;
        }

        // 整体 ETA：取所有 RUNNING 阶段的最大 ETA，加上后续阶段的历史均值（无历史时兜底 30s）
        Long overallEta = -1L;
        // 取所有 RUNNING 阶段中的最大 ETA（多个阶段并行运行时，取最长的）
        for (ScanProgressResponse.TaskProgress tp : taskProgressList) {
            if ("RUNNING".equals(tp.getStatus())
                    && tp.getEstimatedSecondsRemaining() != null
                    && tp.getEstimatedSecondsRemaining() > 0) {
                overallEta = Math.max(overallEta, tp.getEstimatedSecondsRemaining());
            }
        }
        // 加上后续未开始阶段的历史均值预估
        if (overallEta > 0 && currentPhaseIndex >= 0) {
            for (int i = currentPhaseIndex + 1; i < taskProgressList.size(); i++) {
                ScanProgressResponse.TaskProgress tp = taskProgressList.get(i);
                if ("PENDING".equals(tp.getStatus())) {
                    Double avgDuration = getHistoricalPhaseDuration(projectId, tp.getTaskType());
                    overallEta += avgDuration != null ? Math.round(avgDuration) : 30;
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

    // ==================== ETA 预估算法 v2 ====================

    /** 最小样本数：处理不到此数量时不显示实时 ETA（数据不稳定） */
    private static final int MIN_SAMPLE_FOR_REALTIME = 3;
    /** 历史基准查询的最大样本数 */
    private static final int HISTORY_SAMPLE_LIMIT = 5;
    /** 后续阶段无历史时的默认兜底时间（秒） */
    private static final long DEFAULT_PHASE_FALLBACK_SECONDS = 30;
    /** AI 类任务波动较大，添加额外安全余量 */
    private static final double AI_TASK_SAFETY_MARGIN = 1.25;
    /** 扫描类任务相对稳定，安全余量较小 */
    private static final double SCAN_TASK_SAFETY_MARGIN = 1.05;
    /** 长尾效应最大修正因子 */
    private static final double MAX_LONG_TAIL_FACTOR = 1.35;
    /** ETA 最小变化阈值（秒）：避免频繁微小波动 */
    private static final long ETA_STABLE_THRESHOLD = 5;

    /**
     * 高精度混合 ETA 计算：三速率融合 + 任务类型感知 + 自适应加权 + 非线性修正。
     * <p>
     * v2 改进点：
     * 1. 三速率融合：启动期速率、稳态速率、近期速率分别计算
     * 2. 任务类型感知：AI 任务添加安全余量，扫描任务更信任实时数据
     * 3. 进度自适应加权：不同进度阶段使用不同权重组合
     * 4. 长尾效应修正：进度 >80% 时逐步增加修正因子
     * 5. 异常检测：检测速率突降，避免过早乐观
     * 6. 偏差校准：对比历史数据，自动校准系统性偏差
     * </p>
     *
     * @param projectId 项目 ID（用于查历史基准）
     * @param task      当前运行中的子任务
     * @return 预估剩余秒数，-1 表示无法预估
     */
    private long computeBlendedEta(String projectId, ScanTask task) {
        double elapsedSeconds = Duration.between(task.getStartedAt(), LocalDateTime.now()).toMillis() / 1000.0;
        int processed = task.getProcessedItems();
        int total = task.getTotalItems();
        int remaining = total - processed;

        if (remaining <= 0) {
            return 0;
        }
        if (elapsedSeconds < 1) {
            return -1;
        }

        boolean isAiTask = task.getTaskType() != null && task.getTaskType().startsWith("AI_");
        double safetyMargin = isAiTask ? AI_TASK_SAFETY_MARGIN : SCAN_TASK_SAFETY_MARGIN;
        double progressRatio = (double) processed / total;

        Double histSecPerItem = getHistoricalSecondsPerItem(projectId, task.getTaskType());
        long histEta = histSecPerItem != null ? Math.round(remaining * histSecPerItem) : -1;

        if (processed < MIN_SAMPLE_FOR_REALTIME || progressRatio < 0.05) {
            return histEta > 0 ? histEta : -1;
        }

        double overallRate = processed / elapsedSeconds;
        if (overallRate < 0.0001) {
            return histEta > 0 ? histEta : -1;
        }

        // 三速率计算
        double startRate = calculateStartRate(elapsedSeconds, processed, progressRatio);
        double steadyRate = calculateSteadyRate(elapsedSeconds, processed, progressRatio);
        double recentRate = calculateRecentRate(elapsedSeconds, processed, progressRatio);

        // 检测速率异常（近期速率比稳态速率低40%以上）
        double slowdownFactor = 1.0;
        if (recentRate > 0 && steadyRate > 0 && recentRate < steadyRate * 0.6) {
            slowdownFactor = steadyRate / recentRate;
            slowdownFactor = Math.min(2.0, slowdownFactor);
        }

        // 进度自适应加权融合
        double fusedRate;
        if (progressRatio < 0.25) {
            // 初期：历史权重高，用启动期和整体速率
            double histWeight = 0.6 - progressRatio * 0.8;
            double realtimeWeight = 1.0 - histWeight;
            double realtimeRate = 0.3 * startRate + 0.7 * overallRate;
            double histRate = histSecPerItem != null ? (1.0 / histSecPerItem) : realtimeRate;
            fusedRate = realtimeWeight * realtimeRate + histWeight * histRate;
        } else if (progressRatio < 0.6) {
            // 中期：信任稳态速率，结合近期趋势
            fusedRate = 0.2 * startRate + 0.5 * steadyRate + 0.3 * recentRate;
            // 与历史对比校准
            if (histSecPerItem != null) {
                double histRate = 1.0 / histSecPerItem;
                double rateDeviation = Math.abs(fusedRate - histRate) / histRate;
                if (rateDeviation < 0.3) {
                    fusedRate = 0.7 * fusedRate + 0.3 * histRate;
                }
            }
        } else {
            // 后期：主要信任近期速率
            fusedRate = 0.1 * steadyRate + 0.9 * recentRate;
        }

        // 长尾效应修正：进度 >80% 时，随着完成度增加，逐步降低速率预期
        double longTailFactor = 1.0;
        if (progressRatio > 0.8) {
            double tailProgress = (progressRatio - 0.8) / 0.2;
            longTailFactor = 1.0 + tailProgress * (MAX_LONG_TAIL_FACTOR - 1.0);
        }

        // 计算实时 ETA
        long realtimeEta = Math.round(remaining / fusedRate * slowdownFactor * longTailFactor * safetyMargin);

        if (realtimeEta <= 0) {
            return histEta > 0 ? histEta : -1;
        }

        if (histEta > 0) {
            // 最终混合：进度越高越信任实时数据
            double finalRealtimeWeight;
            if (progressRatio < 0.15) {
                finalRealtimeWeight = 0.2;
            } else if (progressRatio < 0.4) {
                finalRealtimeWeight = 0.5;
            } else if (progressRatio < 0.7) {
                finalRealtimeWeight = 0.75;
            } else {
                finalRealtimeWeight = 0.9;
            }
            long blendedEta = Math.round(realtimeEta * finalRealtimeWeight + histEta * (1 - finalRealtimeWeight));
            return Math.max(1, blendedEta);
        }

        return Math.max(1, realtimeEta);
    }

    /**
     * 计算启动期速率（前 20% 处理项的平均速率）。
     */
    private double calculateStartRate(double elapsedSeconds, int processed, double progressRatio) {
        if (progressRatio <= 0.2 || processed < 5) {
            return processed / elapsedSeconds;
        }
        int startCount = Math.max(3, processed / 5);
        double startRatio = Math.pow((double) startCount / processed, 1.3);
        double startElapsed = elapsedSeconds * startRatio;
        return startElapsed > 1 ? startCount / startElapsed : processed / elapsedSeconds;
    }

    /**
     * 计算稳态速率（中间 60% 处理项的平均速率，排除启动和收尾）。
     */
    private double calculateSteadyRate(double elapsedSeconds, int processed, double progressRatio) {
        if (processed < 10) {
            return processed / elapsedSeconds;
        }
        int skipStart = Math.max(2, processed / 10);
        int skipRecent = Math.max(2, processed / 10);
        if (skipStart + skipRecent >= processed) {
            return processed / elapsedSeconds;
        }
        int steadyCount = processed - skipStart - skipRecent;
        double startRatio = Math.pow((double) skipStart / processed, 1.3);
        double recentRatio = 1.0 - Math.pow((double) (processed - skipRecent) / processed, 1.3);
        double steadyElapsed = elapsedSeconds * (1.0 - startRatio - recentRatio);
        return steadyElapsed > 1 ? steadyCount / steadyElapsed : processed / elapsedSeconds;
    }

    /**
     * 计算近期速率（最近 20% 处理项的平均速率）。
     */
    private double calculateRecentRate(double elapsedSeconds, int processed, double progressRatio) {
        if (processed < 5) {
            return processed / elapsedSeconds;
        }
        int recentCount = Math.max(2, processed / 5);
        double recentRatio = 1.0 - Math.pow((double) (processed - recentCount) / Math.max(1, processed), 1.3);
        double recentElapsed = elapsedSeconds * recentRatio;
        return recentElapsed > 0.5 ? recentCount / recentElapsed : processed / elapsedSeconds;
    }

    /**
     * 查询同项目同类型历史任务的加权平均每项耗时（秒）。
     * 使用指数加权：越近的历史任务权重越高（权重: 1.0, 0.7, 0.5, 0.3, 0.2）。
     *
     * @param projectId 项目 ID
     * @param taskType  阶段类型（如 ADAPTER_SCAN）
     * @return 加权平均每项耗时（秒），无历史数据时返回 null
     */
    private Double getHistoricalSecondsPerItem(String projectId, String taskType) {
        String cacheKey = projectId + ":" + taskType;
        Double cached = historicalSecPerItemCache.get(cacheKey);
        if (cached != null) {
            return cached == NO_HISTORY_SENTINEL ? null : cached;
        }
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

            double[] weights = {1.0, 0.7, 0.5, 0.3, 0.2};
            double weightedSecSum = 0;
            double weightedItemSum = 0;

            for (int i = 0; i < history.size(); i++) {
                ScanTask t = history.get(i);
                long sec = Duration.between(t.getStartedAt(), t.getFinishedAt()).getSeconds();
                int items = t.getProcessedItems() != null ? t.getProcessedItems() : 0;
                if (items > 0 && sec > 0) {
                    double w = weights[Math.min(i, weights.length - 1)];
                    double secPerItem = (double) sec / items;
                    weightedSecSum += secPerItem * w * items;
                    weightedItemSum += w * items;
                }
            }
            Double result = weightedItemSum > 0 ? weightedSecSum / weightedItemSum : null;
            // 缓存 null 用哨兵值，避免无历史数据时每次重复空查 DB
            historicalSecPerItemCache.put(cacheKey, result != null ? result : NO_HISTORY_SENTINEL);
            return result;
        } catch (Exception e) {
            log.debug("Failed to compute historical seconds per item for {}/{}: {}",
                    projectId, taskType, e.getMessage());
            return null;
        }
    }

    /**
     * 查询同项目某阶段历史加权平均总耗时（秒）。
     * 使用指数加权：越近的历史任务权重越高（权重: 1.0, 0.7, 0.5, 0.3, 0.2）。
     *
     * @param projectId 项目 ID
     * @param taskType  阶段类型
     * @return 加权平均总耗时（秒），无历史数据时返回 null
     */
    private Double getHistoricalPhaseDuration(String projectId, String taskType) {
        String cacheKey = projectId + ":" + taskType;
        Double cached = historicalDurationCache.get(cacheKey);
        if (cached != null) {
            return cached == NO_HISTORY_SENTINEL ? null : cached;
        }
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

            double[] weights = {1.0, 0.7, 0.5, 0.3, 0.2};
            double weightedSum = 0;
            double weightTotal = 0;
            for (int i = 0; i < history.size(); i++) {
                ScanTask t = history.get(i);
                long sec = Duration.between(t.getStartedAt(), t.getFinishedAt()).getSeconds();
                double w = weights[Math.min(i, weights.length - 1)];
                weightedSum += sec * w;
                weightTotal += w;
            }
            Double result = weightTotal > 0 ? weightedSum / weightTotal : null;
            historicalDurationCache.put(cacheKey, result != null ? result : NO_HISTORY_SENTINEL);
            return result;
        } catch (Exception e) {
            log.debug("Failed to compute historical phase duration for {}/{}: {}",
                    projectId, taskType, e.getMessage());
            return null;
        }
    }

    /**
     * 计算正在运行阶段的部分权重（基于 ScanTask）。
     * 根据已处理项数/总项数的比例，将阶段权重按比例计入已完成权重。
     *
     * @param task        当前运行中的任务
     * @param phaseWeight 阶段权重
     * @return 应计入的部分权重（整数）
     */
    private int calculateRunningPhasePartialWeight(ScanTask task, int phaseWeight) {
        if (task.getTotalItems() != null && task.getTotalItems() > 0
                && task.getProcessedItems() != null && task.getProcessedItems() > 0) {
            double ratio = (double) task.getProcessedItems() / task.getTotalItems();
            return (int) (phaseWeight * ratio);
        }
        return 0;
    }

    /**
     * 计算正在运行阶段的部分权重（基于 AiPhaseAggregate）。
     * 根据已处理项数/总项数的比例，将阶段权重按比例计入已完成权重。
     *
     * @param agg 聚合结果
     * @return 应计入的部分权重（整数）
     */
    private int calculateRunningPhasePartialWeight(AiPhaseAggregate agg) {
        if (agg.total > 0 && agg.completed > 0) {
            double ratio = (double) agg.completed / agg.total;
            ScanPhase phase = ScanPhaseRegistry.getPhase("AI_ORCHESTRATION");
            int phaseWeight = phase != null ? phase.getWeight() : 1;
            return (int) (phaseWeight * ratio);
        }
        return 0;
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

        // 2. Neo4j 子图异步删除

        // 3. 事实删除
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
        CompletableFuture.allOf(factFuture, evidenceFuture,
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
