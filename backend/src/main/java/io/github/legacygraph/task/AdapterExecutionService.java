package io.github.legacygraph.task;

import io.github.legacygraph.config.GraphWriteConfig;
import io.github.legacygraph.dto.claim.CompileOptions;
import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.extractors.adapter.*;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.service.graph.KnowledgeCompiler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.concurrent.Semaphore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 适配器执行服务 — 统一使用 Adapter Registry 做扫描抽取。
 * <p>
 * 负责：扫描文件发现 → Adapter 选择 → 并发执行 → 失败隔离 → 结果汇总。
 * 一次扫描中所有结构化抽取都应通过此服务，不直接实例化 Extractor。
 * </p>
 *
 * @see ExtractionAdapterRegistry
 */
@Slf4j
@Component
public class AdapterExecutionService {

    private final ExtractionAdapterRegistry adapterRegistry;
    private final ScanTaskRecorder taskRecorder;
    private final KnowledgeClaimService knowledgeClaimService;
    private final KnowledgeCompiler knowledgeCompiler;
    private final GraphWriteConfig graphWriteConfig;

    public AdapterExecutionService(ExtractionAdapterRegistry adapterRegistry,
                                   ScanTaskRecorder taskRecorder) {
        this(adapterRegistry, taskRecorder, null, null, null);
    }

    @Autowired
    public AdapterExecutionService(ExtractionAdapterRegistry adapterRegistry,
                                   ScanTaskRecorder taskRecorder,
                                   KnowledgeClaimService knowledgeClaimService,
                                   KnowledgeCompiler knowledgeCompiler,
                                   GraphWriteConfig graphWriteConfig) {
        this.adapterRegistry = adapterRegistry;
        this.taskRecorder = taskRecorder;
        this.knowledgeClaimService = knowledgeClaimService;
        this.knowledgeCompiler = knowledgeCompiler;
        this.graphWriteConfig = graphWriteConfig;
    }

    /**
     * 在指定目录下使用 Adapter Registry 执行扫描抽取。
     *
     * @param projectId  项目ID
     * @param versionId  版本ID
     * @param baseDir    项目根目录
     * @param backendDir 后端子目录
     * @param frontendDir 前端子目录
     * @param cancelChecker 取消检查器（可空）
     * @return 处理的资产数量
     */
    public int executeScan(String projectId, String versionId,
                            String baseDir, String backendDir, String frontendDir,
                            java.util.function.Supplier<Boolean> cancelChecker) {
        if (baseDir == null || adapterRegistry == null) {
            return 0;
        }
        Path root = Paths.get(baseDir);
        if (!Files.exists(root)) {
            return 0;
        }
        ScanContext context = ScanContext.builder()
                .projectId(projectId)
                .versionId(versionId)
                .baseDir(baseDir)
                .backendDir(backendDir)
                .frontendDir(frontendDir)
                .config(new java.util.concurrent.ConcurrentHashMap<>())
                .build();

        ScanTask task = taskRecorder.createTask(projectId, versionId, "ADAPTER_SCAN", "适配器抽取扫描");

        try {
            // 排除巨型目录 + 仅收集有适配器能处理的文件类型
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile)
                        .filter(p -> {
                            String pathStr = p.toString();
                            return !pathStr.contains("/node_modules/") && !pathStr.contains("/.git/")
                                    && !pathStr.contains("/target/") && !pathStr.contains("/dist/")
                                    && !pathStr.contains("/build/") && !pathStr.contains("/__pycache__/")
                                    && !pathStr.contains("/.idea/") && !pathStr.contains("/.vscode/");
                        })
                        .filter(this::isAdapterCandidate)
                        .collect(Collectors.toList());
            }

            int total = files.size();
            if (total == 0) {
                taskRecorder.logProgress(task, 0, 0, "adapter candidate files");
                taskRecorder.completeTask(task, "No adapter candidate files found", null);
                return 0;
            }
            taskRecorder.logProgress(task, 0, total, "adapter candidate files");

            // M11修复：添加 Semaphore 并发限制，防止虚拟线程过多导致资源耗尽
            final int MAX_CONCURRENT = Math.min(50, files.size()); // 最多 50 并发
            Semaphore semaphore = new Semaphore(MAX_CONCURRENT);
            AtomicInteger visited = new AtomicInteger(0);
            AtomicInteger processed = new AtomicInteger(0);
            Queue<KnowledgeClaimDraft> claimDrafts = new ConcurrentLinkedQueue<>();

            List<Callable<Void>> tasks = files.stream()
                    .<Callable<Void>>map(file -> () -> {
                        if (cancelChecker != null && cancelChecker.get()) {
                            return null;
                        }
                        try {
                            semaphore.acquire(); // 获取许可
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                        try {
                            SourceAsset asset = toSourceAsset(root, file);
                            var adapters = adapterRegistry.selectAdapters(context, asset);
                            for (ExtractionAdapter adapter : adapters) {
                                try {
                                    ExtractionResult result = adapter.extract(context, asset);
                                    if (result != null) {
                                        processed.addAndGet(Math.max(0, result.getProcessedAssets()));
                                        collectClaimDrafts(result, claimDrafts);
                                    }
                                } catch (Exception e) {
                                    log.warn("Adapter {} failed for {}: {}",
                                            adapter.capability().getName(), asset.getRelativePath(), e.getMessage());
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Skip file {}: {}", file, e.getMessage());
                        } finally {
                            semaphore.release(); // 释放许可
                            int v = visited.incrementAndGet();
                            if (v % getProgressLogInterval() == 0 || v == total) {
                                taskRecorder.logProgress(task, v, total, "adapter candidate files");
                            }
                        }
                        return null;
                    })
                    .toList();

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.invokeAll(tasks);
            }

            compileClaimDraftsIfConfigured(context, claimDrafts);
            taskRecorder.completeTask(task, "Adapter processed " + processed.get() + " assets", null);
            return processed.get();
        } catch (Exception e) {
            log.warn("Adapter scan failed for {}: {}", baseDir, e.getMessage());
            taskRecorder.completeTask(task, "Adapter scan failed", e.getMessage());
            return 0;
        }
    }

    /**
     * 对已发现的资产列表执行并发扫描抽取（取代 ProjectScanner 中的顺序循环）。
     *
     * @param context              扫描上下文
     * @param assets               已发现的资产列表（可能被截断）
     * @param discoveredCount      实际发现的资产总数（截断前，用于进度统计）
     * @param task                 关联的子任务（用于进度记录）
     * @param cancelChecker        取消检查器（可空）
     * @param incremental          是否增量模式（跳过未变更资产）
     * @param assetDiscoveryService 资产发现服务（用于增量跳过判断，可空）
     * @return 成功处理的资产数量
     */
    public int executeDiscoveredAssets(ScanContext context,
                                       List<SourceAsset> assets,
                                       int discoveredCount,
                                       ScanTask task,
                                       java.util.function.Supplier<Boolean> cancelChecker,
                                       boolean incremental,
                                       AssetDiscoveryService assetDiscoveryService) {
        if (context == null || assets == null || assets.isEmpty() || adapterRegistry == null) {
            taskRecorder.logProgress(task, 0, 0, "adapter candidate files");
            return 0;
        }

        int total = assets.size();
        // 使用实际发现的总数作为进度统计的 totalItems
        int progressTotal = discoveredCount > 0 ? discoveredCount : total;
        taskRecorder.logProgress(task, 0, progressTotal, "adapter candidate files", null);
        // 统一并发配置：默认 16（与 ProjectScanner 保持一致）
        int maxConcurrency = Math.max(1, Integer.getInteger("legacy-graph.scan.adapter-concurrency", 16));
        Semaphore semaphore = new Semaphore(maxConcurrency);
        AtomicInteger visited = new AtomicInteger(0);
        AtomicInteger processed = new AtomicInteger(0);
        Queue<KnowledgeClaimDraft> claimDrafts = new ConcurrentLinkedQueue<>();

        List<Callable<Void>> calls = assets.stream()
                .<Callable<Void>>map(asset -> () -> {
                    if (cancelChecker != null && cancelChecker.get()) {
                        return null;
                    }
                    try {
                        semaphore.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    try {
                        if (incremental && assetDiscoveryService != null
                                && assetDiscoveryService.isIncrementalSkip(asset, context.getProjectId(), context.getVersionId())) {
                            return null;
                        }
                        var adapters = adapterRegistry.selectAdapters(context, asset);
                        for (var adapter : adapters) {
                            try {
                                ExtractionResult result = adapter.extract(context, asset);
                                if (result != null) {
                                    processed.addAndGet(Math.max(0, result.getProcessedAssets()));
                                    collectClaimDrafts(result, claimDrafts);
                                }
                            } catch (Exception ex) {
                                log.warn("Adapter {} failed for asset {}: {}",
                                        adapter.capability().getName(),
                                        asset.getRelativePath(), ex.getMessage());
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("Adapter failed for discovered asset {}: {}",
                                asset.getRelativePath(), ex.getMessage());
                    } finally {
                        semaphore.release();
                        int done = visited.incrementAndGet();
                        taskRecorder.logProgress(task, done, progressTotal, "adapter candidate files", null);
                    }
                    return null;
                })
                .toList();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.invokeAll(calls);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        compileClaimDraftsIfConfigured(context, claimDrafts);
        return processed.get();
    }

    private void collectClaimDrafts(ExtractionResult result, Queue<KnowledgeClaimDraft> claimDrafts) {
        if (result.getClaimDrafts() == null || result.getClaimDrafts().isEmpty()) {
            return;
        }
        claimDrafts.addAll(result.getClaimDrafts());
    }

    private void compileClaimDraftsIfConfigured(ScanContext context, Queue<KnowledgeClaimDraft> claimDrafts) {
        if (graphWriteConfig == null
                || (!graphWriteConfig.isShadowMode() && !graphWriteConfig.isClaimCompilerMode())
                || knowledgeClaimService == null
                || knowledgeCompiler == null
                || claimDrafts.isEmpty()) {
            return;
        }
        List<KnowledgeClaimDraft> drafts = List.copyOf(claimDrafts);
        try {
            knowledgeClaimService.upsertDrafts(drafts);
            CompileOptions options = CompileOptions.builder()
                    .dryRun(graphWriteConfig.isShadowMode())
                    .includePending(graphWriteConfig.isCompilerIncludePending())
                    .minConfidence(graphWriteConfig.getCompilerMinConfidence())
                    .build();
            knowledgeCompiler.compile(context.getProjectId(), context.getVersionId(), options);
        } catch (Exception e) {
            log.warn("Claim compiler write-mode hook failed for projectId={}, versionId={}: {}",
                    context.getProjectId(), context.getVersionId(), e.getMessage());
        }
    }

    /** 判断文件是否有潜在适配器能处理 */
    private boolean isAdapterCandidate(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".vue")
                || name.endsWith(".ts") || name.endsWith(".js") || name.endsWith(".md")
                || name.endsWith(".txt") || name.endsWith(".rst") || name.endsWith(".adoc")
                || name.endsWith(".py") || name.endsWith(".yml") || name.endsWith(".yaml")
                || name.endsWith(".properties") || name.endsWith(".sql");
    }

    /** 从文件路径构造 SourceAsset */
    private SourceAsset toSourceAsset(Path root, Path file) {
        String relPath = root.relativize(file).toString();
        String fileName = file.getFileName().toString();
        String fileType = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";
        return SourceAsset.builder()
                .file(file)
                .relativePath(relPath)
                .fileType(fileType)
                .language(fileType.equals("java") ? "java" : fileType)
                .build();
    }

    private int getProgressLogInterval() {
        return 10; // matches PROGRESS_LOG_INTERVAL from ProjectScanner
    }
}
