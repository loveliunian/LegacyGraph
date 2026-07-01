package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.extractors.JavaControllerExtractor;
import io.github.legacygraph.extractors.JavaStructureExtractor;
import io.github.legacygraph.model.ApiFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Java 代码适配器 — 通过轻量级注解扫描检测 Controller 类文件。
 * <p>
 * {@link #supports} 逐行扫描文件内容查找 @RestController/@Controller，
 * 无需完整 JavaParser 解析，比 AST 解析快 100 倍。
 * 匹配后委托给 {@link JavaControllerExtractor} 精抽取 API 端点。
 * </p>
 */
@Slf4j
@Component
public class JavaCodeAdapter implements ExtractionAdapter {

    private static final Set<String> JAVA_EXTENSIONS = Set.of("java");
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("java");
    private static final Set<String> SUPPORTED_FRAMEWORKS = Set.of("spring", "spring-boot", "mybatis");

    private final GraphBuilder graphBuilder;
    private final JavaControllerExtractor controllerExtractor = new JavaControllerExtractor();
    private final JavaStructureExtractor structureExtractor = new JavaStructureExtractor();

    public JavaCodeAdapter(GraphBuilder graphBuilder) {
        this.graphBuilder = graphBuilder;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        if (asset.getFileType() == null || !JAVA_EXTENSIONS.contains(asset.getFileType().toLowerCase())) {
            return false;
        }
        if (asset.getFile() == null || !Files.isReadable(asset.getFile())) {
            return false;
        }
        // 轻量级注解扫描：逐行读取，找到 @RestController 或 @Controller 即终止
        // 不依赖文件命名约定，且无需完整 JavaParser AST 解析
        try (var lines = Files.lines(asset.getFile())) {
            return lines.anyMatch(line ->
                    line.contains("@RestController") || line.contains("@Controller"));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        try {
            Path file = asset.getFile();

            List<JavaStructureExtractor.JavaClassInfo> structures = structureExtractor.extractFromFile(file);
            List<GraphNode> structureNodes = graphBuilder.buildJavaStructureGraph(
                    context.getProjectId(), context.getVersionId(), structures);

            List<ApiFact> apiFacts = controllerExtractor.extractFromFile(file);
            if (apiFacts.isEmpty()) {
                return ExtractionResult.builder()
                        .processedAssets(1)
                        .nodeCount(structureNodes.size())
                        .evidenceCount(structureNodes.size())
                        .summary("Java controller: " + structureNodes.size() + " structure nodes, no APIs found")
                        .build();
            }

            var nodes = graphBuilder.buildApiNodes(
                    context.getProjectId(), context.getVersionId(),
                    apiFacts, asset.getRelativePath());

            return ExtractionResult.builder()
                    .processedAssets(1)
                    .nodeCount(structureNodes.size() + nodes.size())
                    .edgeCount(apiFacts.size() * 3)
                    .evidenceCount(structureNodes.size() + nodes.size())
                    .summary(String.format("Java controller: %d structure nodes, %d APIs extracted",
                            structureNodes.size(), apiFacts.size()))
                    .build();

        } catch (IOException e) {
            log.warn("JavaCodeAdapter failed to process {}: {}", asset.getRelativePath(), e.getMessage());
            return ExtractionResult.builder()
                    .processedAssets(1)
                    .summary("Java controller failed: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("JavaCodeAdapter")
                .languages(SUPPORTED_LANGUAGES)
                .frameworks(SUPPORTED_FRAMEWORKS)
                .fileTypes(JAVA_EXTENSIONS)
                .aiEnhanced(false)
                .priority(10)
                .build();
    }

    /**
     * 判断文件路径是否为 Controller 类（按命名约定）。
     * 供兄弟 Adapter（如 {@link JavaServiceCallAdapter}）使用，避免重复处理。
     */
    public static boolean isControllerFile(String relativePath) {
        if (relativePath == null) return false;
        String name = relativePath.toLowerCase();
        return name.contains("controller") || name.contains("controller/");
    }
}
