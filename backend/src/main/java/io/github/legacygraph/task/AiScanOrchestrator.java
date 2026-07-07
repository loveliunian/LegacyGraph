package io.github.legacygraph.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.CodeFactAgent;
import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.agent.FeatureMappingAgent;
import io.github.legacygraph.agent.TestCaseAgent;
import io.github.legacygraph.builder.BusinessGraphBuilder;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.ScanStep;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.dto.FactExtractionResult;
import io.github.legacygraph.entity.AiScanJob;
import io.github.legacygraph.dto.GeneratedTestCase;
import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.builder.EvidenceGraphWriter;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.AiScanJobRepository;
import io.github.legacygraph.repository.ReviewRecordRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.service.scan.DocumentContentService;
import io.github.legacygraph.service.graph.GapFinderService;
import io.github.legacygraph.service.system.CacheService;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.service.qa.VectorizationService;
import io.github.legacygraph.understanding.ScanUnderstandingEnhancer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 扫描后 AI 编排器 — Phase 1。
 *
 * <p>扫描成功后，按 {@link AiScanConfig} 开关执行：
 * <ol>
 *   <li>AI_DOC_EXTRACT — 文档业务事实抽取（写入 lg_fact，PENDING_CONFIRM）</li>
 *   <li>AI_FEATURE_MAPPING — Feature → Page/API/Service 映射</li>
 *   <li>AI_TEST_GENERATE — 高价值节点测试用例生成（autoGenerateTestCase 开启时）</li>
 *   <li>AI_REVIEW_PREPARE — 低置信节点生成人工审核任务</li>
 * </ol>
 *
 * <p>所有 AI 结果默认 PENDING_CONFIRM，并关联证据，遵循"AI 不能直接作为事实源"的设计原则。
 * 每个子任务独立容错：单步失败不会中断整体编排。</p>
 */
@Slf4j
@Component
public class AiScanOrchestrator {

    private final ScanTaskRepository scanTaskRepository;
    private final ScanTaskRecorder scanTaskRecorder;
    private final AiScanJobRepository aiScanJobRepository;
    private final DocumentRepository documentRepository;
    private final FactRepository factRepository;
    private final ReviewRecordRepository reviewRecordRepository;
    private final TestCaseRepository testCaseRepository;
    private final Neo4jGraphDao neo4jGraphDao;
    private final DocUnderstandingAgent docUnderstandingAgent;
    private final FeatureMappingAgent featureMappingAgent;
    private final TestCaseAgent testCaseAgent;
    private final CodeFactAgent codeFactAgent;
    private final BusinessGraphBuilder businessGraphBuilder;
    private final ObjectMapper objectMapper;
    private final DocumentContentService documentContentService = new DocumentContentService();
    private final KnowledgeClaimService knowledgeClaimService;
    private final GapFinderService gapFinderService;
    /** 扫描后代码理解增强器 — 对扫描结果中的关键符号执行深入的代码理解 */
    private final ScanUnderstandingEnhancer scanUnderstandingEnhancer;
    /** 统一图谱写入器 — Claim/Evidence/Intent 写图主路径 */
    private final EvidenceGraphWriter evidenceGraphWriter;

    /** 文档/代码向量化服务 — 将文档和代码内容嵌入到 pgvector 供语义检索 */
    private final VectorizationService vectorizationService;

    /** P3-2: Prometheus 业务指标 */
    private final Timer scanDurationTimer;
    private final Counter agentCallCounter;
    private final Counter graphNodeCounter;
    private final Counter graphEdgeCounter;

    /** 测试生成的高价值节点上限，避免编排耗时过长 */
    private static final int MAX_TEST_GEN_NODES = 20;
    /** 审核准备节点上限 */
    private static final int MAX_REVIEW_NODES = 50;
    /** 代码事实抽取的类节点上限，避免 LLM 调用过多 */
    private static final int MAX_CODE_EXTRACT_NODES = 30;
    /** 单个代码文件读取上限（字符），配合 truncate 控制 prompt 体积 */
    private static final int CODE_CONTENT_LIMIT = 8000;

    /** 文档并发抽取线程数（LLM 调用为瓶颈，4 线程可同时打 4 个 LLM 请求） */
    private static final int DOC_EXTRACT_PARALLELISM = 4;
    /** 文档 LLM 调用单次上限字符数 */
    private static final int DOC_CONTENT_LIMIT = 8000;

    /** 向量化分片参数 */
    private static final int VECTOR_CHUNK_SIZE = 2000;
    private static final int VECTOR_OVERLAP = 200;
    /** LLM 功能映射每批 Feature 数：一次性喂全量 Feature 会导致 LLM 输出截断返回 0 条，分批调用。 */
    private static final int FEATURE_MAPPING_BATCH_SIZE = 80;
    /** 当前使用的 embedding 模型名 */
    private static final String EMBEDDING_MODEL_NAME = "bge-m3";

    /** LLM 抽取结果缓存 TTL（按内容哈希缓存；未改动的文档/代码重扫时复用，跳过 LLM 调用） */
    private static final Duration LLM_CACHE_TTL = Duration.ofDays(7);

    private final ExecutorService docExtractExecutor = Executors.newFixedThreadPool(DOC_EXTRACT_PARALLELISM);
    /** 代码抽取独立线程池：与文档抽取并发执行（DOC/CODE 独立，各自 4 线程，峰值 8 路 LLM） */
    private final ExecutorService codeExtractExecutor = Executors.newFixedThreadPool(DOC_EXTRACT_PARALLELISM);

    /** LLM 抽取缓存（Redis，可选；测试环境为 null 时降级为直调 LLM） */
    @Autowired(required = false)
    private CacheService cacheService;

    @PreDestroy
    public void shutdown() {
        docExtractExecutor.shutdown();
        codeExtractExecutor.shutdown();
        try {
            if (!docExtractExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                docExtractExecutor.shutdownNow();
            }
            if (!codeExtractExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                codeExtractExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            docExtractExecutor.shutdownNow();
            codeExtractExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public AiScanOrchestrator(ScanTaskRepository scanTaskRepository,
                              ScanTaskRecorder scanTaskRecorder,
                              AiScanJobRepository aiScanJobRepository,
                              DocumentRepository documentRepository,
                              FactRepository factRepository,
                              ReviewRecordRepository reviewRecordRepository,
                              TestCaseRepository testCaseRepository,
                              Neo4jGraphDao neo4jGraphDao,
                              DocUnderstandingAgent docUnderstandingAgent,
                              FeatureMappingAgent featureMappingAgent,
                              TestCaseAgent testCaseAgent,
                              CodeFactAgent codeFactAgent,
                              BusinessGraphBuilder businessGraphBuilder,
                              ObjectMapper objectMapper,
                              KnowledgeClaimService knowledgeClaimService,
                              GapFinderService gapFinderService,
                              ScanUnderstandingEnhancer scanUnderstandingEnhancer,
                              EvidenceGraphWriter evidenceGraphWriter,
                              VectorizationService vectorizationService,
                              @Qualifier("scanDurationTimer") Timer scanDurationTimer,
                              @Qualifier("agentCallCounter") Counter agentCallCounter,
                              @Qualifier("graphNodeCounter") Counter graphNodeCounter,
                              @Qualifier("graphEdgeCounter") Counter graphEdgeCounter) {
        this.scanTaskRepository = scanTaskRepository;
        this.scanTaskRecorder = scanTaskRecorder;
        this.aiScanJobRepository = aiScanJobRepository;
        this.documentRepository = documentRepository;
        this.factRepository = factRepository;
        this.reviewRecordRepository = reviewRecordRepository;
        this.testCaseRepository = testCaseRepository;
        this.neo4jGraphDao = neo4jGraphDao;
        this.docUnderstandingAgent = docUnderstandingAgent;
        this.featureMappingAgent = featureMappingAgent;
        this.testCaseAgent = testCaseAgent;
        this.codeFactAgent = codeFactAgent;
        this.businessGraphBuilder = businessGraphBuilder;
        this.objectMapper = objectMapper;
        this.knowledgeClaimService = knowledgeClaimService;
        this.gapFinderService = gapFinderService;
        this.scanUnderstandingEnhancer = scanUnderstandingEnhancer;
        this.evidenceGraphWriter = evidenceGraphWriter;
        this.vectorizationService = vectorizationService;
        this.scanDurationTimer = scanDurationTimer;
        this.agentCallCounter = agentCallCounter;
        this.graphNodeCounter = graphNodeCounter;
        this.graphEdgeCounter = graphEdgeCounter;
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
        boolean succeeded = false;
        try {
            // DOC_EXTRACT → CODE_EXTRACT 顺序执行（共享 docExtractExecutor 4 路 LLM）。
            // 曾试 doc/code 并发 8 路，触发 DeepSeek 限流导致 code 抽取 138s→576s，收益被限流吃掉，改回顺序。
            updateStep(jobId, ScanStep.INIT);
            runDocExtract(projectId, versionId);
            if (isCancelled != null && isCancelled.getAsBoolean()) { log.info("AI orchestration cancelled after doc extract: versionId={}", versionId); updateStep(jobId, ScanStep.FAILED); return; }

            // PARSE_FILES → EXTRACT_FACTS (code extract)
            updateStep(jobId, ScanStep.PARSE_FILES);
            runCodeExtract(projectId, versionId);
            if (isCancelled != null && isCancelled.getAsBoolean()) { log.info("AI orchestration cancelled after code extract: versionId={}", versionId); updateStep(jobId, ScanStep.FAILED); return; }

            // EXTRACT_FACTS → BUILD_GRAPH (feature mapping)
            updateStep(jobId, ScanStep.EXTRACT_FACTS);
            runFeatureCodeMapping(projectId, versionId);
            if (isCancelled != null && isCancelled.getAsBoolean()) { log.info("AI orchestration cancelled after feature-code mapping: versionId={}", versionId); updateStep(jobId, ScanStep.FAILED); return; }

            // BUILD_GRAPH → MERGE_ENTITIES
            updateStep(jobId, ScanStep.BUILD_GRAPH);
            runFeatureMapping(projectId, versionId);
            if (isCancelled != null && isCancelled.getAsBoolean()) { log.info("AI orchestration cancelled after feature mapping: versionId={}", versionId); updateStep(jobId, ScanStep.FAILED); return; }

            // MERGE_ENTITIES → WRITE_INTENT (test generate)
            if (config.isAutoGenerateTestCase()) {
                updateStep(jobId, ScanStep.MERGE_ENTITIES);
                runTestGenerate(projectId, versionId);
                if (isCancelled != null && isCancelled.getAsBoolean()) { log.info("AI orchestration cancelled after test generate: versionId={}", versionId); updateStep(jobId, ScanStep.FAILED); return; }
            }

            // WRITE_INTENT → ENHANCE (review prepare)
            updateStep(jobId, ScanStep.WRITE_INTENT);
            runReviewPrepare(projectId, versionId, config.getMinConfidence());
            if (isCancelled != null && isCancelled.getAsBoolean()) { log.info("AI orchestration cancelled after review prepare: versionId={}", versionId); updateStep(jobId, ScanStep.FAILED); return; }

            // ENHANCE → INDEX (knowledge gap + understanding)
            updateStep(jobId, ScanStep.ENHANCE);
            runKnowledgeGapScan(projectId, versionId);
            runScanUnderstandingEnhancement(projectId, versionId);

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

    // ==================== AI_DOC_EXTRACT ====================

    private void runDocExtract(String projectId, String versionId) {
        ScanTask task = createTask(projectId, versionId, "AI_DOC_EXTRACT", "文档业务事实抽取");
        try {
            List<Document> docs = documentRepository.selectList(
                    new LambdaQueryWrapper<Document>()
                            .eq(Document::getProjectId, projectId)
                            .eq(Document::getVersionId, versionId));
            if (docs.isEmpty()) {
                completeTask(task, buildDocExtractSummary(0, 0), null);
                return;
            }

            log.info("AI_DOC_EXTRACT starting: versionId={}, docCount={}, parallelism={}",
                    versionId, docs.size(), DOC_EXTRACT_PARALLELISM);

            AtomicInteger factCount = new AtomicInteger(0);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (Document doc : docs) {
                futures.add(CompletableFuture.runAsync(() -> {
                    String content = readDocContent(doc);
                    if (content == null || content.isBlank()) {
                        // P2 修复：文件读取失败时也更新状态为 FAILED，避免永远卡在 DISCOVERED
                        doc.setParseStatus("FAILED");
                        doc.setErrorMessage("无法读取文档内容：文件不存在或为空");
                        doc.setUpdatedAt(java.time.LocalDateTime.now());
                        try {
                            documentRepository.updateById(doc);
                        } catch (Exception e) {
                            log.warn("Failed to update doc status to FAILED: {}", doc.getId(), e);
                        }
                        return;
                    }
                    // 向量化文档内容（fire-and-forget：不阻塞 LLM 调用线程）
                    CompletableFuture.runAsync(() ->
                            vectorizeContent(projectId, versionId, "DOC", doc.getFilePath(), content));
                    try {
                        String docContent = truncate(content, DOC_CONTENT_LIMIT);
                        DocUnderstandingAgent.BusinessFactExtraction extraction =
                                cachedExtract("doc", docContent, () -> {
                                    agentCallCounter.increment();
                                    return docUnderstandingAgent.extractBusinessFacts(projectId, docContent, doc.getFilePath());
                                }, DocUnderstandingAgent.BusinessFactExtraction.class,
                                e -> e == null || allEmpty(e.getBusinessDomains(), e.getBusinessProcesses(),
                                        e.getBusinessObjects(), e.getBusinessRules(), e.getRoles(),
                                        e.getFeatures(), e.getStatusTransitions()));
                        int count = persistBusinessFacts(projectId, versionId, doc, extraction);
                        factCount.addAndGet(count);
                        buildBusinessGraph(projectId, versionId, doc, extraction);
                        upsertClaimDrafts(projectId, versionId,
                                docUnderstandingAgent.toClaimDrafts(projectId, versionId, extraction, doc.getFilePath()));
                        // P2 修复：抽取成功后更新状态为 PARSED
                        doc.setParseStatus("PARSED");
                        doc.setFactCount(count);
                        doc.setParsedAt(java.time.LocalDateTime.now());
                        doc.setUpdatedAt(java.time.LocalDateTime.now());
                        documentRepository.updateById(doc);
                    } catch (Exception e) {
                        log.warn("Doc extract failed for doc {}: {}", doc.getId(), e.getMessage());
                        // P2 修复：抽取失败时更新状态为 FAILED
                        doc.setParseStatus("FAILED");
                        doc.setErrorMessage(e.getMessage());
                        doc.setUpdatedAt(java.time.LocalDateTime.now());
                        try {
                            documentRepository.updateById(doc);
                        } catch (Exception updateEx) {
                            log.warn("Failed to update doc status to FAILED: {}", doc.getId(), updateEx);
                        }
                    }
                }, docExtractExecutor));
            }

            // 等待所有文档处理完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            int totalFacts = factCount.get();
            log.info("AI_DOC_EXTRACT completed: versionId={}, factCount={}, docCount={}",
                    versionId, totalFacts, docs.size());
            completeTask(task, buildDocExtractSummary(totalFacts, docs.size()), null);
        } catch (Exception e) {
            log.error("AI_DOC_EXTRACT failed: versionId={}", versionId, e);
            completeTask(task, null, e.getMessage());
        }
    }

    /**
     * P1-B：区分"没扫到"与"没开扫"。文档数或事实数为 0 时给出显式提示，
     * 便于在扫描任务列表中定位业务图谱为空的原因（呼应"不静默截断"原则）。
     */
    private String buildDocExtractSummary(int factCount, int docCount) {
        if (docCount == 0) {
            return "⚠ 未发现任何文档 —— 业务事实 0 条。请确认 scanScope 含 DOC_PARSE 且项目已配置产品/需求文档";
        }
        if (factCount == 0) {
            return "⚠ 扫描文档 " + docCount + " 个，但未抽取到业务事实 —— 可能文档无业务语义或 LLM 未返回内容";
        }
        return "AI 抽取业务事实 " + factCount + " 条，扫描文档 " + docCount + " 个";
    }

    // ==================== AI_CODE_EXTRACT ====================

    /**
     * P0-A：从代码抽取业务事实，让"无文档"项目也能产出业务/功能节点。
     *
     * <p>复用 {@link CodeFactAgent} 对 Service/Controller 类源码做 LLM 语义理解，
     * 抽取结果桥接为 {@link DocUnderstandingAgent.BusinessFactExtraction}（填充 features），
     * 复用 {@link BusinessGraphBuilder#buildBusinessGraph} 落图，避免重复的落库映射代码。</p>
     */
    private void runCodeExtract(String projectId, String versionId) {
        ScanTask task = createTask(projectId, versionId, "AI_CODE_EXTRACT", "代码业务事实抽取");
        try {
            // 取 Service/Controller 类节点作为业务语义最集中的抽取对象
            List<GraphNode> codeNodes = new ArrayList<>();
            codeNodes.addAll(neo4jGraphDao.queryNodes(projectId, versionId,
                    NodeType.Service.name(), null, null, null, MAX_CODE_EXTRACT_NODES));
            codeNodes.addAll(neo4jGraphDao.queryNodes(projectId, versionId,
                    NodeType.Controller.name(), null, null, null, MAX_CODE_EXTRACT_NODES));

            if (codeNodes.isEmpty()) {
                completeTask(task, "⚠ 无 Service/Controller 类节点，跳过代码事实抽取", null);
                return;
            }

            // 按 sourcePath 去重后截断到上限
            Set<String> uniquePaths = new LinkedHashSet<>();
            List<GraphNode> uniqueNodes = new ArrayList<>();
            for (GraphNode node : codeNodes) {
                String path = node.getSourcePath();
                if (path != null && !path.isBlank() && uniquePaths.add(path)) {
                    uniqueNodes.add(node);
                    if (uniqueNodes.size() >= MAX_CODE_EXTRACT_NODES) break;
                }
            }

            log.info("AI_CODE_EXTRACT starting: versionId={}, nodeCount={}, dedupedTo={}, parallelism={}",
                    versionId, codeNodes.size(), uniqueNodes.size(), DOC_EXTRACT_PARALLELISM);

            AtomicInteger factCount = new AtomicInteger(0);
            AtomicInteger processed = new AtomicInteger(0);
            Set<String> visitedPaths = ConcurrentHashMap.newKeySet();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (GraphNode node : uniqueNodes) {
                futures.add(CompletableFuture.runAsync(() -> {
                    String content = readCodeContent(node, visitedPaths);
                    if (content == null || content.isBlank()) {
                        return;
                    }
                    processed.incrementAndGet();
                    // 向量化代码内容（fire-and-forget：不阻塞 LLM 调用线程）
                    CompletableFuture.runAsync(() ->
                            vectorizeContent(projectId, versionId, "CODE", node.getSourcePath(), content));
                    try {
                        String codeContent = truncate(content, CODE_CONTENT_LIMIT);
                        FactExtractionResult result = cachedExtract("code", codeContent,
                                () -> codeFactAgent.extractFacts(projectId, codeContent, node.getSourcePath()),
                                FactExtractionResult.class,
                                r -> r == null || r.getItems() == null || r.getItems().isEmpty());
                        factCount.addAndGet(
                                persistAndBuildCodeFacts(projectId, versionId, node, result));
                    } catch (Exception e) {
                        log.warn("Code fact extract failed for node {}: {}", node.getNodeKey(), e.getMessage());
                    }
                }, docExtractExecutor));
            }

            // 等待所有代码分析完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            int totalFacts = factCount.get();
            int totalProcessed = processed.get();
            log.info("AI_CODE_EXTRACT completed: versionId={}, factCount={}, processed={}",
                    versionId, totalFacts, totalProcessed);

            String summary = totalFacts > 0
                    ? "AI 从代码抽取业务事实 " + totalFacts + " 条，分析类节点 " + totalProcessed + " 个"
                    : "⚠ 分析类节点 " + totalProcessed + " 个，未抽取到业务事实";
            completeTask(task, summary, null);
        } catch (Exception e) {
            log.error("AI_CODE_EXTRACT failed: versionId={}", versionId, e);
            completeTask(task, null, e.getMessage());
        }
    }

    /**
     * 将代码事实抽取结果落 Fact 表，并桥接为业务图谱功能节点。
     */
    private int persistAndBuildCodeFacts(String projectId, String versionId, GraphNode node,
                                         FactExtractionResult result) {
        if (result == null || result.getItems() == null || result.getItems().isEmpty()) {
            return 0;
        }
        List<Fact> facts = new ArrayList<>();
        DocUnderstandingAgent.BusinessFactExtraction bridge =
                new DocUnderstandingAgent.BusinessFactExtraction();
        for (FactExtractionResult.FactItem item : result.getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            double confidence = item.getConfidence() != null
                    ? item.getConfidence().doubleValue() : 0.6;
            String key = item.getKey() != null && !item.getKey().isBlank()
                    ? item.getKey() : "code-feature:" + item.getName();
            addFact(facts, buildFact(projectId, versionId, "CODE_FEATURE", key, item.getName(),
                    node.getSourcePath(), item, confidence, SourceType.CODE_AI.name()));
            bridge.getFeatures().add(item.getName());
        }
        int count = saveAiFactsBatch(facts);
        // 复用 P0-B 的功能清单落图路径
        if (!bridge.getFeatures().isEmpty() && businessGraphBuilder != null) {
            try {
                businessGraphBuilder.buildBusinessGraph(projectId, versionId, bridge,
                        node.getSourcePath(), SourceType.CODE_AI.name());
            } catch (Exception e) {
                log.warn("Business graph build from code facts failed for node {}: {}",
                        node.getNodeKey(), e.getMessage());
            }
        }
        upsertClaimDrafts(projectId, versionId,
                codeFactAgent.toClaimDrafts(projectId, versionId, result, node.getSourcePath()));
        return count;
    }

    /**
     * 读取代码节点对应的源文件内容。按 sourcePath 去重，避免同文件多节点重复抽取。
     */
    private String readCodeContent(GraphNode node, Set<String> visitedPaths) {
        String path = node.getSourcePath();
        if (path == null || path.isBlank() || !visitedPaths.add(path)) {
            return null;
        }
        try {
            Path filePath = Path.of(path);
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return null;
            }
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Failed to read code content {}: {}", path, e.getMessage());
            return null;
        }
    }

    // ==================== AI_FEATURE_CODE_MAPPING ====================

    /**
     * P1-C：将文档/代码抽取的 Feature 节点按名称相似度映射到已有的 Page/API 实现，
     * 建立 EXPOSED_BY / IMPLEMENTED_BY 边，避免 Feature 成为孤立节点。
     *
     * <p>此前仅手动接口 {@code FactController.extractDocFacts} 会调用 mapFeaturesToCode，
     * 自动扫描路径遗漏该步骤，导致自动抽出的 Feature 与代码断连。此处对齐两条路径。</p>
     */
    private void runFeatureCodeMapping(String projectId, String versionId) {
        ScanTask task = createTask(projectId, versionId, "AI_FEATURE_CODE_MAPPING", "功能到代码实现映射");
        try {
            int featureMappings = businessGraphBuilder.mapFeaturesToCode(projectId, versionId);
            // P2：业务对象 ↔ 数据库表对齐，连通业务层与技术层
            int objectMappings = businessGraphBuilder.mapBusinessObjectsToTables(projectId, versionId);
            int totalMappings = featureMappings + objectMappings;
            if (totalMappings == 0) {
                completeTask(task,
                        "⚠ 未建立 Feature/业务对象技术映射 —— 可能无 Feature/Page/API，"
                        + "或无 BusinessObject/技术实体候选",
                        null);
            } else {
                completeTask(task, "已建立 Feature→Page/API 映射 " + featureMappings
                        + " 条，业务对象技术映射 " + objectMappings + " 条", null);
            }
        } catch (Exception e) {
            log.error("AI_FEATURE_CODE_MAPPING failed: versionId={}", versionId, e);
            completeTask(task, null, e.getMessage());
        }
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
                String key = "domain:" + nonBlank(domain.getName(), domain.getDescription());
                addFact(facts, buildFact(projectId, versionId, "BUSINESS_DOMAIN", key, domain.getName(),
                        doc.getFilePath(), domain, domain.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getBusinessProcesses() != null) {
            for (DocUnderstandingAgent.BusinessProcess process : extraction.getBusinessProcesses()) {
                String key = process.getKey() != null ? process.getKey()
                        : "process:" + process.getName();
                addFact(facts, buildFact(projectId, versionId, "BUSINESS_PROCESS", key, process.getName(),
                        doc.getFilePath(), process, process.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getBusinessObjects() != null) {
            for (DocUnderstandingAgent.BusinessObject obj : extraction.getBusinessObjects()) {
                addFact(facts, buildFact(projectId, versionId, "BUSINESS_OBJECT", "object:" + obj.getName(),
                        obj.getName(), doc.getFilePath(), obj, obj.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getBusinessRules() != null) {
            for (DocUnderstandingAgent.BusinessRule rule : extraction.getBusinessRules()) {
                String key = "rule:" + nonBlank(rule.getName(), rule.getExpression());
                addFact(facts, buildFact(projectId, versionId, "BUSINESS_RULE", key, rule.getName(),
                        doc.getFilePath(), rule, rule.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getRoles() != null) {
            for (String role : extraction.getRoles()) {
                if (role == null || role.isBlank()) {
                    continue;
                }
                addFact(facts, buildFact(projectId, versionId, "BUSINESS_ROLE", "role:" + role,
                        role, doc.getFilePath(), role, 0.7, SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getStatusTransitions() != null) {
            for (DocUnderstandingAgent.StatusTransition transition : extraction.getStatusTransitions()) {
                String key = "transition:" + nonBlank(transition.getBusinessObject(), "object")
                        + ":" + nonBlank(transition.getFromStatus(), "?")
                        + "->" + nonBlank(transition.getToStatus(), "?")
                        + ":" + nonBlank(transition.getTrigger(), "");
                String name = nonBlank(transition.getBusinessObject(), "对象") + " "
                        + nonBlank(transition.getFromStatus(), "?") + " -> "
                        + nonBlank(transition.getToStatus(), "?");
                addFact(facts, buildFact(projectId, versionId, "STATUS_TRANSITION", key, name,
                        doc.getFilePath(), transition, transition.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getFeatures() != null) {
            for (String feature : extraction.getFeatures()) {
                if (feature == null || feature.isBlank()) {
                    continue;
                }
                addFact(facts, buildFact(projectId, versionId, "FEATURE", "feature:" + feature,
                        feature, doc.getFilePath(), feature, 0.7, SourceType.DOC_AI.name()));
            }
        }
        return saveAiFactsBatch(facts);
    }

    private void buildBusinessGraph(String projectId, String versionId, Document doc,
                                    DocUnderstandingAgent.BusinessFactExtraction extraction) {
        if (businessGraphBuilder == null || extraction == null) {
            return;
        }
        try {
            int beforeNodes = countGraphNodes(projectId, versionId);
            int beforeEdges = countGraphEdges(projectId, versionId);
            businessGraphBuilder.buildBusinessGraph(projectId, versionId, extraction, doc.getFilePath());
            int afterNodes = countGraphNodes(projectId, versionId);
            int afterEdges = countGraphEdges(projectId, versionId);
            graphNodeCounter.increment(afterNodes - beforeNodes);
            graphEdgeCounter.increment(afterEdges - beforeEdges);
        } catch (Exception e) {
            log.warn("Business graph build failed for doc {}: {}", doc.getId(), e.getMessage());
        }
    }

    // ==================== AI_FEATURE_MAPPING ====================

    private void runFeatureMapping(String projectId, String versionId) {
        ScanTask task = createTask(projectId, versionId, "AI_FEATURE_MAPPING", "功能映射对齐");
        try {
            // 收集全部 Feature / Page / ApiEndpoint 节点（limit=0 表示不限）
            List<GraphNode> features = neo4jGraphDao.queryNodes(projectId, versionId,
                    NodeType.Feature.name(), null, null, null, 0);
            List<GraphNode> pages = neo4jGraphDao.queryNodes(projectId, versionId,
                    NodeType.Page.name(), null, null, null, 0);
            List<GraphNode> apis = neo4jGraphDao.queryNodes(projectId, versionId,
                    NodeType.ApiEndpoint.name(), null, null, null, 0);

            if (pages.isEmpty() && apis.isEmpty()) {
                completeTask(task, "无可映射的页面/接口节点，跳过", null);
                return;
            }

            // 构建 nodeKey / nodeName(小写) → 节点 的查找表，落地时按 LLM 返回的 key 或 name 解析
            Map<String, GraphNode> featureMap = buildNodeIndex(features);
            Map<String, GraphNode> pageMap = buildNodeIndex(pages);
            Map<String, GraphNode> apiMap = buildNodeIndex(apis);

            // 分批调 LLM：一次性喂全量 Feature 会导致 LLM 输出截断（实测 347 个一次喂返回 0 条）。
            // 每批只放 FEATURE_MAPPING_BATCH_SIZE 个 Feature 作为映射锚点，Page/API 作为目标全量传入。
            String pageSummary = summarizeNodes(pages);
            String apiSummary = summarizeNodes(apis);
            // 分批并发调 LLM（复用 docExtractExecutor，此时 doc/code 抽取已结束、池空闲），
            // 避免顺序 5 批把耗时拉长。结果用 AtomicInteger 累加。
            AtomicInteger totalMappings = new AtomicInteger(0);
            AtomicInteger totalPersisted = new AtomicInteger(0);
            AtomicInteger batches = new AtomicInteger(0);
            List<CompletableFuture<Void>> batchFutures = new ArrayList<>();
            for (int i = 0; i < features.size(); i += FEATURE_MAPPING_BATCH_SIZE) {
                List<GraphNode> batch = features.subList(i,
                        Math.min(i + FEATURE_MAPPING_BATCH_SIZE, features.size()));
                batchFutures.add(CompletableFuture.runAsync(() -> {
                    FeatureMappingAgent.MappingRequest request = new FeatureMappingAgent.MappingRequest();
                    request.setProjectId(projectId);
                    request.setVueCode(pageSummary);
                    request.setApiDefinitions(apiSummary);
                    request.setControllerCode("");
                    request.setPermissionInfo("");
                    String batchFeatureSummary = summarizeNodes(batch);
                    request.setProductDoc("已有功能点:\n" + batchFeatureSummary);
                    try {
                        // 缓存特征映射结果：文档/代码稳定（cachedExtract 命中）时 Features/Pages/APIs 稳定，
                        // 请求内容哈希稳定 → 特征映射命中缓存 → 重扫结果可复现，且省掉这批 LLM 调用。
                        // 修复前 featureMappingAgent 无缓存，LLM 输出在 30-102 间摆动 3.4×，是扫描方差主因。
                        String cacheContent = projectId + "|" + pageSummary + "|" + apiSummary + "|" + batchFeatureSummary;
                        FeatureMappingAgent.MappingResult result = cachedExtract(
                                "feature-mapping",
                                cacheContent,
                                () -> {
                                    agentCallCounter.increment();
                                    return featureMappingAgent.mapFeatures(request);
                                },
                                FeatureMappingAgent.MappingResult.class,
                                r -> r == null || r.getMappings() == null || r.getMappings().isEmpty());
                        int mappingCount = result != null && result.getMappings() != null
                                ? result.getMappings().size() : 0;
                        int persisted = persistFeatureMappings(projectId, versionId, result,
                                featureMap, pageMap, apiMap);
                        totalMappings.addAndGet(mappingCount);
                        totalPersisted.addAndGet(persisted);
                        int b = batches.incrementAndGet();
                        log.info("AI feature mapping batch {}: {} mappings, {} edges persisted",
                                b, mappingCount, persisted);
                    } catch (Exception e) {
                        log.warn("AI feature mapping batch {} failed: {}", batches.get() + 1, e.getMessage());
                    }
                }, docExtractExecutor));
            }
            CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).join();
            completeTask(task, "AI 生成功能映射 " + totalMappings.get() + " 条（" + batches.get()
                    + " 批），落地 Feature→Page/API 边 " + totalPersisted.get() + " 条", null);
        } catch (Exception e) {
            log.error("AI_FEATURE_MAPPING failed: versionId={}", versionId, e);
            completeTask(task, null, e.getMessage());
        }
    }

    private int persistFeatureMappings(String projectId, String versionId,
                                       FeatureMappingAgent.MappingResult result,
                                       Map<String, GraphNode> featureMap,
                                       Map<String, GraphNode> pageMap,
                                       Map<String, GraphNode> apiMap) {
        if (result == null || result.getMappings() == null) {
            return 0;
        }
        int persisted = 0;
        for (FeatureMappingAgent.Mapping mapping : result.getMappings()) {
            if (mapping == null) {
                continue;
            }
            try {
                List<GraphEdge> edges = createPendingFeatureEdges(projectId, versionId, mapping,
                        featureMap, pageMap, apiMap);
                if (!edges.isEmpty()) {
                    persisted += edges.size();
                    // 为首条边建审核记录（一条映射只产一条审核，避免噪声）
                    createMappingReviewRecord(projectId, versionId, mapping, edges.get(0).getId());
                } else {
                    log.debug("No edge created for mapping businessAction={} pageKey={} apiKey={}",
                            mapping.getBusinessAction(), mapping.getPageKey(), mapping.getApiKey());
                }
            } catch (Exception e) {
                log.warn("Failed to persist AI feature mapping edge: {}", e.getMessage());
            }
        }
        // claim 草稿仍落库（审计/后续编译用）；direct 模式下边由上面直接写入 Neo4j
        upsertClaimDrafts(projectId, versionId,
                featureMappingAgent.toClaimDrafts(projectId, versionId, result));
        return persisted;
    }

    /**
     * 将一条 LLM 功能映射落地为 Feature→ApiEndpoint / Feature→Page 边。
     * <p>修正原实现的三处问题：
     * <ul>
     *   <li>不再要求 pageKey 与 apiKey 同时存在——有 apiKey 就建 Feature IMPLEMENTED_BY ApiEndpoint，
     *       有 pageKey 就建 Feature EXPOSED_BY Page（原实现缺一个就整条丢弃，导致 50 条映射落地 0 条）。</li>
     *   <li>边起点改为 Feature 而非 Page（原实现建 Page CALLS ApiEndpoint，Feature 业务概念根本没被连上）。</li>
     *   <li>Feature 节点按 nodeKey 与 nodeName 双重解析（Feature 的 nodeKey 来源不一：DOC_AI 用 "feature:"，
     *       CODE_AI 用 "code-feature:"，LLM 返回的可能是 name 也可能是 key）。</li>
     * </ul>
     */
    private List<GraphEdge> createPendingFeatureEdges(String projectId, String versionId,
                                                      FeatureMappingAgent.Mapping mapping,
                                                      Map<String, GraphNode> featureMap,
                                                      Map<String, GraphNode> pageMap,
                                                      Map<String, GraphNode> apiMap) throws Exception {
        List<GraphEdge> created = new ArrayList<>();
        // 解析 Feature：优先 businessAction，回退 apiKey/pageKey（LLM 偶尔不填 businessAction）
        GraphNode feature = resolveNode(featureMap, mapping.getBusinessAction(), "feature:");
        if (feature == null) {
            feature = resolveNode(featureMap, mapping.getApiKey(), "feature:");
        }
        if (feature == null) {
            feature = resolveNode(featureMap, mapping.getPageKey(), "feature:");
        }
        if (feature == null) {
            log.debug("Feature mapping dropped: feature not found for businessAction={}",
                    mapping.getBusinessAction());
            return created;
        }

        BigDecimal confidence = BigDecimal.valueOf(normalizeConfidence(mapping.getConfidence()));

        // Feature → ApiEndpoint（IMPLEMENTED_BY）
        if (mapping.getApiKey() != null && !mapping.getApiKey().isBlank()) {
            GraphNode api = resolveNode(apiMap, mapping.getApiKey(), null);
            if (api != null) {
                GraphEdge edge = upsertMappingEdge(projectId, versionId, feature, api,
                        EdgeType.IMPLEMENTED_BY.name(),
                        "ai-feature:" + feature.getNodeKey() + "->implemented_by->" + api.getNodeKey(),
                        confidence, mapping);
                if (edge != null) {
                    created.add(edge);
                }
            }
        }
        // Feature → Page（EXPOSED_BY）
        if (mapping.getPageKey() != null && !mapping.getPageKey().isBlank()) {
            GraphNode page = resolveNode(pageMap, mapping.getPageKey(), null);
            if (page != null) {
                GraphEdge edge = upsertMappingEdge(projectId, versionId, feature, page,
                        EdgeType.EXPOSED_BY.name(),
                        "ai-feature:" + feature.getNodeKey() + "->exposed_by->" + page.getNodeKey(),
                        confidence, mapping);
                if (edge != null) {
                    created.add(edge);
                }
            }
        }
        return created;
    }

    /** 构建 nodeKey + nodeName(小写) → 节点 的查找表，支持按 key 或 name 解析。 */
    private Map<String, GraphNode> buildNodeIndex(List<GraphNode> nodes) {
        Map<String, GraphNode> idx = new HashMap<>();
        if (nodes == null) {
            return idx;
        }
        for (GraphNode n : nodes) {
            if (n.getNodeKey() != null && !n.getNodeKey().isBlank()) {
                idx.putIfAbsent(n.getNodeKey(), n);
            }
            if (n.getNodeName() != null && !n.getNodeName().isBlank()) {
                idx.putIfAbsent(n.getNodeName().toLowerCase(), n);
            }
        }
        return idx;
    }

    /** 从查找表解析节点：先按原值（key），再按 prefix+原值（如 "feature:"+name），最后按小写 name。 */
    private GraphNode resolveNode(Map<String, GraphNode> map, String keyOrName, String keyPrefix) {
        if (map == null || map.isEmpty() || keyOrName == null || keyOrName.isBlank()) {
            return null;
        }
        GraphNode n = map.get(keyOrName);
        if (n != null) {
            return n;
        }
        if (keyPrefix != null) {
            n = map.get(keyPrefix + keyOrName);
            if (n != null) {
                return n;
            }
        }
        return map.get(keyOrName.toLowerCase());
    }

    /** 统一走 EvidenceGraphWriter：去重 + 证据继承 + 状态裁决。 */
    private GraphEdge upsertMappingEdge(String projectId, String versionId,
                                        GraphNode from, GraphNode to,
                                        String edgeType, String edgeKey,
                                        BigDecimal confidence,
                                        FeatureMappingAgent.Mapping mapping) throws Exception {
        GraphEdgeClaim claim = GraphEdgeClaim.builder()
                .projectId(projectId)
                .versionId(versionId)
                .fromNodeId(from.getId())
                .toNodeId(to.getId())
                .edgeType(edgeType)
                .edgeKey(edgeKey)
                .sourceType("AI_FEATURE_MAPPING")
                .confidence(confidence)
                .status("PENDING_CONFIRM")
                .properties(objectMapper.writeValueAsString(mapping))
                .build();
        return evidenceGraphWriter.upsertEdge(claim);
    }

    // ==================== AI_TEST_GENERATE ====================

    private void runTestGenerate(String projectId, String versionId) {
        ScanTask task = createTask(projectId, versionId, "AI_TEST_GENERATE", "测试用例生成");
        try {
            // 查询多种节点类型，与手动生成端点保持一致
            List<GraphNode> allNodes = new ArrayList<>();
            String[] types = {"ApiEndpoint", "Feature", "Controller"};
            for (String t : types) {
                allNodes.addAll(neo4jGraphDao.queryNodes(projectId, versionId,
                        t, null, null, null, MAX_TEST_GEN_NODES));
            }

            // P1 优化：并发调 LLM（复用 docExtractExecutor 4 路；此时 doc/code/mapping 已结束，池空闲）。
            // 原串行 60 节点逐个阻塞 ~17s/节点，实测 1044s；4 路并发预期降到 ~260-350s。
            // 不回到 8 路：注释见 orchestrate()，DeepSeek 8 路曾触发限流导致 code 抽取 138s→576s。
            AtomicInteger generated = new AtomicInteger(0);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (GraphNode node : allNodes) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        TestCaseAgent.TestGenerationRequest req = new TestCaseAgent.TestGenerationRequest();
                        req.setProjectId(projectId);
                        req.setFeatureKey(node.getNodeKey());
                        req.setFeatureName(node.getNodeName());
                        req.setApiEndpoint(node.getNodeKey());
                        // B5：从 ApiEndpoint 节点名解析真实 HTTP method（节点名形如 "POST /xyBank/unLock"），
                        // 不再对所有接口硬编码 GET，避免给 POST/PUT/DELETE 接口生成 GET 用例。
                        // Feature/Controller 节点无 method 概念，回退 GET。
                        req.setHttpMethod(resolveHttpMethod(node));

                        List<GeneratedTestCase> cases = testCaseAgent.generateTestCases(req);
                        for (GeneratedTestCase gen : cases) {
                            int idx = generated.incrementAndGet();
                            persistTestCase(projectId, versionId, node, gen, idx);
                        }
                    } catch (Exception e) {
                        log.warn("Test generation failed for node {}: {}", node.getNodeKey(), e.getMessage());
                    }
                }, docExtractExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            completeTask(task, "AI 生成测试用例 " + generated.get() + " 条", null);
        } catch (Exception e) {
            log.error("AI_TEST_GENERATE failed: versionId={}", versionId, e);
            completeTask(task, null, e.getMessage());
        }
    }

    /**
     * 从节点名解析 HTTP method。ApiEndpoint 节点名形如 "POST /xyBank/unLock"，
     * 取首个空格前的 token；非标准 method 或非 ApiEndpoint 节点回退 GET。
     */
    private String resolveHttpMethod(GraphNode node) {
        if (node == null || !"ApiEndpoint".equals(node.getNodeType())) {
            return "GET";
        }
        String name = node.getNodeName();
        if (name == null || name.isBlank()) {
            return "GET";
        }
        int sp = name.indexOf(' ');
        if (sp <= 0) {
            return "GET";
        }
        String method = name.substring(0, sp).toUpperCase(java.util.Locale.ROOT);
        return switch (method) {
            case "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS" -> method;
            default -> "GET";
        };
    }

    private void persistTestCase(String projectId, String versionId, GraphNode node,
                                 GeneratedTestCase gen, int index) {
        try {
            TestCase tc = new TestCase();
            tc.setProjectId(projectId);
            tc.setVersionId(versionId);
            tc.setCaseCode("AI-TC-" + versionId + "-" + index);
            tc.setCaseName(gen.getCaseName() != null ? gen.getCaseName() : node.getNodeName() + " 测试");
            tc.setCaseType(gen.getCaseType() != null ? gen.getCaseType().name() : "API");
            tc.setScenario(nonBlank(gen.getFeatureKey(), node.getNodeKey()));
            tc.setTargetNodeId(node.getId());
            tc.setPriority("MEDIUM");
            tc.setPreconditions(toJsonArray(gen.getPreconditions()));
            tc.setSteps(buildStructuredSteps(gen));
            tc.setExpectedResult(buildExpectedResult(gen));
            tc.setConfidence(BigDecimal.valueOf(0.7));
            tc.setStatus("ENABLED");
            tc.setGeneratedBy("LLM");
            tc.setCreatedAt(LocalDateTime.now());
            tc.setUpdatedAt(LocalDateTime.now());
            testCaseRepository.insert(tc);
        } catch (Exception e) {
            log.warn("Failed to persist generated test case: {}", e.getMessage());
        }
    }

    private String buildStructuredSteps(GeneratedTestCase gen) throws Exception {
        // steps 列为 JSONB 类型，输出 JSON 数组，元素为字符串或对象
        List<Object> steps = new ArrayList<>();
        if (gen.getSteps() != null && !gen.getSteps().isEmpty()) {
            steps.addAll(gen.getSteps());
        }
        if (gen.getRequest() != null && !gen.getRequest().isEmpty()) {
            Map<String, Object> requestStep = new HashMap<>();
            requestStep.put("action", "REQUEST");
            requestStep.put("body", gen.getRequest());
            steps.add(requestStep);
        }
        if (gen.getNeedHumanInput() != null && !gen.getNeedHumanInput().isEmpty()) {
            Map<String, Object> humanInputStep = new HashMap<>();
            humanInputStep.put("action", "NEED_HUMAN_INPUT");
            humanInputStep.put("items", gen.getNeedHumanInput());
            steps.add(humanInputStep);
        }
        return objectMapper.writeValueAsString(steps);
    }

    private String buildExpectedResult(GeneratedTestCase gen) throws Exception {
        // expected_result 列为 JSONB NOT NULL，必须输出合法 JSON
        if (gen.getAssertions() == null || gen.getAssertions().isEmpty()) {
            Map<String, Object> defaultExpected = new HashMap<>();
            defaultExpected.put("description", "验证接口返回符合预期");
            return objectMapper.writeValueAsString(defaultExpected);
        }
        return objectMapper.writeValueAsString(gen.getAssertions());
    }

    /**
     * 将 List<String> 序列化为 JSON 数组字符串，null 或空时返回 "[]"。
     * preconditions 列为 JSONB 类型，必须输出合法 JSON。
     */
    private String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ==================== AI_REVIEW_PREPARE ====================

    private void runReviewPrepare(String projectId, String versionId, double minConfidence) {
        ScanTask task = createTask(projectId, versionId, "AI_REVIEW_PREPARE", "低置信节点审核准备");
        try {
            // 拉取该版本节点，筛选低置信节点生成审核任务
            List<GraphNode> nodes = neo4jGraphDao.queryNodes(projectId, versionId,
                    null, null, null, null, MAX_REVIEW_NODES * 4);
            int created = 0;
            for (GraphNode node : nodes) {
                if (created >= MAX_REVIEW_NODES) {
                    break;
                }
                double conf = node.getConfidence() != null ? node.getConfidence().doubleValue() : 0.0;
                if (conf >= minConfidence) {
                    continue;
                }
                if (createReviewRecord(projectId, versionId, node, conf)) {
                    created++;
                }
            }
            completeTask(task, "生成低置信审核任务 " + created + " 条（阈值 " + minConfidence + "）", null);
        } catch (Exception e) {
            log.error("AI_REVIEW_PREPARE failed: versionId={}", versionId, e);
            completeTask(task, null, e.getMessage());
        }
    }

    private boolean createReviewRecord(String projectId, String versionId, GraphNode node, double confidence) {
        try {
            // 去重：同一目标已有待审核记录则跳过
            long exists = reviewRecordRepository.selectCount(
                    new LambdaQueryWrapper<ReviewRecord>()
                            .eq(ReviewRecord::getProjectId, projectId)
                            .eq(ReviewRecord::getTargetId, node.getId())
                            .eq(ReviewRecord::getStatus, "PENDING"));
            if (exists > 0) {
                return false;
            }
            ReviewRecord record = new ReviewRecord();
            record.setId(UUID.randomUUID().toString());
            record.setProjectId(projectId);
            record.setVersionId(versionId);
            record.setTargetType("NODE");
            record.setTargetId(node.getId());
            record.setTargetName(node.getNodeName());
            record.setGraphType(node.getNodeType());
            record.setConfidence(confidence);
            record.setPriority(confidence < 0.3 ? "HIGH" : "MEDIUM");
            record.setStatus("PENDING");
            record.setComment("AI 编排：低置信节点，建议人工审核");
            record.setCreatedAt(LocalDateTime.now());
            reviewRecordRepository.insert(record);
            return true;
        } catch (Exception e) {
            log.warn("Failed to create review record for node {}: {}", node.getId(), e.getMessage());
            return false;
        }
    }

    private boolean createMappingReviewRecord(String projectId, String versionId, FeatureMappingAgent.Mapping mapping, String targetId) {
        try {
            long exists = reviewRecordRepository.selectCount(
                    new LambdaQueryWrapper<ReviewRecord>()
                            .eq(ReviewRecord::getProjectId, projectId)
                            .eq(ReviewRecord::getTargetId, targetId)
                            .eq(ReviewRecord::getStatus, "PENDING"));
            if (exists > 0) {
                return false;
            }
            ReviewRecord record = new ReviewRecord();
            record.setId(UUID.randomUUID().toString());
            record.setProjectId(projectId);
            record.setVersionId(versionId);
            record.setTargetType("EDGE");
            record.setTargetId(targetId);
            record.setTargetName(mappingTargetId(mapping));
            record.setGraphType("AI_FEATURE_MAPPING");
            record.setConfidence(normalizeConfidence(mapping.getConfidence()));
            record.setPriority(mapping.getConfidence() < 0.6 ? "HIGH" : "MEDIUM");
            record.setStatus("PENDING");
            record.setComment("AI 功能映射待确认："
                    + nonBlank(mapping.getBusinessAction(), mappingTargetId(mapping))
                    + "，页面=" + nonBlank(mapping.getPageKey(), "-")
                    + "，接口=" + nonBlank(mapping.getApiKey(), "-"));
            record.setCreatedAt(LocalDateTime.now());
            reviewRecordRepository.insert(record);
            return true;
        } catch (Exception e) {
            log.warn("Failed to create review record for mapping {}: {}", mappingTargetId(mapping), e.getMessage());
            return false;
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 向量化内容分片并存储到 pgvector（非阻塞：失败不影响扫描主流程）。
     */
    private void vectorizeContent(String projectId, String versionId, String chunkType,
                                  String sourceUri, String content) {
        if (vectorizationService == null || !vectorizationService.isAvailable()) {
            return;
        }
        try {
            int stored = vectorizationService.embedDocument(
                    projectId, versionId, chunkType, sourceUri, content,
                    VECTOR_CHUNK_SIZE, VECTOR_OVERLAP, EMBEDDING_MODEL_NAME);
            if (stored > 0) {
                log.debug("Vectorized {}: {} chunks stored", sourceUri, stored);
            }
        } catch (Exception e) {
            log.debug("Vectorization skipped for {}: {}", sourceUri, e.getMessage());
        }
    }

    /** 构建 Fact（不落库）；序列化失败返回 null。供单条 saveAiFact 与批量 saveAiFactsBatch 复用。 */
    private Fact buildFact(String projectId, String versionId, String factType, String factKey,
                           String factName, String sourcePath, Object data, double confidence,
                           String sourceType) {
        try {
            Fact fact = new Fact();
            fact.setId(UUID.randomUUID().toString());
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

    /** 单条 upsert（保留给非批量场景）；批量请用 saveAiFactsBatch。 */
    private boolean saveAiFact(String projectId, String versionId, String factType, String factKey,
                               String factName, String sourcePath, Object data, double confidence,
                               String sourceType) {
        Fact fact = buildFact(projectId, versionId, factType, factKey, factName,
                sourcePath, data, confidence, sourceType);
        if (fact == null) {
            return false;
        }
        try {
            factRepository.upsert(fact);
            return true;
        } catch (Exception e) {
            log.warn("Failed to save AI fact {}: {}", factKey, e.getMessage());
            return false;
        }
    }

    /** 批量 upsert：单次 DB 往返替代逐条 upsert。返回受影响行数。 */
    private int saveAiFactsBatch(List<Fact> facts) {
        if (facts == null || facts.isEmpty()) {
            return 0;
        }
        try {
            return factRepository.batchUpsert(facts);
        } catch (Exception e) {
            log.warn("Failed to batch save {} AI facts: {}", facts.size(), e.getMessage());
            return 0;
        }
    }

    private void addFact(List<Fact> facts, Fact fact) {
        if (fact != null) {
            facts.add(fact);
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
            return documentContentService.readText(doc.getFilePath());
        } catch (Exception e) {
            log.warn("readDocContent: failed to read {}: {}", doc.getFilePath(), e.getMessage());
            return null;
        }
    }

    private String summarizeNodes(List<GraphNode> nodes) {
        StringBuilder sb = new StringBuilder();
        for (GraphNode n : nodes) {
            sb.append("- [").append(n.getNodeType()).append("] ")
                    .append(n.getNodeName() != null ? n.getNodeName() : "")
                    .append(" (").append(n.getNodeKey()).append(")\n");
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * LLM 抽取结果按内容哈希缓存：同一内容（文档/代码未改动）重扫时复用，跳过 LLM 调用。
     * cacheService 不可用（测试环境）或内容为空时直调 loader。
     */
    private <T> T cachedExtract(String type, String content, java.util.function.Supplier<T> loader,
                                Class<T> resultType, java.util.function.Predicate<T> isEmpty) {
        if (cacheService == null || content == null || content.isBlank()) {
            return loader.get();
        }
        String key = "llm:" + type + ":" + sha256(content);
        // 命中且非空才复用；空结果（LLM 返回空/解析失败）视为未命中，重新调 LLM
        T cached = cacheService.get(key, resultType);
        if (cached != null && !isEmpty.test(cached)) {
            return cached;
        }
        T loaded = loader.get();
        // 仅缓存非空结果——空结果不缓存，下次重试，避免把失败锁进缓存
        if (loaded != null && !isEmpty.test(loaded)) {
            cacheService.put(key, loaded, LLM_CACHE_TTL);
        }
        return loaded;
    }

    /** 多个 List 全为 null/空时返回 true（用于判定抽取结果是否为空）。 */
    private static boolean allEmpty(List<?>... lists) {
        for (List<?> l : lists) {
            if (l != null && !l.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(String s) {
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

    private String mappingTargetId(FeatureMappingAgent.Mapping mapping) {
        return nonBlank(mapping.getPageKey(), "-") + "->" + nonBlank(mapping.getApiKey(), "-");
    }

    private String nonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private double normalizeConfidence(double confidence) {
        if (confidence <= 0) {
            return 0.7;
        }
        return Math.min(1.0, Math.max(0.0, confidence));
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
        task.setId(UUID.randomUUID().toString());
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

    // ==================== Knowledge Claim 桥接 ====================

    private void upsertClaimDrafts(String projectId, String versionId,
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

    /**
     * 扫描结束后执行知识缺口发现（确定性 + LLM 增强）。
     */
    private void runKnowledgeGapScan(String projectId, String versionId) {
        if (gapFinderService == null) {
            log.debug("GapFinderService not available, skipping gap scan: versionId={}", versionId);
            return;
        }
        ScanTask task = createTask(projectId, versionId, "AI_GAP_FINDING", "知识缺口扫描");
        try {
            GapFinderService.GapScanResult result = gapFinderService.scanGaps(projectId, versionId);
            completeTask(task, "生成知识缺口 " + result.getCreated()
                    + " 条，重新打开 " + result.getReopened()
                    + " 条，保持 " + result.getUnchanged() + " 条", null);
        } catch (Exception e) {
            log.error("AI_GAP_FINDING failed: versionId={}", versionId, e);
            completeTask(task, null, e.getMessage());
        }
    }

    /**
     * 扫描后代码理解增强 — 提取扫描结果中优先级最高的符号，调用 ScanUnderstandingEnhancer 进行深入分析。
     * <p>
     * 增强失败不影响基础扫描状态，使用 try-catch 包裹整个增强流程。
     * </p>
     *
     * @param projectId 项目 ID
     * @param versionId 扫描版本 ID
     */
    private void runScanUnderstandingEnhancement(String projectId, String versionId) {
        if (scanUnderstandingEnhancer == null) {
            log.debug("ScanUnderstandingEnhancer not available, skipping: versionId={}", versionId);
            return;
        }
        ScanTask task = createTask(projectId, versionId, "AI_CODE_UNDERSTANDING", "扫描后代码理解增强");
        try {
            // 提取 top symbols：按复杂度/入度排序的关键节点（Service/Controller/Method）
            List<String> topSymbols = extractTopSymbols(projectId, versionId);
            if (topSymbols.isEmpty()) {
                completeTask(task, "⚠ 无关键符号需要增强（缺少 Service/Controller/Method 等高价值节点）", null);
                return;
            }

            // 调用增强器
            ScanUnderstandingEnhancer.EnhancementResult result =
                    scanUnderstandingEnhancer.enhance(projectId, versionId, topSymbols);

            String summary = result.isEnabled()
                    ? String.format("增强 %d/%d 个符号成功（失败 %d），收集 %d 条证据：%s",
                            result.getEnhancedCount(), result.getEnhancedCount() + result.getFailCount(),
                            result.getFailCount(), result.getTotalEvidence(), result.getMessage())
                    : "增强未启用：" + result.getMessage();
            completeTask(task, summary, null);
            log.info("扫描后增强完成: projectId={}, versionId={}, result={}",
                    projectId, versionId, result.getMessage());
        } catch (Exception e) {
            // 增强失败不影响基础扫描状态
            log.error("扫描后代码理解增强失败: projectId={}, versionId={}, err={}",
                    projectId, versionId, e.getMessage());
            completeTask(task, null, "增强失败（不影响基础扫描）: " + e.getMessage());
        }
    }

    /**
     * 提取扫描结果中优先级最高的符号（按复杂度/入度排序）。
     * <p>
     * 优先选择 Service、Controller、Method 类型节点，按入度（被引用次数）降序排序。
     * 取前 MAX_CODE_EXTRACT_NODES 个作为增强目标。
     * </p>
     */
    private List<String> extractTopSymbols(String projectId, String versionId) {
        List<String> symbols = new ArrayList<>();

        // 查询 Service 节点
        List<GraphNode> serviceNodes = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Service.name(), null, null, null, MAX_CODE_EXTRACT_NODES);
        // 查询 Controller 节点
        List<GraphNode> controllerNodes = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Controller.name(), null, null, null, MAX_CODE_EXTRACT_NODES);
        // 查询 Method 节点
        List<GraphNode> methodNodes = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Method.name(), null, null, null, MAX_CODE_EXTRACT_NODES);

        // 合并并按优先级排序：Service > Controller > Method，同类取前几个
        List<GraphNode> allNodes = new ArrayList<>();
        allNodes.addAll(serviceNodes);
        allNodes.addAll(controllerNodes);
        allNodes.addAll(methodNodes);

        // 按 traceCount（被追踪次数，作为复杂度/入度的代理指标）降序排序
        allNodes.sort(Comparator.<GraphNode, Long>comparing(
                n -> n.getTraceCount() != null ? n.getTraceCount() : 0L).reversed());

        // 取前 MAX_CODE_EXTRACT_NODES 个
        int limit = Math.min(allNodes.size(), MAX_CODE_EXTRACT_NODES);
        for (int i = 0; i < limit; i++) {
            GraphNode node = allNodes.get(i);
            // 优先使用 nodeKey 作为符号标识，回退到 nodeName
            String symbol = node.getNodeKey() != null ? node.getNodeKey() : node.getNodeName();
            if (symbol != null && !symbol.isBlank()) {
                symbols.add(symbol);
            }
        }

        log.info("提取扫描后增强目标: projectId={}, versionId={}, count={}, types=[Service={}, Controller={}, Method={}]",
                projectId, versionId, symbols.size(),
                serviceNodes.size(), controllerNodes.size(), methodNodes.size());
        return symbols;
    }

    /**
     * 统计指定版本的图谱节点数（用于 Prometheus 指标计算差值）
     */
    private int countGraphNodes(String projectId, String versionId) {
        try {
            return neo4jGraphDao.queryNodes(projectId, versionId, null, null, null, null, Integer.MAX_VALUE).size();
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
            return neo4jGraphDao.queryEdges(projectId, versionId, null, null, Integer.MAX_VALUE).size();
        } catch (Exception e) {
            log.debug("countGraphEdges failed: {}", e.getMessage());
            return 0;
        }
    }
}
