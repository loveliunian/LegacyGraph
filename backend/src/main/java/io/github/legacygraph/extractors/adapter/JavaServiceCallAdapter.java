package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.extractors.JavaStructureExtractor;
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

    public JavaServiceCallAdapter(GraphBuilder graphBuilder, FactPersister factPersister,
                                   JavaStructureExtractor structureExtractor) {
        this.graphBuilder = graphBuilder;
        this.factPersister = factPersister;
        this.structureExtractor = structureExtractor;
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
        // Controller 文件交给 JavaCodeAdapter，避免重复
        if (JavaCodeAdapter.isControllerFile(asset.getRelativePath())) {
            return false;
        }
        // 不依赖文件命名，基于 AST 实际内容判定调用关系
        return asset.getFile() != null && Files.isReadable(asset.getFile());
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        try {
            List<JavaStructureExtractor.JavaClassInfo> structures = structureExtractor.extractFromFile(asset.getFile());
            List<GraphNode> structureNodes = graphBuilder.buildJavaStructureGraph(
                    context.getProjectId(), context.getVersionId(), structures);

            List<ServiceCallExtractor.CallRelation> calls = callExtractor.extractFromFile(asset.getFile().toFile());
            if (calls.isEmpty()) {
                return ExtractionResult.builder()
                        .processedAssets(1)
                        .nodeCount(structureNodes.size())
                        .evidenceCount(structureNodes.size())
                        .summary("Java service: " + structureNodes.size() + " structure nodes, no calls")
                        .build();
            }
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
            return ExtractionResult.builder()
                    .processedAssets(1)
                    .nodeCount(structureNodes.size())
                    .edgeCount(calls.size())
                    .evidenceCount(structureNodes.size() + calls.size())
                    .summary("Java service: " + structureNodes.size() + " structure nodes, "
                            + calls.size() + " calls")
                    .build();
        } catch (IOException e) {
            log.warn("JavaServiceCallAdapter failed for {}: {}", asset.getRelativePath(), e.getMessage());
            return ExtractionResult.builder().processedAssets(1).summary("Java service failed: " + e.getMessage()).build();
        }
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
