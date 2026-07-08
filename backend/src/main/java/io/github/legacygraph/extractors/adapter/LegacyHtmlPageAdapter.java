package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.FrontendGraphBuilder;
import io.github.legacygraph.model.FrontendPageFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 传统 HTML 前端页面适配器。
 *
 * <p>处理 src/main/html/** 下的 .html 文件（传统 JSP/jQuery 项目），
 * 每个 .html 文件创建一个 Page 节点，用于后续 Feature→Page 映射。</p>
 *
 * <p>与 VueFrontendAdapter 的区别：不做路由解析、不做 API 抽取、不做按钮/权限提取。
 * 仅保证传统项目的前端页面在图谱中有对应的 Page 节点，消除"Page=1"的覆盖盲区。</p>
 */
@Slf4j
@Component
public class LegacyHtmlPageAdapter implements ExtractionAdapter {

    private static final Set<String> HTML_EXTENSIONS = Set.of("html", "htm");

    private final FrontendGraphBuilder frontendGraphBuilder;

    public LegacyHtmlPageAdapter(FrontendGraphBuilder frontendGraphBuilder) {
        this.frontendGraphBuilder = frontendGraphBuilder;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        String path = asset.getRelativePath();
        if (path == null) return false;
        String ext = extension(path);
        if (!HTML_EXTENSIONS.contains(ext)) return false;
        // 仅处理 src/main/html 下的 HTML 文件（传统项目前端目录）
        return path.replace('\\', '/').contains("src/main/html/");
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        // 以发现的第一个 .html 文件为触发点，批量扫描整个 html 目录。
        // 同一项目只扫一次（多个 .html 文件都会触发 supports，但 extract 按目录去重）。
        Path htmlRoot = findHtmlRoot(asset);
        if (htmlRoot == null || !Files.exists(htmlRoot)) {
            return ExtractionResult.builder().processedAssets(1)
                    .summary("Legacy HTML: html root not found").build();
        }

        // 同一根目录只扫一次
        String rootKey = htmlRoot.toString();
        if (!markRootScanned(context, rootKey)) {
            return ExtractionResult.builder().processedAssets(0).build();
        }

        List<FrontendPageFact> pages = scanHtmlFiles(htmlRoot);
        if (pages.isEmpty()) {
            return ExtractionResult.builder().processedAssets(1)
                    .summary("Legacy HTML: 0 pages found under " + htmlRoot).build();
        }

        frontendGraphBuilder.buildFrontendGraph(
                context.getProjectId(), context.getVersionId(), pages);

        log.info("Legacy HTML adapter: {} pages created from {}", pages.size(), htmlRoot);
        return ExtractionResult.builder()
                .processedAssets(pages.size())
                .nodeCount(pages.size())
                .summary("Legacy HTML: " + pages.size() + " pages")
                .build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("LegacyHtmlPageAdapter")
                .languages(Set.of("html"))
                .fileTypes(HTML_EXTENSIONS)
                .aiEnhanced(false)
                .priority(25) // 低于 VueFrontendAdapter(10)，高于通用适配器
                .build();
    }

    // ─── 内部方法 ───

    /** 从 asset 路径向上查找 src/main/html 根目录 */
    private Path findHtmlRoot(SourceAsset asset) {
        Path file = asset.getFile();
        if (file == null) return null;
        // 向上遍历找到 src/main/html
        for (Path p = file.getParent(); p != null; p = p.getParent()) {
            if (p.endsWith("src/main/html") || p.endsWith("src/main/html/")) {
                return p;
            }
            // 兼容：路径中包含 src/main/html 但不是直接父目录
            if (p.toString().replace('\\', '/').endsWith("src/main/html")) {
                return p;
            }
        }
        return null;
    }

    /** 递归扫描 html 根目录下所有 .html/.htm 文件，构造 FrontendPageFact */
    private List<FrontendPageFact> scanHtmlFiles(Path htmlRoot) {
        List<FrontendPageFact> pages = new ArrayList<>();
        try (var stream = Files.walk(htmlRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> {
                        String name = f.getFileName().toString().toLowerCase();
                        return name.endsWith(".html") || name.endsWith(".htm");
                    })
                    .forEach(f -> {
                        String relPath = htmlRoot.relativize(f).toString();
                        String pageName = pageName(relPath);
                        FrontendPageFact fact = new FrontendPageFact();
                        fact.setPageName(pageName);
                        fact.setTitle(pageName);
                        fact.setRoutePath("/" + relPath.replace('\\', '/'));
                        fact.setComponentPath(f.toString());
                        pages.add(fact);
                    });
        } catch (IOException e) {
            log.warn("Failed to scan HTML files under {}: {}", htmlRoot, e.getMessage());
        }
        return pages;
    }

    /** 从相对路径生成页面名：html_xy/unLock.html → "unLock" */
    private static String pageName(String relPath) {
        String name = relPath.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name;
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 ? path.substring(dot + 1).toLowerCase() : "";
    }

    // ─── 去重：同一 html 根目录只扫一次 ───

    private static final Set<String> scannedRoots = Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<>());

    private static boolean markRootScanned(ScanContext context, String rootKey) {
        String fullKey = context.getProjectId() + "|" + context.getVersionId() + "|" + rootKey;
        return scannedRoots.add(fullKey);
    }
}
