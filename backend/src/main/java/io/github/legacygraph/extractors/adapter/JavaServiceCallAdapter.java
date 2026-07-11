package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.extractors.JavaStructureExtractor;
import io.github.legacygraph.extractors.PackageExtractor;
import io.github.legacygraph.extractors.ServiceCallExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/**
 * Java Service/Mapper/Dao 调用关系适配器（doc 3.2）。
 */
@Slf4j
@Component
public class JavaServiceCallAdapter implements ExtractionAdapter {

    private static final Set<String> JAVA_EXTENSIONS = Set.of("java");

    private final GraphBuilder graphBuilder;
    private final FactPersister factPersister;
    private final ServiceCallExtractor callExtractor;
    private final JavaStructureExtractor structureExtractor;
    private final PackageExtractor packageExtractor;

    public JavaServiceCallAdapter(GraphBuilder graphBuilder, FactPersister factPersister,
                                   JavaStructureExtractor structureExtractor,
                                   PackageExtractor packageExtractor) {
        this.graphBuilder = graphBuilder;
        this.factPersister = factPersister;
        this.structureExtractor = structureExtractor;
        this.packageExtractor = packageExtractor;
        this.callExtractor = new ServiceCallExtractor();
    }

    /**
     * 设置源码根目录，启用 SymbolSolver。
     * 由 ExtractionAdapterRegistry 在扫描开始前调用。
     */
    public void setSourceRoot(java.io.File sourceRoot) {
        this.callExtractor.setSourceRoot(sourceRoot);
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        if (asset.getFileType() == null || !JAVA_EXTENSIONS.contains(asset.getFileType().toLowerCase())) {
            return false;
        }
        // Controller 文件交给 JavaCodeAdapter，避免重复（按内容判断，与 JavaCodeAdapter.supports 一致）
        String content = asset.getCachedContent();
        if (content != null) {
            if (content.contains("@RestController") || content.contains("@Controller")) {
                return false;
            }
        } else if (asset.getFile() != null && Files.isReadable(asset.getFile())) {
            try (var lines = Files.lines(asset.getFile())) {
                if (lines.anyMatch(line -> line.contains("@RestController") || line.contains("@Controller"))) {
                    return false;
                }
            } catch (IOException e) {
                // 读取失败时不排除，让 JavaServiceCallAdapter 处理
            }
        }
        // 不依赖文件命名，基于 AST 实际内容判定调用关系
        return asset.getFile() != null && Files.isReadable(asset.getFile());
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        int structureNodeCount = 0;

        // 结构提取独立 try-catch：JavaParser AST 遍历（findAll）偶发 RuntimeException 不应阻断后续提取
        try {
            List<JavaStructureExtractor.JavaClassInfo> structures = structureExtractor.extractFromFile(asset.getFile(), asset.getCachedContent());
            List<GraphNode> structureNodes = graphBuilder.buildJavaStructureGraph(
                    context.getProjectId(), context.getVersionId(), structures);
            graphBuilder.buildPackageGraph(
                    context.getProjectId(), context.getVersionId(),
                    packageExtractor.extract(structures));
            structureNodeCount = structureNodes.size();
        } catch (Exception e) {
            log.warn("Failed to extract structure from {}: {}", asset.getRelativePath(), e.getMessage());
        }

        // SERVICE_CALL 提取和保存独立 try-catch：即使结构提取失败，调用关系仍应保存
        int callEdgeCount = 0;
        try {
            List<ServiceCallExtractor.CallRelation> calls = callExtractor.extractFromFile(asset.getFile().toFile());
            if (!calls.isEmpty()) {
                factPersister.saveFacts(calls.stream()
                        .map(call -> FactPersister.FactDraft.builder()
                                .projectId(context.getProjectId())
                                .versionId(context.getVersionId())
                                .sourceType("CODE_AST")
                                .factType("SERVICE_CALL")
                                .factKey(call.getCallerClass() + "." + call.getCallerMethod())
                                .factName(call.getCallerClass() + " -> " + call.getTargetClass() + "." + call.getTargetMethod())
                                .sourcePath(asset.getRelativePath())
                                .startLine(call.getLineNumber())
                                .endLine(call.getLineNumber())
                                .data(call)
                                .confidence(BigDecimal.ONE)
                                .status("EXTRACTED")
                                .build())
                        .toList());
                graphBuilder.buildServiceCallGraph(context.getProjectId(), context.getVersionId(), calls);
                callEdgeCount = (int) calls.stream()
                        .filter(c -> c.getTargetClass() != null)
                        .count();
            }
        } catch (Exception e) {
            log.warn("Failed to extract service calls from {}: {}", asset.getRelativePath(), e.getMessage());
        }

        return ExtractionResult.builder()
                .processedAssets(1)
                .nodeCount(structureNodeCount)
                .edgeCount(callEdgeCount)
                .evidenceCount(structureNodeCount + callEdgeCount)
                .summary(String.format("Java service: %d structure nodes, %d calls extracted",
                        structureNodeCount, callEdgeCount))
                .build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("JavaServiceCallAdapter")
                .languages(Set.of("java"))
                .frameworks(Set.of("spring", "spring-boot", "mybatis"))
                .fileTypes(JAVA_EXTENSIONS)
                .aiEnhanced(false)
                .priority(20)
                .build();
    }
}
