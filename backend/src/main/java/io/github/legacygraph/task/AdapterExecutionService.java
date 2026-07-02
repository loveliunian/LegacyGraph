package io.github.legacygraph.task;

import io.github.legacygraph.extractors.adapter.*;
import io.github.legacygraph.entity.ScanTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    public AdapterExecutionService(ExtractionAdapterRegistry adapterRegistry,
                                   ScanTaskRecorder taskRecorder) {
        this.adapterRegistry = adapterRegistry;
        this.taskRecorder = taskRecorder;
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
                .config(Map.of())
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

            // 并发处理：虚拟线程，I/O 阻塞时自动切换，充分利用 CPU
            AtomicInteger visited = new AtomicInteger(0);
            AtomicInteger processed = new AtomicInteger(0);

            List<Callable<Void>> tasks = files.stream()
                    .<Callable<Void>>map(file -> () -> {
                        if (cancelChecker != null && cancelChecker.get()) {
                            return null;
                        }
                        try {
                            SourceAsset asset = toSourceAsset(root, file);
                            var adapter = adapterRegistry.selectAdapter(context, asset);
                            if (adapter.isEmpty()) {
                                return null;
                            }
                            try {
                                ExtractionResult result = adapter.get().extract(context, asset);
                                if (result != null && result.getProcessedAssets() > 0) {
                                    processed.addAndGet(result.getProcessedAssets());
                                } else {
                                    processed.incrementAndGet();
                                }
                            } catch (Exception e) {
                                log.warn("Adapter {} failed for {}: {}",
                                        adapter.get().capability().getName(), asset.getRelativePath(), e.getMessage());
                            }
                        } catch (Exception e) {
                            log.debug("Skip file {}: {}", file, e.getMessage());
                        } finally {
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

            taskRecorder.completeTask(task, "Adapter processed " + processed.get() + " assets", null);
            return processed.get();
        } catch (Exception e) {
            log.warn("Adapter scan failed for {}: {}", baseDir, e.getMessage());
            taskRecorder.completeTask(task, "Adapter scan failed", e.getMessage());
            return 0;
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
