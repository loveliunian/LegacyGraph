package io.github.legacygraph.task.step;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.builder.BusinessGraphBuilder;
import io.github.legacygraph.common.ScanStep;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dto.DocumentChunk;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.entity.DocumentElement;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.ParseFailure;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.repository.ParseFailureRepository;
import io.github.legacygraph.service.document.DocumentPartitionService;
import io.github.legacygraph.service.document.StructureAwareChunkService;
import io.github.legacygraph.service.scan.DocumentContentService;
import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * AI_DOC_EXTRACT — 文档业务事实抽取（写入 lg_fact，PENDING_CONFIRM）。
 */
@Slf4j
@Component
public class DocExtractStep implements AiScanStepExecutor {

    /** 文档 LLM 调用单次上限字符数；超过则分段并行抽取后合并 */
    private static final int DOC_CONTENT_LIMIT = 8000;
    /** 大文档(>50KB)分段大小（#21 调优：800→2500，减少 chunk 数量，OOM 由 isMemoryHealthy 兜底） */
    private static final int LARGE_DOC_CHUNK_SIZE = 2500;
    /** 中文档(>20KB)分段大小（#21 调优：1200→1800） */
    private static final int MEDIUM_DOC_CHUNK_SIZE = 1800;
    /** 普通文档分段大小（spec 5.5：分级 chunk size，其他用 2500） */
    private static final int NORMAL_DOC_CHUNK_SIZE = 2500;
    /** 分段重叠（字符），避免切割处跨句上下文丢失 */
    private static final int DOC_CHUNK_OVERLAP = 400;
    /** 超过此大小的文档才分段，中间大小直接截断（性价比：分段 LLM 耗时 vs 覆盖内容） */
    private static final int DOC_CHUNK_THRESHOLD = 16000;
    /** L-03: 大文档分片阈值（字符数），超过此值走分片策略 */
    private static final int SHARD_THRESHOLD = 500_000;
    /** L-03: 分片大小（字符数，≤50KB），每片独立抽取后合并 */
    private static final int SHARD_SIZE_CHARS = 50_000;
    /** 扫描产物目录（docs/legacygraph/），其中的文档是扫描自身产出，不应再次进入 AI_DOC_EXTRACT */
    private static final String SCAN_ARTIFACT_DIR = "docs/legacygraph/";

    private final AiScanStepSupport support;
    private final DocumentRepository documentRepository;
    private final DocUnderstandingAgent docUnderstandingAgent;
    private final BusinessGraphBuilder businessGraphBuilder;
    private final Neo4jGraphDao neo4jGraphDao;
    private final DocumentContentService documentContentService = new DocumentContentService();
    private final DocumentPartitionService documentPartitionService;
    private final StructureAwareChunkService structureAwareChunkService;
    private final Counter agentCallCounter;
    private final Counter graphNodeCounter;
    private final Counter graphEdgeCounter;

    /** 向量语义去重可用模型（bge-m3 @ Ollama）；未配置时降级为精确+子串去重，永不劣化 */
    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    /** L-03: 解析失败日志仓库（记录分片/shard 级失败，用于后续重试和治理） */
    @Autowired(required = false)
    private ParseFailureRepository parseFailureRepository;

    /** Feature Flag：结构感知切块开关（spec 5.4），关闭时走旧 DocumentContentService 路径 */
    @Value("${legacygraph.document.partition.enabled:true}")
    private boolean partitionEnabled;

    public DocExtractStep(AiScanStepSupport support,
                          DocumentRepository documentRepository,
                          DocUnderstandingAgent docUnderstandingAgent,
                          BusinessGraphBuilder businessGraphBuilder,
                          Neo4jGraphDao neo4jGraphDao,
                          DocumentPartitionService documentPartitionService,
                          StructureAwareChunkService structureAwareChunkService,
                          @Qualifier("agentCallCounter") Counter agentCallCounter,
                          @Qualifier("graphNodeCounter") Counter graphNodeCounter,
                          @Qualifier("graphEdgeCounter") Counter graphEdgeCounter) {
        this.support = support;
        this.documentRepository = documentRepository;
        this.docUnderstandingAgent = docUnderstandingAgent;
        this.businessGraphBuilder = businessGraphBuilder;
        this.neo4jGraphDao = neo4jGraphDao;
        this.documentPartitionService = documentPartitionService;
        this.structureAwareChunkService = structureAwareChunkService;
        this.agentCallCounter = agentCallCounter;
        this.graphNodeCounter = graphNodeCounter;
        this.graphEdgeCounter = graphEdgeCounter;
    }

    @Override
    public String getStepName() {
        return "AI_DOC_EXTRACT";
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public ScanStep getScanStep() {
        return ScanStep.INIT;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext ctx) {
        String projectId = ctx.getProjectId();
        String versionId = ctx.getVersionId();
        io.github.legacygraph.entity.ScanTask task =
                support.createTask(projectId, versionId, "AI_DOC_EXTRACT", "文档业务事实抽取");
        try {
            List<Document> allDocs = documentRepository.selectList(
                    new LambdaQueryWrapper<Document>()
                            .eq(Document::getProjectId, projectId)
                            .eq(Document::getVersionId, versionId));
            // 增量模式：仅处理 changedFilePaths 命中的文档。注意 changedFilePaths 存相对路径
            // （来自 FileChangeDetector/IncrementalScanService），而 Document.filePath 为绝对路径，
            // SQL .in() 无法直接匹配，故在内存中按 endsWith/contains 过滤（与
            // IncrementalScanService.matchesAnyChanged 语义一致）。
            List<Document> docs = filterDocsForExtract(allDocs, ctx.isIncremental(), ctx.getChangedFilePaths());
            // #22 P0：排除扫描产物文档（docs/legacygraph/ 下的文档是扫描自身产出，不应再次抽取）
            long artifactCount = docs.stream()
                    .filter(d -> d.getFilePath() != null && d.getFilePath().contains(SCAN_ARTIFACT_DIR))
                    .count();
            if (artifactCount > 0) {
                docs = docs.stream()
                        .filter(d -> d.getFilePath() == null || !d.getFilePath().contains(SCAN_ARTIFACT_DIR))
                        .collect(Collectors.toList());
                log.info("AI_DOC_EXTRACT filtered {} scan artifact docs (under {})", artifactCount, SCAN_ARTIFACT_DIR);
            }
            if (ctx.isIncremental() && ctx.getChangedFilePaths() != null && !ctx.getChangedFilePaths().isEmpty()) {
                log.info("增量抽取：处理 {} 篇文档（跳过 {} 篇未变更）",
                        docs.size(), allDocs.size() - docs.size());
            }
            if (docs.isEmpty()) {
                String summary = "未发现需要抽取的文档（" + allDocs.size() + " 篇全部为扫描产物或未变更），跳过 AI_DOC_EXTRACT";
                support.completeTask(task, summary, null);
                return StepExecutionResult.builder().success(true)
                        .message(summary).processedCount(0).build();
            }

            // 断点续传：跳过已完成的文件
            java.util.Set<String> donePaths = support.findDoneFilePaths(projectId, versionId, "DOC_EXTRACT");
            long skipped = docs.stream().filter(d -> donePaths.contains(d.getFilePath())).count();
            if (skipped > 0) {
                log.info("AI_DOC_EXTRACT checkpoint resume: {} docs already done, {} remaining",
                        skipped, docs.size() - skipped);
            }

            log.info("AI_DOC_EXTRACT starting: versionId={}, docCount={}, parallelism={}, skipped={}",
                    versionId, docs.size(), AiScanStepSupport.DOC_EXTRACT_PARALLELISM, skipped);

            // 设置总项数用于 ETA 计算
            int totalDocs = (int) (docs.size() - skipped);
            support.updateTaskProgress(task, totalDocs, 0, null);

            AtomicInteger factCount = new AtomicInteger(0);
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger partialDocumentCount = new AtomicInteger(0);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (Document doc : docs) {
                // 跳过已完成的文件
                if (donePaths.contains(doc.getFilePath())) {
                    continue;
                }
                String filePath = doc.getFilePath();
                futures.add(CompletableFuture.runAsync(() -> {
                    support.markExtracting(projectId, versionId, filePath, "DOC_EXTRACT");
                    String content = readDocContent(doc);
                    if (content == null || content.isBlank()) {
                        doc.setParseStatus("FAILED");
                        doc.setErrorMessage("无法读取文档内容：文件不存在或为空");
                        doc.setUpdatedAt(java.time.LocalDateTime.now());
                        try { documentRepository.updateById(doc); } catch (Exception e) { log.warn("Failed to update doc: {}", doc.getId(), e); }
                        support.markExtractFailed(projectId, versionId, filePath, "DOC_EXTRACT", "empty content");
                        logParseFailure(projectId, versionId, doc, 0, 1, 0, 0, "EMPTY_CONTENT", "无法读取文档内容");
                        return;
                    }
                    // L-03: 严格串行 — LLM 抽取完成后再向量化（所有文档统一，不再提前向量化）
                    try {
                        // 内存保护：堆快满时跳过 LLM 调用，避免 OOM 中断扫描
                        if (!AiScanStepSupport.isMemoryHealthy()) {
                            log.warn("Skipping doc extract for {} (low memory: {}MB free)",
                                    doc.getDocName(), Runtime.getRuntime().freeMemory() / 1024 / 1024);
                            doc.setParseStatus("FAILED");
                            doc.setErrorMessage("Low memory, skipped");
                            doc.setUpdatedAt(java.time.LocalDateTime.now());
                            try { documentRepository.updateById(doc); } catch (Exception ignored) {}
                            logParseFailure(projectId, versionId, doc, 0, 1, 0, content.length(), "OOM", "Low memory, skipped");
                            return;
                        }
                        // A3：大文档分段并行抽取再合并，既全覆盖又并发提速。
                        // 小文档（≤ DOC_CONTENT_LIMIT）保持原路径——单次 LLM 调用。
                        // L-03：超大文档（>500KB）先分片再分块，避免单次加载过大内容导致 OOM
                        DocUnderstandingAgent.BusinessFactExtraction extraction;
                        ChunkExtractionResult chunkExtraction = null;
                        if (content.length() > SHARD_THRESHOLD) {
                            chunkExtraction = extractFromShardsWithCoverage(projectId, versionId, doc, content);
                            extraction = chunkExtraction.extraction();
                        } else if (content.length() <= DOC_CHUNK_THRESHOLD) {
                            extraction = support.cachedExtract("doc",
                                    support.truncate(content, DOC_CONTENT_LIMIT), () -> {
                                agentCallCounter.increment();
                                return docUnderstandingAgent.extractBusinessFacts(projectId, content, doc.getFilePath());
                            }, DocUnderstandingAgent.BusinessFactExtraction.class,
                            e -> e == null || AiScanStepSupport.allEmpty(e.getBusinessDomains(), e.getBusinessProcesses(),
                                    e.getBusinessObjects(), e.getBusinessRules(), e.getRoles(),
                                    e.getFeatures(), e.getStatusTransitions()));
                        } else {
                            chunkExtraction = extractFromChunksWithCoverage(projectId, doc, content);
                            extraction = chunkExtraction.extraction();
                        }
                        // 小文档也做语义去重（embeddingModel 不可用时降级为原结果）
                        if (embeddingModel != null && extraction != null
                                && extraction.getFeatures() != null && extraction.getFeatures().size() > 10) {
                            extraction.setFeatures(semanticDeduplicateFeatures(extraction.getFeatures()));
                        }
                        int count = persistBusinessFacts(projectId, versionId, doc, extraction);
                        factCount.addAndGet(count);
                        buildBusinessGraph(projectId, versionId, doc, extraction);
                        support.upsertClaimDrafts(projectId, versionId,
                                docUnderstandingAgent.toClaimDrafts(projectId, versionId, extraction, doc.getFilePath()));
                        boolean completeExtraction = chunkExtraction == null || chunkExtraction.isComplete();
                        // 仅全部 chunk 成功时标记 PARSED；部分结果仍可追溯，但不可伪装为完整覆盖。
                        doc.setParseStatus(completeExtraction ? "PARSED" : "PARTIAL");
                        doc.setFactCount(count);
                        doc.setParsedAt(java.time.LocalDateTime.now());
                        if (completeExtraction) {
                            doc.setErrorMessage(null);
                        } else {
                            doc.setErrorMessage("文档分块抽取不完整：成功 "
                                    + chunkExtraction.successfulChunkCount() + "/" + chunkExtraction.totalChunkCount());
                            partialDocumentCount.incrementAndGet();
                        }
                        doc.setUpdatedAt(java.time.LocalDateTime.now());
                        documentRepository.updateById(doc);
                        if (completeExtraction) {
                            support.markExtractDone(projectId, versionId, filePath, "DOC_EXTRACT", "facts=" + count);
                        } else {
                            support.markExtractFailed(projectId, versionId, filePath, "DOC_EXTRACT", doc.getErrorMessage());
                        }
                        // L-03: LLM 抽取完成后向量化（严格串行，所有文档统一）
                        vectorizeDocument(projectId, versionId, doc, content);
                        // 更新进度（每 5 个文件写一次 DB，减少写入频率）
                        int done = processedCount.incrementAndGet();
                        if (done % 5 == 0 || done == totalDocs) {
                            support.updateTaskProgress(task, totalDocs, done, filePath);
                        }
                    } catch (OutOfMemoryError oom) {
                        log.error("Doc extract OOM for doc {} (content length={}), skip and continue",
                                doc.getId(), content.length());
                        doc.setParseStatus("FAILED");
                        doc.setErrorMessage("OOM: " + oom.getMessage());
                        doc.setUpdatedAt(java.time.LocalDateTime.now());
                        try { documentRepository.updateById(doc); } catch (Exception ignored) {}
                        logParseFailure(projectId, versionId, doc, 0, 1, 0, content.length(), "OOM", oom.getMessage());
                    } catch (Exception e) {
                        log.warn("Doc extract failed for doc {}: {}", doc.getId(), e.getMessage());
                        doc.setParseStatus("FAILED");
                        doc.setErrorMessage(e.getMessage());
                        doc.setUpdatedAt(java.time.LocalDateTime.now());
                        try {
                            documentRepository.updateById(doc);
                        } catch (Exception updateEx) {
                            log.warn("Failed to update doc status to FAILED: {}", doc.getId(), updateEx);
                        }
                        support.markExtractFailed(projectId, versionId, filePath, "DOC_EXTRACT", e.getMessage());
                        logParseFailure(projectId, versionId, doc, 0, 1, 0, content.length(), "LLM_ERROR", e.getMessage());
                    }
                }, support.getDocExtractExecutor()));
            }

            // 等待所有文档处理完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            int totalFacts = factCount.get();
            log.info("AI_DOC_EXTRACT completed: versionId={}, factCount={}, docCount={}",
                    versionId, totalFacts, docs.size());
            String summary = buildDocExtractSummary(totalFacts, docs.size(), partialDocumentCount.get());
            support.completeTask(task, summary, null);
            // H17: 部分文档失败时标记 warning，让调用方感知部分失败
            boolean hasWarning = partialDocumentCount.get() > 0;
            return StepExecutionResult.builder().success(true).warning(hasWarning).message(summary)
                    .processedCount(totalFacts).build();
        } catch (Exception e) {
            log.error("AI_DOC_EXTRACT failed: versionId={}", versionId, e);
            support.completeTask(task, null, e.getMessage());
            return StepExecutionResult.builder().success(false).message(e.getMessage()).build();
        }
    }

    /**
     * A3：大文档分段抽取后合并。
     * 按动态 chunkSize 分段（带重叠），每段独立调 LLM（复用 cachedExtract）。
     * <p><b>2 路受限并行</b>（#21 Task 6）：段间用 Semaphore(2) 限制并发，避免全并行导致
     * 多段 LLM 响应(BusinessFactExtraction，含大量长字符串)同时驻留引发 OOM，但比串行快约 2x。
     * 内存保护：每个 chunk future 内部提交前 + 获取信号量后双重检查 isMemoryHealthy，
     * OOM/内存不足时返回 null，join 阶段过滤掉，保留已抽到的段。</p>
     */
    private DocUnderstandingAgent.BusinessFactExtraction extractFromChunks(String projectId, Document doc, String content) {
        return extractFromChunksWithCoverage(projectId, doc, content).extraction();
    }

    /**
     * 分块抽取并返回覆盖度。任一块未完成时，调用方必须把文档标为 PARTIAL，
     * 防止局部抽取结果被当成整篇文档的完整结论。
     */
    private ChunkExtractionResult extractFromChunksWithCoverage(String projectId, Document doc, String content) {
        int chunkSize;
        int contentLen = content.length();
        if (contentLen > 50_000) {
            chunkSize = LARGE_DOC_CHUNK_SIZE;
        } else if (contentLen > 20_000) {
            chunkSize = MEDIUM_DOC_CHUNK_SIZE;
        } else {
            chunkSize = NORMAL_DOC_CHUNK_SIZE;
        }
        // spec 5.4 G2：partitionEnabled 时优先走结构感知切块路径，失败/空时降级到 splitContent
        List<DocChunk> chunks;
        if (partitionEnabled) {
            chunks = tryStructureAwareChunk(doc, content, chunkSize);
        } else {
            chunks = splitContent(content, chunkSize, DOC_CHUNK_OVERLAP);
        }
        // P0-2：分块数上限告警——超大文档可能产生数百块，记录以便后续治理（不影响流程）
        if (chunks.size() > 200) {
            log.warn("AI_DOC_EXTRACT chunk count {} exceeds 200 for doc {} (contentLen={}, chunkSize={})",
                    chunks.size(), doc.getDocName(), content.length(), chunkSize);
        }
        if (chunks.size() <= 1) {
            // 单段 → 回退直接抽取
            DocUnderstandingAgent.BusinessFactExtraction extraction = support.cachedExtract("doc", content, () -> {
                agentCallCounter.increment();
                return docUnderstandingAgent.extractBusinessFacts(projectId,
                        support.truncate(content, DOC_CONTENT_LIMIT), doc.getFilePath());
            }, DocUnderstandingAgent.BusinessFactExtraction.class,
            e -> e == null || AiScanStepSupport.allEmpty(e.getBusinessDomains(), e.getBusinessProcesses(),
                    e.getBusinessObjects(), e.getBusinessRules(), e.getRoles(),
                    e.getFeatures(), e.getStatusTransitions()));
            return new ChunkExtractionResult(extraction, 1, 1);
        }

        log.info("AI_DOC_EXTRACT chunking: doc={}, contentLen={}, chunks={}", doc.getDocName(), content.length(), chunks.size());
        // #22 P0：chunk 并行度从 2 提升到 3（OOM 由 isMemoryHealthy 兜底，总并发 4+3=7 < DeepSeek 8 路限流）
        final int chunkParallelism = Math.min(3, chunks.size());
        final java.util.concurrent.Semaphore chunkSem = new java.util.concurrent.Semaphore(chunkParallelism);
        List<CompletableFuture<DocUnderstandingAgent.BusinessFactExtraction>> chunkFutures = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            final DocChunk chunk = chunks.get(i);
            final String chunkText = chunk.content();
            // P0-2：将 chunkIndex/charStart/charEnd 附加到 sourcePath，供 LLM evidence 定位
            final String sourcePath = doc.getFilePath() + "#chunk" + chunk.chunkIndex()
                    + ":start" + chunk.charStart() + ":end" + chunk.charEnd();

            chunkFutures.add(CompletableFuture.supplyAsync(() -> {
                // 内存保护：提交前检查
                if (!AiScanStepSupport.isMemoryHealthy()) {
                    log.warn("Doc chunk extract skipped (low memory) for doc {} at chunk {}/{}",
                            doc.getId(), chunk.chunkIndex(), chunks.size());
                    return null;
                }
                try {
                    chunkSem.acquire();
                    try {
                        // 二次内存检查（获取信号量后内存可能已变紧张）
                        if (!AiScanStepSupport.isMemoryHealthy()) {
                            log.warn("Doc chunk extract skipped after sem acquire (low memory) for doc {} at chunk {}/{}",
                                    doc.getId(), chunk.chunkIndex(), chunks.size());
                            return null;
                        }
                        return support.cachedExtract("doc-chunk", chunkText, () -> {
                            agentCallCounter.increment();
                            return docUnderstandingAgent.extractBusinessFacts(projectId, chunkText, sourcePath);
                        }, DocUnderstandingAgent.BusinessFactExtraction.class,
                        e -> e == null || AiScanStepSupport.allEmpty(e.getBusinessDomains(), e.getBusinessProcesses(),
                                e.getBusinessObjects(), e.getBusinessRules(), e.getRoles(),
                                e.getFeatures(), e.getStatusTransitions()));
                    } finally {
                        chunkSem.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (OutOfMemoryError oom) {
                    log.warn("Doc chunk extract OOM at chunk {}/{} for doc {}, aborting", chunk.chunkIndex(), chunks.size(), doc.getId());
                    return null;
                } catch (Exception e) {
                    log.warn("Doc chunk extract failed at chunk {} for doc {}: {}", chunk.chunkIndex(), doc.getId(), e.getMessage());
                    return null;
                }
            }, support.getDocExtractExecutor()));
        }

        // 等待全部完成，过滤 null，合并
        CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0])).join();
        List<DocUnderstandingAgent.BusinessFactExtraction> chunkResults = new ArrayList<>();
        for (var f : chunkFutures) {
            DocUnderstandingAgent.BusinessFactExtraction r = f.join();
            if (r != null) {
                chunkResults.add(r);
            }
        }
        DocUnderstandingAgent.BusinessFactExtraction merged = mergeExtractions(chunkResults);
        // 向量语义去重：cosine > 0.90 的 Feature 合并（embeddingModel 不可用时降级为精确+子串去重结果）
        if (embeddingModel != null && merged.getFeatures() != null && merged.getFeatures().size() > 10) {
            merged.setFeatures(semanticDeduplicateFeatures(merged.getFeatures()));
        }
        return new ChunkExtractionResult(merged, chunks.size(), chunkResults.size());
    }

    /**
     * 带元数据的文档分块。
     * <ul>
     *   <li>{@code chunkIndex}：从 0 递增的分块序号</li>
     *   <li>{@code charStart}/{@code charEnd}：该块在原文中的字符区间 [start, end)</li>
     *   <li>{@code sectionTitle}：段落标题（暂为 null，待 Docling 集成时填充）</li>
     *   <li>{@code pageNumber}：页码（暂为 null，待 Docling 集成时填充）</li>
     * </ul>
     */
    public static record DocChunk(String content, int chunkIndex, int charStart, int charEnd,
                                  String sectionTitle, Integer pageNumber) {
    }

    /** 分块抽取的事实与覆盖度；isComplete 是写入 PARSED 状态的唯一判据。 */
    static record ChunkExtractionResult(DocUnderstandingAgent.BusinessFactExtraction extraction,
                                        int totalChunkCount, int successfulChunkCount) {
        boolean isComplete() {
            return totalChunkCount > 0 && successfulChunkCount == totalChunkCount;
        }
    }

    /** 将文本按 size 分段，段间 overlap 个字符重叠，尽量在换行处切割。返回带元数据的 DocChunk 列表。 */
    static List<DocChunk> splitContent(String text, int size, int overlap) {
        List<DocChunk> chunks = new ArrayList<>();
        if (text == null || text.isEmpty() || size <= 0) {
            return chunks;
        }
        int start = 0;
        int chunkIndex = 0;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            // 尽量在换行处切割
            if (end < text.length()) {
                int nl = text.lastIndexOf('\n', end);
                if (nl > start + size / 2) {
                    end = nl + 1;
                }
            }
            chunks.add(new DocChunk(text.substring(start, end), chunkIndex, start, end, null, null));
            chunkIndex++;
            if (end >= text.length()) {
                break;
            }
            // overlap 必须小于 chunkSize 才能保证游标前进；越界参数降为无重叠，
            // 既不能死循环或退化为逐字符切片，也不能静默丢弃文档尾部。
            int effectiveOverlap = overlap >= size ? 0 : Math.max(0, overlap);
            start = end - effectiveOverlap;
            if (start >= text.length()) {
                break;
            }
        }
        return chunks;
    }

    /**
     * 结构感知切块（spec 5.4 G2）。
     * <p>
     * DocumentPartitionService.partition → StructureAwareChunkService.chunk → 转 DocChunk。
     * partition/chunk 返回空或抛异常时降级到 {@link #splitContent}，保证不劣化。
     *
     * @param doc                文档实体
     * @param content            原始文本内容
     * @param fallbackChunkSize  降级时 splitContent 使用的 chunk size
     * @return 结构感知切块结果（已转为 DocChunk），失败时返回 splitContent 结果
     */
    private List<DocChunk> tryStructureAwareChunk(Document doc, String content, int fallbackChunkSize) {
        try {
            String fileName = doc.getDocName() != null && !doc.getDocName().isBlank()
                    ? doc.getDocName()
                    : extractFileName(doc.getFilePath());
            // 文本型文件（md/txt）传文本内容；二进制型文件（docx/xlsx）传文件路径
            String partitionContent = isTextFile(fileName) ? content : doc.getFilePath();
            List<DocumentElement> elements = documentPartitionService.partition(
                    doc.getId(), fileName, partitionContent);
            if (elements == null || elements.isEmpty()) {
                log.debug("Structure-aware partition returned empty for {}, fallback to splitContent",
                        doc.getFilePath());
                return splitContent(content, fallbackChunkSize, DOC_CHUNK_OVERLAP);
            }
            int totalSize = structureAwareChunkService.totalTextSize(elements);
            int maxChars = structureAwareChunkService.determineMaxChars(totalSize);
            List<DocumentChunk> docChunks = structureAwareChunkService.chunk(elements, maxChars);
            if (docChunks == null || docChunks.isEmpty()) {
                log.debug("Structure-aware chunking returned empty for {}, fallback to splitContent",
                        doc.getFilePath());
                return splitContent(content, fallbackChunkSize, DOC_CHUNK_OVERLAP);
            }
            log.info("AI_DOC_EXTRACT structure-aware chunking: doc={}, contentLen={}, chunks={}",
                    doc.getDocName(), content.length(), docChunks.size());
            // 转 DocChunk 以复用后续并行抽取逻辑（DocumentChunk 无 charStart/charEnd，用 0/length 近似）
            List<DocChunk> result = new ArrayList<>(docChunks.size());
            for (DocumentChunk dc : docChunks) {
                String sectionTitle = dc.getHeadingPath() != null && !dc.getHeadingPath().isEmpty()
                        ? String.join(" > ", dc.getHeadingPath())
                        : null;
                int chunkContentLen = dc.getContent() != null ? dc.getContent().length() : 0;
                result.add(new DocChunk(dc.getContent(), dc.getChunkIndex(), 0, chunkContentLen,
                        sectionTitle, null));
            }
            return result;
        } catch (Exception e) {
            log.warn("Structure-aware chunking failed for doc {}, fallback to splitContent: {}",
                    doc.getId(), e.getMessage());
            return splitContent(content, fallbackChunkSize, DOC_CHUNK_OVERLAP);
        }
    }

    /** 合并多个分段抽取结果：按 name/key 去重，取最高置信度。 */
    static DocUnderstandingAgent.BusinessFactExtraction mergeExtractions(
            List<DocUnderstandingAgent.BusinessFactExtraction> results) {
        DocUnderstandingAgent.BusinessFactExtraction merged = new DocUnderstandingAgent.BusinessFactExtraction();
        if (results == null || results.isEmpty()) {
            return merged;
        }
        if (results.size() == 1) {
            return results.get(0);
        }
        merged.setBusinessDomains(mergeByKey(results, r -> r.getBusinessDomains(), d -> d.getName()));
        merged.setBusinessProcesses(mergeByKey(results, r -> r.getBusinessProcesses(), p -> p.getName()));
        merged.setBusinessObjects(mergeByKey(results, r -> r.getBusinessObjects(), o -> o.getName()));
        merged.setBusinessRules(mergeByKey(results, r -> r.getBusinessRules(), r2 -> r2.getName()));
        merged.setRoles(new ArrayList<>(new java.util.LinkedHashSet<>(
                results.stream().flatMap(r -> safe(r.getRoles()).stream()).toList())));
        merged.setFeatures(deduplicateFeatures(
                results.stream().flatMap(r -> safe(r.getFeatures()).stream()).toList()));
        merged.setStatusTransitions(mergeByKey(results, r -> r.getStatusTransitions(),
                t -> (t.getBusinessObject() + ":" + t.getFromStatus() + "->" + t.getToStatus())));
        return merged;
    }

    /**
     * Feature 去重（#21 P0-3）：chunk 间 Feature 归一化去重 + 子串去重。
     * <ul>
     *   <li>归一化：去空格/标点/转小写后相同的 Feature 视为重复</li>
     *   <li>子串去重：短名称是长名称的子串时保留长名称（更具体）</li>
     * </ul>
     */
    static List<String> deduplicateFeatures(List<String> rawFeatures) {
        if (rawFeatures == null || rawFeatures.isEmpty()) {
            return new ArrayList<>();
        }
        // 1) 精确 + 归一化去重
        Map<String, String> normalizedToOriginal = new LinkedHashMap<>();
        for (String f : rawFeatures) {
            if (f == null || f.isBlank()) continue;
            String norm = f.trim().toLowerCase().replaceAll("[\\s\\p{Punct}]", "");
            if (norm.isEmpty()) continue;
            normalizedToOriginal.putIfAbsent(norm, f.trim());
        }
        // 2) 子串去重：若 A 是 B 的子串（归一化后），保留 B（更具体）
        List<String> candidates = new ArrayList<>(normalizedToOriginal.values());
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String normI = candidates.get(i).toLowerCase().replaceAll("[\\s\\p{Punct}]", "");
            boolean subsumed = false;
            for (int j = 0; j < candidates.size(); j++) {
                if (i == j) continue;
                String normJ = candidates.get(j).toLowerCase().replaceAll("[\\s\\p{Punct}]", "");
                // normI 是 normJ 的真子串，且长度差 >= 2（避免单字差异误删）
                if (normJ.contains(normI) && normJ.length() - normI.length() >= 2) {
                    subsumed = true;
                    break;
                }
            }
            if (!subsumed) {
                kept.add(candidates.get(i));
            }
        }
        return kept;
    }

    /**
     * 向量语义去重（#21 Task 4）：对 Feature 列表做 cosine > 0.90 的合并。
     * <p>在 {@link #deduplicateFeatures} 精确+子串去重之后执行，捕获"入金查询"与"查询入金"这类
     * 归一化不同但语义相同的 Feature。embeddingModel 不可用时直接返回原列表（降级）。</p>
     * <p>bge-m3 输出已归一化，cosine 相似度即点积。</p>
     */
    List<String> semanticDeduplicateFeatures(List<String> features) {
        if (features == null || features.size() <= 1) {
            return features;
        }
        try {
            // 精确去重先，减少 embedding 调用量
            List<String> unique = new ArrayList<>(new LinkedHashSet<>(features));
            List<float[]> vectors = embeddingModel.embed(unique);
            if (vectors == null || vectors.size() != unique.size()) {
                log.debug("Semantic dedup: embedding count mismatch ({} vs {}), fallback", vectors == null ? 0 : vectors.size(), unique.size());
                return features;
            }
            List<String> kept = new ArrayList<>();
            Set<Integer> removed = new HashSet<>();
            for (int i = 0; i < unique.size(); i++) {
                if (removed.contains(i)) {
                    continue;
                }
                kept.add(unique.get(i));
                for (int j = i + 1; j < unique.size(); j++) {
                    if (removed.contains(j)) {
                        continue;
                    }
                    if (cosineSimilarity(vectors.get(i), vectors.get(j)) > 0.85) {
                        removed.add(j);
                    }
                }
            }
            return kept;
        } catch (Exception e) {
            log.warn("Semantic dedup failed, fallback to original: {}", e.getMessage());
            return features;
        }
    }

    /** 余弦相似度（bge-m3 输出已归一化，直接点积） */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }

    @SuppressWarnings("unchecked")
    static <T> List<T> mergeByKey(List<DocUnderstandingAgent.BusinessFactExtraction> results,
                                          java.util.function.Function<DocUnderstandingAgent.BusinessFactExtraction, List<T>> getter,
                                          java.util.function.Function<T, String> keyFn) {
        Map<String, T> seen = new LinkedHashMap<>();
        for (var r : results) {
            for (T item : safe(getter.apply(r))) {
                String key = keyFn.apply(item);
                T existing = seen.get(key);
                if (existing == null) {
                    seen.put(key, item);
                } else {
                    // P0-2：同名项取较高置信度（通过反射读取 confidence 字段），
                    // 并合并 evidence 列表（如果存在 getEvidence/setEvidence 方法）。
                    seen.put(key, pickHigherConfidence(existing, item));
                }
            }
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * 比较两个同名项的 confidence（通过反射读取 double 字段），保留较高者；
     * 同时把较低者的 evidence 列表合并到 winner（若存在 getEvidence/setEvidence 方法）。
     */
    private static <T> T pickHigherConfidence(T existing, T incoming) {
        double c1 = readConfidence(existing);
        double c2 = readConfidence(incoming);
        T winner = c2 > c1 ? incoming : existing;
        T loser = c2 > c1 ? existing : incoming;
        mergeEvidence(winner, loser);
        return winner;
    }

    /** 通过反射读取 confidence 字段（double 类型），读取失败返回 0（保持原有"保留首个"行为）。 */
    private static double readConfidence(Object item) {
        if (item == null) {
            return 0;
        }
        try {
            java.lang.reflect.Field f = item.getClass().getDeclaredField("confidence");
            f.setAccessible(true);
            return f.getDouble(item);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return 0;
        }
    }

    /**
     * 通过反射合并 evidence 列表：将 loser 的 evidence 追加到 winner 的 evidence 列表。
     * 仅当 winner 同时具备 getEvidence() 和 setEvidence(List) 方法时生效
     * （如 BusinessObject/BusinessProcess/BusinessRule/StatusTransition）；
     * 没有 evidence 字段的类型（如 BusinessDomain）静默跳过。
     */
    @SuppressWarnings("unchecked")
    private static void mergeEvidence(Object winner, Object loser) {
        try {
            java.lang.reflect.Method getter = winner.getClass().getMethod("getEvidence");
            java.lang.reflect.Method setter = winner.getClass().getMethod("setEvidence", List.class);
            Object winnerEv = getter.invoke(winner);
            Object loserEv = getter.invoke(loser);
            if (!(winnerEv instanceof List) || !(loserEv instanceof List)) {
                return;
            }
            List<Object> merged = new ArrayList<>((List<Object>) winnerEv);
            for (Object e : (List<Object>) loserEv) {
                if (!merged.contains(e)) {
                    merged.add(e);
                }
            }
            setter.invoke(winner, merged);
        } catch (NoSuchMethodException e) {
            // 该类型无 evidence 字段（如 BusinessDomain），静默跳过
        } catch (Exception e) {
            // 反射调用失败，忽略以保持合并主流程不中断
        }
    }

    private static <T> List<T> safe(List<T> list) {
        return list != null ? list : List.of();
    }

    /**
     * 增量模式文档筛选：从全量文档中筛出需处理的文档。
     * <p>非增量模式（incremental=false）或 changedFilePaths 为空/null 时返回全量列表；
     * 增量模式且 changedFilePaths 非空时，仅保留 filePath 命中变更路径的文档。</p>
     * <p><b>路径适配</b>：changedFilePaths 存相对路径（来自 FileChangeDetector /
     * IncrementalScanService），Document.filePath 为绝对路径，SQL .in() 无法直接匹配，
     * 故在内存中按 endsWith/contains 过滤（与 IncrementalScanService.matchesAnyChanged 一致）。</p>
     */
    static List<Document> filterDocsForExtract(List<Document> allDocs, boolean incremental,
                                               Set<String> changedFilePaths) {
        if (!incremental || changedFilePaths == null || changedFilePaths.isEmpty()) {
            return allDocs;
        }
        return allDocs.stream()
                .filter(d -> d.getFilePath() != null && matchesAnyChangedPath(d.getFilePath(), changedFilePaths))
                .collect(Collectors.toList());
    }

    /**
     * 判断绝对路径是否命中任一变更相对路径（endsWith 或 contains）。
     * 与 {@link io.github.legacygraph.service.IncrementalScanService} 的 matchesAnyChanged 语义一致：
     * sourcePath 通常是绝对路径，变更列表是相对路径，因此用 endsWith / contains 匹配。
     */
    static boolean matchesAnyChangedPath(String absolutePath, Set<String> changedRelativePaths) {
        for (String rel : changedRelativePaths) {
            if (rel == null || rel.isBlank()) {
                continue;
            }
            if (absolutePath.endsWith(rel) || absolutePath.contains(rel)) {
                return true;
            }
        }
        return false;
    }

    /**
     * P1-B：区分"没扫到"与"没开扫"。文档数或事实数为 0 时给出显式提示，
     * 便于在扫描任务列表中定位业务图谱为空的原因（呼应"不静默截断"原则）。
     */
    private String buildDocExtractSummary(int factCount, int docCount) {
        return buildDocExtractSummary(factCount, docCount, 0);
    }

    private String buildDocExtractSummary(int factCount, int docCount, int partialDocumentCount) {
        if (docCount == 0) {
            return "⚠ 未发现任何文档 —— 业务事实 0 条。请确认 scanScope 含 DOC_PARSE 且项目已配置产品/需求文档";
        }
        String partialWarning = partialDocumentCount > 0
                ? "；⚠ " + partialDocumentCount + " 个文档分块不完整，已标记 PARTIAL，待重试" : "";
        if (factCount == 0) {
            return "⚠ 扫描文档 " + docCount + " 个，但未抽取到业务事实 —— 可能文档无业务语义或 LLM 未返回内容" + partialWarning;
        }
        return "AI 抽取业务事实 " + factCount + " 条，扫描文档 " + docCount + " 个" + partialWarning;
    }

    private int persistBusinessFacts(String projectId, String versionId, Document doc,
                                     DocUnderstandingAgent.BusinessFactExtraction extraction) {
        if (extraction == null) {
            return 0;
        }
        // 收集本批次所有 Fact，一次性 batchUpsert（替代逐条 upsert 的远程 DB 往返）
        List<Fact> facts = new ArrayList<>();
        if (extraction.getBusinessDomains() != null) {
            for (DocUnderstandingAgent.BusinessDomain domain : extraction.getBusinessDomains()) {
                String key = "domain:" + support.nonBlank(domain.getName(), domain.getDescription());
                support.addFact(facts, support.buildFact(projectId, versionId, "BUSINESS_DOMAIN", key, domain.getName(),
                        doc.getFilePath(), domain, domain.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getBusinessProcesses() != null) {
            for (DocUnderstandingAgent.BusinessProcess process : extraction.getBusinessProcesses()) {
                String key = process.getKey() != null ? process.getKey()
                        : "process:" + process.getName();
                support.addFact(facts, support.buildFact(projectId, versionId, "BUSINESS_PROCESS", key, process.getName(),
                        doc.getFilePath(), process, process.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getBusinessObjects() != null) {
            for (DocUnderstandingAgent.BusinessObject obj : extraction.getBusinessObjects()) {
                support.addFact(facts, support.buildFact(projectId, versionId, "BUSINESS_OBJECT", "object:" + obj.getName(),
                        obj.getName(), doc.getFilePath(), obj, obj.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getBusinessRules() != null) {
            for (DocUnderstandingAgent.BusinessRule rule : extraction.getBusinessRules()) {
                String key = "rule:" + support.nonBlank(rule.getName(), rule.getExpression());
                support.addFact(facts, support.buildFact(projectId, versionId, "BUSINESS_RULE", key, rule.getName(),
                        doc.getFilePath(), rule, rule.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getRoles() != null) {
            for (String role : extraction.getRoles()) {
                if (role == null || role.isBlank()) {
                    continue;
                }
                support.addFact(facts, support.buildFact(projectId, versionId, "BUSINESS_ROLE", "role:" + role,
                        role, doc.getFilePath(), role, 0.7, SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getStatusTransitions() != null) {
            for (DocUnderstandingAgent.StatusTransition transition : extraction.getStatusTransitions()) {
                String key = "transition:" + support.nonBlank(transition.getBusinessObject(), "object")
                        + ":" + support.nonBlank(transition.getFromStatus(), "?")
                        + "->" + support.nonBlank(transition.getToStatus(), "?")
                        + ":" + support.nonBlank(transition.getTrigger(), "");
                String name = support.nonBlank(transition.getBusinessObject(), "对象") + " "
                        + support.nonBlank(transition.getFromStatus(), "?") + " -> "
                        + support.nonBlank(transition.getToStatus(), "?");
                support.addFact(facts, support.buildFact(projectId, versionId, "STATUS_TRANSITION", key, name,
                        doc.getFilePath(), transition, transition.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getFeatures() != null) {
            for (String feature : extraction.getFeatures()) {
                if (feature == null || feature.isBlank()) {
                    continue;
                }
                support.addFact(facts, support.buildFact(projectId, versionId, "FEATURE", "feature:" + feature,
                        feature, doc.getFilePath(), feature, 0.7, SourceType.DOC_AI.name()));
            }
        }
        return support.saveAiFactsBatch(facts);
    }

    private void buildBusinessGraph(String projectId, String versionId, Document doc,
                                    DocUnderstandingAgent.BusinessFactExtraction extraction) {
        if (businessGraphBuilder == null || extraction == null) {
            return;
        }
        try {
            if (!support.isMemoryHealthy()) {
                log.warn("Business graph build skipped (memory high) for doc {} ({})", doc.getId(), doc.getDocName());
                return;
            }
            int beforeNodes = countGraphNodes(projectId, versionId);
            int beforeEdges = countGraphEdges(projectId, versionId);
            businessGraphBuilder.buildBusinessGraph(projectId, versionId, extraction, doc.getFilePath());
            int afterNodes = countGraphNodes(projectId, versionId);
            int afterEdges = countGraphEdges(projectId, versionId);
            graphNodeCounter.increment(afterNodes - beforeNodes);
            graphEdgeCounter.increment(afterEdges - beforeEdges);
        } catch (OutOfMemoryError oom) {
            log.error("Business graph build OOM for doc {} ({}), skip and continue", doc.getId(), doc.getDocName());
        } catch (Exception e) {
            log.warn("Business graph build failed for doc {}: {}", doc.getId(), e.getMessage());
        }
    }

    private String readDocContent(Document doc) {
        if (doc.getFilePath() == null || doc.getFilePath().isBlank()) {
            log.warn("readDocContent: filePath is null/blank for doc {} ({})", doc.getId(), doc.getDocName());
            return null;
        }
        try {
            Path filePath = Path.of(doc.getFilePath());
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                log.warn("readDocContent: file not found or not regular: {} (doc={})", doc.getFilePath(), doc.getId());
                return null;
            }
            // P0-2 / spec 5.5：不再前置截断大文档（已取消 100KB 截断）——返回完整内容，
            // 由 extractFromChunks() / StructureAwareChunkService 全量分块处理。
            // 分块大小由分级 chunkSize 控制，OOM 由 isMemoryHealthy() 兜底。
            return documentContentService.readText(doc.getFilePath());
        } catch (Exception e) {
            log.warn("readDocContent: failed to read {}: {}", doc.getFilePath(), e.getMessage());
            return null;
        }
    }

    /**
     * 文档向量化入口（spec 5.4）。
     * <p>
     * 通过 Feature Flag {@code legacygraph.document.partition.enabled} 控制路径：
     * <ul>
     *   <li>开启：DocumentPartitionService 解析 → StructureAwareChunkService 结构感知切块 → vectorizeChunks 逐块入库</li>
     *   <li>关闭（默认）：走旧 {@link AiScanStepSupport#vectorizeContent} 路径</li>
     * </ul>
     * 结构感知路径失败时自动降级到旧路径，保证不劣化。
     */
    private void vectorizeDocument(String projectId, String versionId, Document doc, String content) {
        if (!partitionEnabled) {
            support.vectorizeContent(projectId, versionId, "DOC", doc.getFilePath(), content);
            return;
        }
        try {
            String fileName = doc.getDocName() != null && !doc.getDocName().isBlank()
                    ? doc.getDocName()
                    : extractFileName(doc.getFilePath());
            // 文本型文件（md/txt）传文本内容；二进制型文件（docx/xlsx）传文件路径
            String partitionContent = isTextFile(fileName) ? content : doc.getFilePath();
            List<DocumentElement> elements = documentPartitionService.partition(
                    doc.getId(), fileName, partitionContent);
            if (elements.isEmpty()) {
                log.debug("Structure-aware partition returned empty for {}, fallback to plain", doc.getFilePath());
                support.vectorizeContent(projectId, versionId, "DOC", doc.getFilePath(), content);
                return;
            }
            // spec 5.5：分级 chunk size（>50KB 用 2500，>20KB 用 1800，其他用 2500）
            int totalSize = structureAwareChunkService.totalTextSize(elements);
            int maxChars = structureAwareChunkService.determineMaxChars(totalSize);
            List<DocumentChunk> chunks = structureAwareChunkService.chunk(elements, maxChars);
            if (chunks.isEmpty()) {
                log.debug("Structure-aware chunking returned empty for {}, fallback to plain", doc.getFilePath());
                support.vectorizeContent(projectId, versionId, "DOC", doc.getFilePath(), content);
                return;
            }
            log.debug("Structure-aware vectorization: doc={}, elements={}, chunks={}, maxChars={}",
                    doc.getDocName(), elements.size(), chunks.size(), maxChars);
            support.vectorizeChunks(projectId, versionId, "DOC", doc.getFilePath(), chunks);
        } catch (Exception e) {
            log.warn("Structure-aware vectorization failed for doc {}, fallback to plain: {}",
                    doc.getId(), e.getMessage());
            support.vectorizeContent(projectId, versionId, "DOC", doc.getFilePath(), content);
        }
    }

    /** 从文件路径提取文件名。 */
    private static String extractFileName(String filePath) {
        if (filePath == null) {
            return "";
        }
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return slash >= 0 ? filePath.substring(slash + 1) : filePath;
    }

    /** 判断是否为文本型文件（md/txt），二进制型（docx/xlsx）返回 false。 */
    private static boolean isTextFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".text");
    }

    /**
     * 统计指定版本的图谱节点数（用于 Prometheus 指标计算差值）
     */
    private int countGraphNodes(String projectId, String versionId) {
        try {
            return (int) neo4jGraphDao.countNodes(projectId, versionId, null);
        } catch (Exception e) {
            log.debug("countGraphNodes failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 统计指定版本的图谱边数（用于 Prometheus 指标计算差值）
     */
    private int countGraphEdges(String projectId, String versionId) {
        try {
            return (int) neo4jGraphDao.countEdges(projectId, versionId, null);
        } catch (Exception e) {
            log.debug("countGraphEdges failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * L-03: 超大文档（>500KB）分片抽取。
     * <p>将文档按 {@value #SHARD_SIZE_CHARS} 字符分片，每片独立走
     * {@link #extractFromChunksWithCoverage}（内部再分块调 LLM），最后合并所有分片结果。
     * 失败的分片记录到 {@code lg_parse_failure} 表，不影响其他分片。</p>
     *
     * @param projectId 项目 ID
     * @param versionId 版本 ID
     * @param doc       文档实体
     * @param content   完整文档内容（长度 > {@value #SHARD_THRESHOLD}）
     * @return 合并后的抽取结果与覆盖度
     */
    private ChunkExtractionResult extractFromShardsWithCoverage(String projectId, String versionId,
                                                                 Document doc, String content) {
        int contentLen = content.length();
        int totalShards = (contentLen + SHARD_SIZE_CHARS - 1) / SHARD_SIZE_CHARS;
        log.info("AI_DOC_EXTRACT sharding: doc={}, contentLen={}, shards={}, shardSize={}",
                doc.getDocName(), contentLen, totalShards, SHARD_SIZE_CHARS);

        List<DocUnderstandingAgent.BusinessFactExtraction> shardResults = new ArrayList<>();
        int successfulShards = 0;

        for (int i = 0; i < totalShards; i++) {
            int shardStart = i * SHARD_SIZE_CHARS;
            int shardEnd = Math.min(shardStart + SHARD_SIZE_CHARS, contentLen);
            String shardContent = content.substring(shardStart, shardEnd);

            // 内存保护：分片处理前检查内存水位
            if (!AiScanStepSupport.isMemoryHealthy()) {
                log.warn("Shard {}/{} skipped (low memory) for doc {}", i, totalShards, doc.getId());
                logParseFailure(projectId, versionId, doc, i, totalShards, shardStart, shardEnd,
                        "OOM", "Low memory, shard skipped");
                continue;
            }

            try {
                ChunkExtractionResult shardResult = extractFromChunksWithCoverage(projectId, doc, shardContent);
                if (shardResult.extraction() != null) {
                    shardResults.add(shardResult.extraction());
                    successfulShards++;
                } else {
                    logParseFailure(projectId, versionId, doc, i, totalShards, shardStart, shardEnd,
                            "LLM_ERROR", "Shard extraction returned null");
                }
            } catch (OutOfMemoryError oom) {
                log.warn("Shard {}/{} OOM for doc {}, skipping", i, totalShards, doc.getId());
                logParseFailure(projectId, versionId, doc, i, totalShards, shardStart, shardEnd,
                        "OOM", oom.getMessage());
            } catch (Exception e) {
                log.warn("Shard {}/{} failed for doc {}: {}", i, totalShards, doc.getId(), e.getMessage());
                logParseFailure(projectId, versionId, doc, i, totalShards, shardStart, shardEnd,
                        "SHARD_ERROR", e.getMessage());
            }
        }

        DocUnderstandingAgent.BusinessFactExtraction merged = mergeExtractions(shardResults);
        log.info("AI_DOC_EXTRACT sharding complete: doc={}, successfulShards={}/{}",
                doc.getDocName(), successfulShards, totalShards);
        return new ChunkExtractionResult(merged, totalShards, successfulShards);
    }

    /**
     * L-03: 记录解析失败到 {@code lg_parse_failure} 表。
     * <p>失败记录用于后续重试和治理分析。写入失败时仅 warn 不抛异常，不影响扫描主流程。</p>
     *
     * @param projectId    项目 ID
     * @param versionId    版本 ID
     * @param doc          文档实体
     * @param shardIndex   分片序号（从 0 开始，非分片文档传 0）
     * @param shardTotal   总分片数（非分片文档传 1）
     * @param charStart    失败片段在原文中的起始字符位置
     * @param charEnd      失败片段在原文中的结束字符位置
     * @param failureType  失败类型：OOM/LLM_ERROR/READ_ERROR/EMPTY_CONTENT/SHARD_ERROR/OTHER
     * @param errorMessage 错误消息（超 2000 字符自动截断）
     */
    private void logParseFailure(String projectId, String versionId, Document doc,
                                  int shardIndex, int shardTotal, int charStart, int charEnd,
                                  String failureType, String errorMessage) {
        if (parseFailureRepository == null) {
            log.debug("ParseFailureRepository not available, skip logging: doc={}, type={}", doc.getId(), failureType);
            return;
        }
        try {
            ParseFailure pf = new ParseFailure();
            pf.setProjectId(projectId);
            pf.setVersionId(versionId);
            pf.setDocumentId(doc.getId());
            pf.setFilePath(doc.getFilePath());
            pf.setShardIndex(shardIndex);
            pf.setShardTotal(shardTotal);
            pf.setCharStart(charStart);
            pf.setCharEnd(charEnd);
            pf.setFailureType(failureType);
            pf.setErrorMessage(errorMessage != null && errorMessage.length() > 2000
                    ? errorMessage.substring(0, 2000) : errorMessage);
            pf.setCreatedAt(java.time.LocalDateTime.now());
            parseFailureRepository.insert(pf);
        } catch (Exception e) {
            log.warn("Failed to log parse failure for doc {}: {}", doc.getId(), e.getMessage());
        }
    }
}
