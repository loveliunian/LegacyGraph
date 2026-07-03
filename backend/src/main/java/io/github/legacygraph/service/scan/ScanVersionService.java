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

            if (task != null) {
                tp.setStatus(task.getTaskStatus());
                tp.setFactCount(0);
                tp.setTotalItems(task.getTotalItems());
                tp.setProcessedItems(task.getProcessedItems());
                tp.setCurrentItem(task.getCurrentItem());
                tp.setStartedAt(task.getStartedAt());
                tp.setFinishedAt(task.getFinishedAt());

                // 计算本阶段 ETA
                if ("RUNNING".equals(task.getTaskStatus())
                        && task.getTotalItems() != null && task.getTotalItems() > 0
                        && task.getProcessedItems() != null && task.getProcessedItems() > 0
                        && task.getStartedAt() != null) {
                    long elapsedSeconds = Duration.between(task.getStartedAt(), LocalDateTime.now()).getSeconds();
                    if (elapsedSeconds > 0) {
                        long itemsPerSecond = task.getProcessedItems() / elapsedSeconds;
                        if (itemsPerSecond > 0) {
                            int remaining = task.getTotalItems() - task.getProcessedItems();
                            tp.setEstimatedSecondsRemaining(remaining / itemsPerSecond);
                        }
                    }
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

        // 整体 ETA：取当前运行阶段的预估剩余时间，加上后续阶段的经验时间
        Long overallEta = -1L;
        if (currentPhaseIndex >= 0 && currentPhaseIndex < taskProgressList.size()) {
            ScanProgressResponse.TaskProgress currentTp = taskProgressList.get(currentPhaseIndex);
            if (currentTp.getEstimatedSecondsRemaining() != null && currentTp.getEstimatedSecondsRemaining() > 0) {
                overallEta = currentTp.getEstimatedSecondsRemaining();
                // 加上后续未开始阶段的粗略预估（每阶段 30s 兜底）
                for (int i = currentPhaseIndex + 1; i < taskProgressList.size(); i++) {
                    ScanProgressResponse.TaskProgress tp = taskProgressList.get(i);
                    if ("PENDING".equals(tp.getStatus())) {
                        overallEta += 30;
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
     */
    @Transactional
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

        // 2. Neo4j 子图异步删除（不阻塞 HTTP 响应，通过 self 代理确保 @Async 生效）
        self.deleteNeo4jGraphAsync(projectId, versionId);

        // 3. PG 图谱数据并行删除（虚拟线程，I/O 密集）
        CompletableFuture<Void> graphFuture = CompletableFuture.runAsync(() -> {
            graphNodeRepository.delete(new QueryWrapper<GraphNode>().eq("version_id", versionId));
            graphEdgeRepository.delete(new QueryWrapper<GraphEdge>().eq("version_id", versionId));
        });

        // 4. 事实删除
        CompletableFuture<Void> factFuture = CompletableFuture.runAsync(() ->
            factRepository.delete(new QueryWrapper<Fact>().eq("version_id", versionId))
        );

        // 5. 证据删除（先查 ID 再批量删，避免字符串拼接子查询）
        CompletableFuture<Void> evidenceFuture = CompletableFuture.runAsync(() -> {
            List<Evidence> evidenceList = evidenceRepository.selectList(
                    new QueryWrapper<Evidence>().select("id").eq("version_id", versionId));
            if (!evidenceList.isEmpty()) {
                List<String> evidenceIds = evidenceList.stream().map(Evidence::getId).toList();
                nodeEvidenceRepository.delete(new QueryWrapper<NodeEvidence>().in("evidence_id", evidenceIds));
                edgeEvidenceRepository.delete(new QueryWrapper<EdgeEvidence>().in("evidence_id", evidenceIds));
                evidenceRepository.delete(new QueryWrapper<Evidence>().eq("version_id", versionId));
            }
        });

        // 6. 文档删除
        CompletableFuture<Void> docFuture = CompletableFuture.runAsync(() -> {
            docChunkRepository.delete(new QueryWrapper<DocChunk>().eq("version_id", versionId));
            documentRepository.delete(new QueryWrapper<Document>().eq("version_id", versionId));
        });

        // 7. 测试数据删除
        CompletableFuture<Void> testFuture = CompletableFuture.runAsync(() -> {
            List<TestCase> testCases = testCaseRepository.selectList(
                    new QueryWrapper<TestCase>().select("id").eq("version_id", versionId));
            if (!testCases.isEmpty()) {
                List<String> testCaseIds = testCases.stream().map(TestCase::getId).toList();
                testAssertionRepository.delete(new QueryWrapper<TestAssertion>().in("test_case_id", testCaseIds));
            }
            testResultRepository.delete(new QueryWrapper<TestResult>().eq("version_id", versionId));
            testRunRepository.delete(new QueryWrapper<TestRun>().eq("version_id", versionId));
            testCaseRepository.delete(new QueryWrapper<TestCase>().eq("version_id", versionId));
        });

        // 8. 审计/知识/缺口/风险删除
        CompletableFuture<Void> auditFuture = CompletableFuture.runAsync(() -> {
            runtimeTraceRepository.delete(new QueryWrapper<RuntimeTrace>().eq("version_id", versionId));
            reviewRecordRepository.delete(new QueryWrapper<ReviewRecord>().eq("version_id", versionId));
            knowledgeClaimRepository.delete(new QueryWrapper<KnowledgeClaim>().eq("version_id", versionId));
            gapTaskRepository.delete(new QueryWrapper<GapTask>().eq("version_id", versionId));
            migrationRiskRepository.delete(new QueryWrapper<MigrationRisk>().eq("version_id", versionId));
        });

        // 等待所有并行删除完成
        CompletableFuture.allOf(graphFuture, factFuture, evidenceFuture,
                docFuture, testFuture, auditFuture).join();

        // 9. 删除版本本身前，彻底清除所有相关缓存
        cacheService.evict(PROGRESS_KEY + versionId);
        graphCacheInvalidator.invalidateVersion(versionId);
        cacheService.evictByPrefix("graph:node:");

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
            log.warn("Neo4j 子图异步删除失败: projectId={}, versionId={}, err={}",
                    projectId, versionId, e.getMessage());
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
