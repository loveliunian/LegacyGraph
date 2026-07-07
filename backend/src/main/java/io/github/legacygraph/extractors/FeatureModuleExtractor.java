package io.github.legacygraph.extractors;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 功能模块抽取器
 * 从前端目录结构识别功能模块（FeatureModule）
 * 
 * 识别模式：
 * 1. src/views/xxx/ 或 src/pages/xxx/ 目录
 * 2. 每个一级子目录视为一个功能模块
 * 3. 模块内包含的页面文件作为 Feature
 */
@Slf4j
@Component
public class FeatureModuleExtractor {

    /**
     * 功能模块事实
     */
    @Data
    public static class FeatureModuleFact {
        private String moduleName;        // 模块名称（目录名）
        private String modulePath;        // 模块路径
        private String description;       // 模块描述（从 README 或 index 推断）
        private List<String> pages;       // 包含的页面文件
        private int pageCount;            // 页面数量
    }

    /**
     * 功能点事实
     */
    @Data
    public static class FeatureFact {
        private String featureName;       // 功能名称（文件名，不含扩展名）
        private String featurePath;       // 文件路径
        private String moduleName;        // 所属模块
        private String description;       // 功能描述
    }

    /**
     * 从前端源码目录抽取功能模块
     * 
     * @param frontendRoot 前端项目根目录
     * @return 功能模块列表
     */
    public List<FeatureModuleFact> extractModules(Path frontendRoot) throws IOException {
        List<FeatureModuleFact> modules = new ArrayList<>();

        // Vue：src/views 或 src/pages
        Path modulesRoot = frontendRoot.resolve("src/views");
        if (!Files.exists(modulesRoot)) {
            modulesRoot = frontendRoot.resolve("src/pages");
        }
        // Legacy HTML 前端：frontendRoot 本身即 html 目录（如 src/main/html），一级子目录为功能模块
        if (!Files.exists(modulesRoot) && isLegacyHtmlFrontend(frontendRoot)) {
            modulesRoot = frontendRoot;
        }
        if (!Files.exists(modulesRoot)) {
            log.debug("No views/pages or legacy html dir found in {}", frontendRoot);
            return modules;
        }

        // 遍历一级子目录
        try (Stream<Path> dirs = Files.list(modulesRoot)) {
            dirs.filter(Files::isDirectory)
                .forEach(dir -> {
                    try {
                        FeatureModuleFact module = extractModule(dir);
                        if (module != null) {
                            modules.add(module);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to extract module from {}: {}", dir, e.getMessage());
                    }
                });
        }

        log.info("Extracted {} feature modules from {}", modules.size(), modulesRoot);
        return modules;
    }

    /**
     * Legacy HTML 前端判定：目录的某个一级子目录含 .html 页面
     * （排除 css/js/img 等纯资源目录，确认这是页面型前端）。
     */
    private boolean isLegacyHtmlFrontend(Path dir) {
        if (dir == null) {
            return false;
        }
        try (Stream<Path> subs = Files.list(dir)) {
            return subs.filter(Files::isDirectory).anyMatch(this::dirContainsHtmlPages);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean dirContainsHtmlPages(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> {
                String n = p.toString().toLowerCase();
                return n.endsWith(".html") || n.endsWith(".htm");
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 从模块目录抽取功能点
     * 
     * @param moduleDir 模块目录
     * @return 功能点列表
     */
    public List<FeatureFact> extractFeatures(Path moduleDir) throws IOException {
        List<FeatureFact> features = new ArrayList<>();
        String moduleName = moduleDir.getFileName().toString();

        // 遍历页面文件（.vue/.tsx/.jsx；Legacy 前端含 .html/.htm）
        try (Stream<Path> files = Files.walk(moduleDir)) {
            files.filter(p -> {
                String name = p.toString().toLowerCase();
                return name.endsWith(".vue") || name.endsWith(".tsx") || name.endsWith(".jsx")
                        || name.endsWith(".html") || name.endsWith(".htm");
            })
                 .filter(Files::isRegularFile)
                 .forEach(file -> {
                     FeatureFact feature = new FeatureFact();
                     String fileName = file.getFileName().toString();
                     // 去掉扩展名
                     String featureName = fileName.replaceAll("\\.(vue|tsx|jsx|html|htm)$", "");
                     feature.setFeatureName(featureName);
                     feature.setFeaturePath(file.toString());
                     feature.setModuleName(moduleName);
                     
                     // 尝试从文件内容推断描述
                     try {
                         String content = Files.readString(file);
                         feature.setDescription(extractDescription(content));
                     } catch (IOException e) {
                         log.debug("Failed to read {}: {}", file, e.getMessage());
                     }
                     
                     features.add(feature);
                 });
        }

        return features;
    }

    /**
     * 抽取单个模块信息
     */
    private FeatureModuleFact extractModule(Path moduleDir) throws IOException {
        FeatureModuleFact module = new FeatureModuleFact();
        module.setModuleName(moduleDir.getFileName().toString());
        module.setModulePath(moduleDir.toString());

        // 统计页面数量（.vue/.tsx/.jsx；Legacy 前端含 .html/.htm）
        List<String> pages = new ArrayList<>();
        try (Stream<Path> files = Files.walk(moduleDir)) {
            files.filter(p -> {
                String name = p.toString().toLowerCase();
                return name.endsWith(".vue") || name.endsWith(".tsx") || name.endsWith(".jsx")
                        || name.endsWith(".html") || name.endsWith(".htm");
            })
                 .filter(Files::isRegularFile)
                 .forEach(p -> pages.add(p.toString()));
        }
        // 无页面的目录（如 css/js/img 资源目录）不作为功能模块
        if (pages.isEmpty()) {
            return null;
        }
        module.setPages(pages);
        module.setPageCount(pages.size());

        // 尝试从 README / index.vue / index.html 推断描述
        Path readme = moduleDir.resolve("README.md");
        if (Files.exists(readme)) {
            String content = Files.readString(readme);
            module.setDescription(extractFirstLine(content));
        } else {
            Path indexVue = moduleDir.resolve("index.vue");
            if (Files.exists(indexVue)) {
                module.setDescription(extractDescription(Files.readString(indexVue)));
            } else {
                Path indexHtml = moduleDir.resolve("index.html");
                if (Files.exists(indexHtml)) {
                    module.setDescription(extractDescription(Files.readString(indexHtml)));
                }
            }
        }

        return module;
    }

    /**
     * 从 Vue 文件内容推断描述
     * 查找 <template> 中的标题或注释
     */
    private String extractDescription(String content) {
        // 匹配 <!-- 描述 --> 注释
        Pattern commentPattern = Pattern.compile("<!--\\s*(.+?)\\s*-->");
        Matcher matcher = commentPattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // 匹配 <h1>标题</h1> 或 <h2>标题</h2>
        Pattern titlePattern = Pattern.compile("<h[12][^>]*>([^<]+)</h[12]>");
        matcher = titlePattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return null;
    }

    /**
     * 提取第一行非空内容
     */
    private String extractFirstLine(String content) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return trimmed;
            }
        }
        return null;
    }
}
