package io.github.legacygraph.task.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dto.DocumentChunk;
import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.entity.ExtractCheckpoint;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.repository.ExtractCheckpointRepository;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.service.qa.VectorizationService;
import io.github.legacygraph.service.system.CacheService;
import io.github.legacygraph.task.ScanTaskRecorder;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * AI 扫描步骤共享支撑组件 — 集中放置被多个步骤执行器复用的私有逻辑。
 *
 * <p>包括：任务生命周期委托（createTask/completeTask，含 recorder 为 null 的 fallback）、
 * LLM 抽取缓存（cachedExtract）、内容向量化（vectorizeContent）、知识 Claim 草稿写入
 * （upsertClaimDrafts）、Fact 构建与批量落库（buildFact/saveAiFactsBatch/addFact）、
 * 字符串工具（truncate/sha256/allEmpty/nonBlank）以及共享的抽取线程池。</p>
 */
@Slf4j
@Component
public class AiScanStepSupport {

    /** LLM 并发上限（#21 调优：2→4，ZGC + 内存水位兜底，DOC+CODE 各 4 路，峰值 8 路 LLM） */
    public static final int DOC_EXTRACT_PARALLELISM = 4;
    /** 向量化并发上限：embedding 调用 CPU/内存较重，独立于 LLM 并发控制，避免无界堆积打爆堆 */
    public static final int VECTOR_PARALLELISM = 2;
    /** 向量化任务队列上限：超出即拒绝（背压），防止内容文本在队列里无限堆积导致 OOM */
    private static final int VECTOR_QUEUE_CAPACITY = 4;
    /** 内存水位线：堆使用率超过此值时拒绝新的向量化任务（背压，而非事后跳过） */
    private static final double MEMORY_HIGH_WATERMARK = 0.60;

    /** 向量化分片参数（#21 调优：2000→1200 减少 OOM，现恢复到 2000，bge-m3 对 2000 字符处理无精度损失） */
    private static final int VECTOR_CHUNK_SIZE = 2000;      // 1200→2000
    private static final int VECTOR_OVERLAP = 200;          // 120→200
    /** 大文档阈值（字符），超过此值动态使用更小的 chunk */
    private static final int LARGE_DOC_THRESHOLD = 20000;
    private static final int LARGE_DOC_CHUNK_SIZE = 1500;   // 800→1500
    /** 当前使用的 embedding 模型名 */
    private static final String EMBEDDING_MODEL_NAME = "bge-m3";

    /** LLM 抽取结果缓存 TTL（按内容哈希缓存；未改动的文档/代码重扫时复用，跳过 LLM 调用） */
    private static final Duration LLM_CACHE_TTL = Duration.ofDays(7);

    private final ScanTaskRecorder scanTaskRecorder;
    private final ScanTaskRepository scanTaskRepository;
    private final FactRepository factRepository;
    private final KnowledgeClaimService knowledgeClaimService;
    private final VectorizationService vectorizationService;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private ExtractCheckpointRepository checkpointRepository;

    /** 文档抽取线程池：虚拟线程 + Semaphore(4) 控制 LLM 并发，I/O 阻塞不占 OS 线程 */
    private final ExecutorService docExtractExecutor = boundedVirtualExecutor(DOC_EXTRACT_PARALLELISM);
    /** 代码抽取线程池：同上，DOC/CODE 各独立控制并发，峰值 8 路 LLM */
    private final ExecutorService codeExtractExecutor = boundedVirtualExecutor(DOC_EXTRACT_PARALLELISM);
    /**
     * 向量化专用线程池：有界线程 + 有界队列 + CallerRuns 回退。
     * 不再用 ForkJoinPool.commonPool（无界队列会无控堆积内容文本导致 OOM）。
     * 提交时还会做内存水位检查，超水位直接拒绝。
     *
     * <p>拒绝策略用 CallerRunsPolicy：队列满时提交线程自己执行任务，
     * 这样虽然会短暂阻塞提交方，但保证任务不丢失，避免 DiscardPolicy
     * 静默丢任务导致进度永远卡住的"软挂起"问题。</p>
     */
    private final ExecutorService vectorizeExecutor = new ThreadPoolExecutor(
            VECTOR_PARALLELISM, VECTOR_PARALLELISM,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(VECTOR_QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "vectorize-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());  // 队列满时提交线程自己执行，不丢任务

    /** LLM 抽取缓存（Redis，可选；测试环境为 null 时降级为直调 LLM） */
    @Autowired(required = false)
    private CacheService cacheService;

    public AiScanStepSupport(ScanTaskRecorder scanTaskRecorder,
                             ScanTaskRepository scanTaskRepository,
                             FactRepository factRepository,
                             KnowledgeClaimService knowledgeClaimService,
                             VectorizationService vectorizationService,
                             ObjectMapper objectMapper) {
        this.scanTaskRecorder = scanTaskRecorder;
        this.scanTaskRepository = scanTaskRepository;
        this.factRepository = factRepository;
        this.knowledgeClaimService = knowledgeClaimService;
        this.vectorizationService = vectorizationService;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建有界虚拟线程执行器：虚拟线程无上限（I/O 阻塞不占 OS 线程），
     * 但通过 Semaphore 将实际并发控制在 maxConcurrency 以内（防止打爆 LLM 限流）。
     */
    private static ExecutorService boundedVirtualExecutor(int maxConcurrency) {
        final Semaphore semaphore = new Semaphore(maxConcurrency);
        final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
        return new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                // 先提交虚拟线程，在虚拟线程内 acquire，避免调用线程（主扫描线程）被阻塞。
                // 旧实现在调用线程 acquire：主线程提交第 5 个任务时因前 4 个任务全阻塞而永久卡死。
                delegate.execute(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            command.run();
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            @Override public void shutdown() { delegate.shutdown(); }
            @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
            @Override public boolean isShutdown() { return delegate.isShutdown(); }
            @Override public boolean isTerminated() { return delegate.isTerminated(); }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return delegate.awaitTermination(timeout, unit);
            }
        };
    }

    @PreDestroy
    public void shutdown() {
        docExtractExecutor.shutdown();
        codeExtractExecutor.shutdown();
        vectorizeExecutor.shutdown();
        try {
            if (!docExtractExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                docExtractExecutor.shutdownNow();
            }
            if (!codeExtractExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                codeExtractExecutor.shutdownNow();
            }
            if (!vectorizeExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                vectorizeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            docExtractExecutor.shutdownNow();
            codeExtractExecutor.shutdownNow();
            vectorizeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 文档/代码/映射抽取复用的线程池。 */
    public ExecutorService getDocExtractExecutor() {
        return docExtractExecutor;
    }

    /** 代码抽取专用线程池（与 docExtractExecutor 独立，避免 doc+code 竞争同一池）。 */
    public ExecutorService getCodeExtractExecutor() {
        return codeExtractExecutor;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    // ==================== 任务生命周期 ====================

    public ScanTask createTask(String projectId, String versionId, String taskType, String taskName) {
        if (scanTaskRecorder != null) {
            return scanTaskRecorder.createTask(projectId, versionId, taskType, taskName);
        }
        // fallback: 测试环境 scanTaskRecorder 可能为 null
        ScanTask task = new ScanTask();
        task.setId(IdUtil.fastUUID());
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

    public void completeTask(ScanTask task, String summary, String error) {
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

    // ==================== LLM 缓存 ====================

    /** Redis 不可用时快速降级：首次失败后设为 false，后续调用跳过 Redis */
    private volatile boolean cacheAvailable = true;

    /**
     * LLM 抽取结果按内容哈希缓存：同一内容（文档/代码未改动）重扫时复用，跳过 LLM 调用。
     * cacheService 不可用（测试环境）或 Redis 连不上时直调 loader，首次失败后快速降级。
     */
    public <T> T cachedExtract(String type, String content, Supplier<T> loader,
                               Class<T> resultType, Predicate<T> isEmpty) {
        if (cacheService == null || content == null || content.isBlank()) {
            return loader.get();
        }
        if (!cacheAvailable) {
            return loader.get(); // Redis 已判不可用，跳过缓存直调 LLM
        }
        String key = "llm:" + type + ":" + sha256(content);
        try {
            T cached = cacheService.get(key, resultType);
            if (cached != null && !isEmpty.test(cached)) {
                return cached;
            }
        } catch (Exception e) {
            cacheAvailable = false;
            log.warn("Redis unavailable, disabling LLM cache for this scan: {}", e.getMessage());
            return loader.get();
        }
        T loaded = loader.get();
        if (loaded != null && !isEmpty.test(loaded)) {
            try {
                cacheService.put(key, loaded, LLM_CACHE_TTL);
            } catch (Exception e) {
                // put 失败不影响扫描主流程
            }
        }
        return loaded;
    }

    // ==================== 向量化 ====================

    /**
     * 向量化内容分片并存储到 pgvector（非阻塞：失败不影响扫描主流程）。
     *
     * <p>提交到专用有界线程池 {@link #vectorizeExecutor}，而非 ForkJoinPool.commonPool。
     * 提交时刻做内存水位检查（背压）：堆使用率超 {@link #MEMORY_HIGH_WATERMARK} 时直接拒绝，
     * 避免内容文本在无界队列里堆积导致 OOM。队列满时由 CallerRunsPolicy 回退到提交线程执行。</p>
     *
     * <p><b>#20 修复：</b>大文档（&gt;{@link #LARGE_DOC_THRESHOLD}）动态使用更小的 chunk size，
     * 减少 bge-m3 embedding 时的单次内存峰值。worker 线程内逐 chunk 处理并检查内存水位，
     * 避免所有 chunk 字符串同时驻留内存。</p>
     */
    public void vectorizeContent(String projectId, String versionId, String chunkType,
                                 String sourceUri, String content) {
        if (vectorizationService == null || !vectorizationService.isAvailable()) {
            return;
        }
        if (content == null || content.isBlank()) {
            return;
        }
        // 背压：提交时刻检查内存水位，超水位直接放弃向量化（比事后跳过更早拦截）
        if (!isMemoryHealthy()) {
            log.debug("Vectorization skipped (memory high) for {}: {}MB used / {}%",
                    sourceUri,
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024,
                    (int) (memoryUsageRatio() * 100));
            return;
        }
        // 大文档动态使用更小的 chunk，减少 embedding 内存峰值
        int effectiveChunkSize = VECTOR_CHUNK_SIZE;
        int effectiveOverlap = VECTOR_OVERLAP;
        if (content.length() > LARGE_DOC_THRESHOLD) {
            effectiveChunkSize = LARGE_DOC_CHUNK_SIZE;
            effectiveOverlap = 80;
            log.info("Large doc vectorization (len={}): using smaller chunks size={}",
                    content.length(), effectiveChunkSize);
        }
        final int chunkSize = effectiveChunkSize;
        final int overlap = effectiveOverlap;
        try {
            vectorizeExecutor.execute(() -> {
                try {
                    // worker 线程内再次检查内存（提交后可能内存已变紧张）
                    if (!isMemoryHealthy()) {
                        log.debug("Vectorization skipped (memory high in worker) for {}", sourceUri);
                        return;
                    }
                    int stored = vectorizationService.embedDocument(
                            projectId, versionId, chunkType, sourceUri, content,
                            chunkSize, overlap, EMBEDDING_MODEL_NAME);
                    if (stored > 0) {
                        log.debug("Vectorized {}: {} chunks stored", sourceUri, stored);
                    }
                } catch (OutOfMemoryError oom) {
                    log.warn("Vectorization OOM (non-blocking) for {}: {}", sourceUri, oom.getMessage());
                } catch (Exception e) {
                    log.debug("Vectorization skipped for {}: {}", sourceUri, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            // 有界队列满（CallerRunsPolicy 不会抛 Rejected，这里兜底其他拒绝场景）
            log.debug("Vectorization rejected (queue full) for {}", sourceUri);
        }
    }

    /**
     * 向量化结构化切块列表（spec 5.4）。
     * <p>
     * 与 {@link #vectorizeContent} 不同，本方法接收预切好的 {@link DocumentChunk} 列表，
     * 每个 chunk 已含 headingPath 前缀和 sourceLocation，直接调用 {@code embedAndStore}
     * 逐条入库，保留结构感知的切块边界与章节上下文。
     * <p>
     * 内存保护与背压策略与 {@link #vectorizeContent} 一致。
     *
     * @param projectId   项目 ID
     * @param versionId   扫描版本 ID
     * @param chunkType   分片类型（DOC/CODE 等）
     * @param sourceUri    来源 URI（通常为文件路径）
     * @param chunks      结构化切块列表
     */
    public void vectorizeChunks(String projectId, String versionId, String chunkType,
                                String sourceUri, List<DocumentChunk> chunks) {
        if (vectorizationService == null || !vectorizationService.isAvailable()) {
            return;
        }
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        if (!isMemoryHealthy()) {
            log.debug("Vectorization skipped (memory high) for {}: {}MB used / {}%",
                    sourceUri,
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024,
                    (int) (memoryUsageRatio() * 100));
            return;
        }
        try {
            vectorizeExecutor.execute(() -> {
                try {
                    if (!isMemoryHealthy()) {
                        log.debug("Vectorization skipped (memory high in worker) for {}", sourceUri);
                        return;
                    }
                    int stored = 0;
                    for (DocumentChunk chunk : chunks) {
                        if (!isMemoryHealthy()) {
                            log.warn("Vectorization aborted (memory high) at chunk {}/{} for {}",
                                    chunk.getChunkIndex(), chunks.size(), sourceUri);
                            break;
                        }
                        try {
                            Long id = vectorizationService.embedAndStore(
                                    projectId, versionId, chunkType, sourceUri,
                                    chunk.getChunkIndex(), chunk.getContent(), EMBEDDING_MODEL_NAME);
                            if (id != null) {
                                stored++;
                            }
                        } catch (OutOfMemoryError oom) {
                            log.warn("Embedding OOM at chunk {} for {}, aborting: {}",
                                    chunk.getChunkIndex(), sourceUri, oom.getMessage());
                            break;
                        } catch (Exception e) {
                            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                                log.debug("Chunk {} already exists for {}, skip", chunk.getChunkIndex(), sourceUri);
                            } else {
                                log.warn("Failed to embed chunk {} for {}: {}",
                                        chunk.getChunkIndex(), sourceUri, e.getMessage());
                            }
                        }
                    }
                    if (stored > 0) {
                        log.debug("Vectorized {}: {} chunks stored (structure-aware)", sourceUri, stored);
                    }
                } catch (OutOfMemoryError oom) {
                    log.warn("Vectorization OOM (non-blocking) for {}: {}", sourceUri, oom.getMessage());
                } catch (Exception e) {
                    log.debug("Vectorization skipped for {}: {}", sourceUri, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.debug("Vectorization rejected (queue full) for {}", sourceUri);
        }
    }

    /** 堆使用率（used / max）。 */
    private static double memoryUsageRatio() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return (double) used / rt.maxMemory();
    }

    /** 内存保护：堆使用率低于水位线时才安全进行向量化等重内存操作。 */
    public static boolean isMemoryHealthy() {
        return memoryUsageRatio() < MEMORY_HIGH_WATERMARK;
    }

    // ==================== Knowledge Claim 桥接 ====================

    public void upsertClaimDrafts(String projectId, String versionId,
                                  List<KnowledgeClaimDraft> drafts) {
        if (knowledgeClaimService == null || drafts == null || drafts.isEmpty()) {
            return;
        }
        try {
            knowledgeClaimService.upsertDrafts(drafts);
        } catch (Exception e) {
            log.warn("Knowledge claim upsert failed: projectId={}, versionId={}, err={}",
                    projectId, versionId, e.getMessage());
        }
    }

    // ==================== Fact 落库 ====================

    /** 构建 Fact（不落库）；序列化失败返回 null。 */
    public Fact buildFact(String projectId, String versionId, String factType, String factKey,
                          String factName, String sourcePath, Object data, double confidence,
                          String sourceType) {
        try {
            Fact fact = new Fact();
            fact.setId(IdUtil.fastUUID());
            fact.setProjectId(projectId);
            fact.setVersionId(versionId);
            fact.setFactType(factType);
            fact.setFactKey(factKey);
            fact.setFactName(factName);
            fact.setSourceType(sourceType != null ? sourceType : SourceType.DOC_AI.name());
            fact.setSourcePath(sourcePath);
            fact.setNormalizedData(objectMapper.writeValueAsString(data));
            fact.setConfidence(confidence);
            // AI 结果默认 PENDING_CONFIRM，不能直接 CONFIRMED
            fact.setStatus("PENDING_CONFIRM");
            fact.setCreatedBy("ai-orchestrator");
            fact.setCreatedAt(LocalDateTime.now());
            fact.setUpdatedAt(LocalDateTime.now());
            return fact;
        } catch (Exception e) {
            log.warn("Failed to build AI fact {}: {}", factKey, e.getMessage());
            return null;
        }
    }

    /** 批量 upsert：单次 DB 往返替代逐条 upsert。返回受影响行数。
     *  批内按冲突键 (projectId, versionId, factType, factKey) 去重，
     *  避免 PostgreSQL "ON CONFLICT DO UPDATE command cannot affect row a second time" 整批失败。 */
    public int saveAiFactsBatch(List<Fact> facts) {
        if (facts == null || facts.isEmpty()) {
            return 0;
        }
        // 批内去重：同 (project_id, version_id, fact_type, fact_key) 只保留最后一条
        java.util.Map<String, Fact> deduped = new java.util.LinkedHashMap<>();
        for (Fact f : facts) {
            if (f == null || f.getProjectId() == null || f.getVersionId() == null
                    || f.getFactType() == null || f.getFactKey() == null) {
                continue;
            }
            String k = f.getProjectId() + "|" + f.getVersionId() + "|" + f.getFactType() + "|" + f.getFactKey();
            deduped.put(k, f);
        }
        if (deduped.isEmpty()) {
            return 0;
        }
        List<Fact> toSave = new java.util.ArrayList<>(deduped.values());
        try {
            return factRepository.batchUpsert(toSave);
        } catch (Exception e) {
            log.warn("Failed to batch save {} AI facts: {}", toSave.size(), e.getMessage());
            return 0;
        }
    }

    public void addFact(List<Fact> facts, Fact fact) {
        if (fact != null) {
            facts.add(fact);
        }
    }

    // ==================== 字符串工具 ====================

    public String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 多个 List 全为 null/空时返回 true（用于判定抽取结果是否为空）。 */
    public static boolean allEmpty(List<?>... lists) {
        for (List<?> l : lists) {
            if (l != null && !l.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static String sha256(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    public String nonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /**
     * 更新扫描任务的处理进度项数（用于 ETA 计算）。
     */
    public void updateTaskProgress(ScanTask task, int total, int processed, String currentItem) {
        try {
            task.setTotalItems(total);
            task.setProcessedItems(processed);
            if (currentItem != null) task.setCurrentItem(currentItem);
            task.setUpdatedAt(LocalDateTime.now());
            scanTaskRepository.updateById(task);
        } catch (Exception e) {
            log.debug("Failed to update task progress: {}", e.getMessage());
        }
    }

    // ==================== 断点续传 ====================

    /**
     * 查询已完成提取的文件路径集合，用于跳过已处理的文件（断点续传）。
     */
    public java.util.Set<String> findDoneFilePaths(String projectId, String versionId, String stepName) {
        if (checkpointRepository == null) return java.util.Collections.emptySet();
        try {
            return checkpointRepository.findDonePaths(projectId, versionId, stepName);
        } catch (Exception e) {
            log.warn("Failed to query extract checkpoints, continuing without resume: {}", e.getMessage());
            return java.util.Collections.emptySet();
        }
    }

    /**
     * 标记文件提取中。
     */
    public void markExtracting(String projectId, String versionId, String filePath, String stepName) {
        if (checkpointRepository == null) return;
        try {
            ExtractCheckpoint cp = ExtractCheckpoint.builder()
                    .projectId(projectId).versionId(versionId)
                    .filePath(filePath).stepName(stepName)
                    .status("EXTRACTING")
                    .createdAt(java.time.LocalDateTime.now())
                    .updatedAt(java.time.LocalDateTime.now())
                    .build();
            checkpointRepository.insert(cp);
        } catch (Exception e) {
            // 唯一约束冲突 → 已有记录，忽略
            log.debug("Checkpoint EXTRACTING upsert skipped for {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * 标记文件提取完成。
     */
    public void markExtractDone(String projectId, String versionId, String filePath,
                                 String stepName, String resultJson) {
        if (checkpointRepository == null) return;
        try {
            ExtractCheckpoint cp = ExtractCheckpoint.builder()
                    .projectId(projectId).versionId(versionId)
                    .filePath(filePath).stepName(stepName)
                    .status("DONE").resultJson(resultJson)
                    .extractedAt(java.time.LocalDateTime.now())
                    .updatedAt(java.time.LocalDateTime.now())
                    .build();
            // upsert: 存在则更新，不存在则插入
            var existing = checkpointRepository.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExtractCheckpoint>()
                            .eq(ExtractCheckpoint::getProjectId, projectId)
                            .eq(ExtractCheckpoint::getVersionId, versionId)
                            .eq(ExtractCheckpoint::getFilePath, filePath)
                            .eq(ExtractCheckpoint::getStepName, stepName));
            if (existing != null) {
                cp.setId(existing.getId());
                cp.setCreatedAt(existing.getCreatedAt());
            }
            checkpointRepository.insertOrUpdate(cp);
        } catch (Exception e) {
            log.debug("Checkpoint DONE failed for {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * 标记文件提取失败。
     */
    public void markExtractFailed(String projectId, String versionId, String filePath,
                                   String stepName, String errorMsg) {
        if (checkpointRepository == null) return;
        try {
            var existing = checkpointRepository.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExtractCheckpoint>()
                            .eq(ExtractCheckpoint::getProjectId, projectId)
                            .eq(ExtractCheckpoint::getVersionId, versionId)
                            .eq(ExtractCheckpoint::getFilePath, filePath)
                            .eq(ExtractCheckpoint::getStepName, stepName));
            ExtractCheckpoint cp = existing != null ? existing : ExtractCheckpoint.builder()
                    .projectId(projectId).versionId(versionId)
                    .filePath(filePath).stepName(stepName)
                    .createdAt(java.time.LocalDateTime.now()).build();
            cp.setStatus("FAILED");
            cp.setErrorMsg(errorMsg);
            cp.setUpdatedAt(java.time.LocalDateTime.now());
            checkpointRepository.insertOrUpdate(cp);
        } catch (Exception e) {
            log.debug("Checkpoint FAILED failed for {}: {}", filePath, e.getMessage());
        }
    }
}
