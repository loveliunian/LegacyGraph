package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.FeatureModuleExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 功能模块适配器 — 调用 FeatureModuleExtractor 从前端目录结构抽取功能模块并写入图谱。
 * <p>
 * 与其他 Adapter 不同，此 Adapter 是目录级扫描（基于 src/views 或 src/pages 目录），
 * 不是逐文件扫描。当遇到前端目录下的 index.vue 文件时触发，避免重复扫描。
 * </p>
 */
@Slf4j
@Component
public class FeatureModuleAdapter implements ExtractionAdapter {

    private final FeatureModuleExtractor extractor;
    private final GraphBuilder graphBuilder;

    public FeatureModuleAdapter(FeatureModuleExtractor extractor, GraphBuilder graphBuilder) {
        this.extractor = extractor;
        this.graphBuilder = graphBuilder;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        String path = asset.getRelativePath();
        if (path == null) return false;
        // Vue：views/<module>/index.vue 或 pages/<module>/index.vue
        if (path.endsWith("index.vue") && path.matches(".*(/views/|/pages/)[^/]+/index\\.vue$")) {
            return true;
        }
        // Legacy HTML 前端：src/main/html 下的 .js 触发目录级扫描
        // （.html 未被 isAdapterCandidate 收录，借已发现的 .js 资产作触发；extract 内按 html 根目录去重）
        if (path.endsWith(".js") && path.contains("src/main/html/")) {
            return true;
        }
        // 兼容：若后续将 .html 纳入候选，顶层 .html 也可触发
        if ((path.endsWith(".html") || path.endsWith(".htm"))
                && path.matches(".*src/main/html/[^/]+\\.(html|htm)$")) {
            return true;
        }
        return false;
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        try {
            // 确定前端根目录
            Path frontendRoot = resolveFrontendRoot(context, asset);
            if (frontendRoot == null || !Files.exists(frontendRoot)) {
                return ExtractionResult.builder().processedAssets(0).build();
            }
            // 同一前端根目录只扫一次（多个顶层 .html / 多个 index.vue 都会触发 supports）
            if (!markRootScanned(context, frontendRoot)) {
                return ExtractionResult.builder().processedAssets(0).build();
            }

            List<FeatureModuleExtractor.FeatureModuleFact> modules = extractor.extractModules(frontendRoot);
            if (modules.isEmpty()) {
                return ExtractionResult.builder().processedAssets(0).build();
            }

            // 抽取每个模块下的功能点
            java.util.List<FeatureModuleExtractor.FeatureFact> allFeatures = new java.util.ArrayList<>();
            for (var module : modules) {
                Path moduleDir = Paths.get(module.getModulePath());
                if (Files.exists(moduleDir)) {
                    List<FeatureModuleExtractor.FeatureFact> features = extractor.extractFeatures(moduleDir);
                    allFeatures.addAll(features);
                }
            }

            graphBuilder.buildFeatureModuleGraph(context.getProjectId(), context.getVersionId(), modules, allFeatures);
            log.info("Scanned {} feature modules with {} features", modules.size(), allFeatures.size());

            return ExtractionResult.builder()
                    .processedAssets(1)
                    .nodeCount(modules.size() + allFeatures.size())
                    .summary("Scanned " + modules.size() + " feature modules, " + allFeatures.size() + " features")
                    .build();
        } catch (IOException e) {
            log.warn("Failed to extract feature modules: {}", e.getMessage());
        }
        return ExtractionResult.builder().processedAssets(0).build();
    }

    /**
     * 从当前 asset 路径推断前端根目录。
     * Vue（src/views|pages）返回项目根；Legacy HTML（src/main/html）返回 html 目录本身。
     */
    private Path resolveFrontendRoot(ScanContext context, SourceAsset asset) {
        if (context.getFrontendDir() != null) {
            return Paths.get(context.getBaseDir(), context.getFrontendDir());
        }
        String path = asset.getRelativePath();
        if (path == null || asset.getFile() == null) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        String[] segments = normalized.split("/");
        // Vue: src/views 或 src/pages —— 返回项目根（src 的父目录）
        for (int i = 0; i < segments.length - 1; i++) {
            boolean isFrontendSource = "src".equals(segments[i])
                    && i + 1 < segments.length
                    && ("views".equals(segments[i + 1]) || "pages".equals(segments[i + 1]));
            if (isFrontendSource) {
                Path root = asset.getFile().toAbsolutePath();
                int levelsToRoot = segments.length - i;
                for (int level = 0; level < levelsToRoot; level++) {
                    root = root.getParent();
                }
                return root;
            }
        }
        // Legacy HTML: src/main/html —— 返回 html 目录本身
        for (int i = 2; i < segments.length; i++) {
            if ("html".equals(segments[i])
                    && "main".equals(segments[i - 1]) && "src".equals(segments[i - 2])) {
                Path htmlDir = asset.getFile().toAbsolutePath();
                int levelsFromFileToHtml = segments.length - 1 - i;
                for (int level = 0; level < levelsFromFileToHtml; level++) {
                    htmlDir = htmlDir.getParent();
                }
                return htmlDir;
            }
        }
        return null;
    }

    /**
     * 标记某前端根目录已扫描。返回 true 表示首次（应继续），false 表示已扫过（跳过，避免重复）。
     * 退化：context 无 config 或 config 不可变时返回 true（不 dedup，结果仍幂等）。
     */
    @SuppressWarnings("unchecked")
    private boolean markRootScanned(ScanContext context, Path root) {
        Map<String, Object> cfg = context.getConfig();
        if (cfg == null) {
            return true;
        }
        try {
            Set<Path> scanned = (Set<Path>) cfg.computeIfAbsent(
                    "featureModule.scannedRoots",
                    k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
            return scanned.add(root.toAbsolutePath().normalize());
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("FeatureModuleAdapter")
                .languages(Set.of("javascript", "typescript"))
                .fileTypes(Set.of("vue"))
                .frameworks(Set.of("vue", "react"))
                .aiEnhanced(false)
                .priority(35)
                .build();
    }
}
