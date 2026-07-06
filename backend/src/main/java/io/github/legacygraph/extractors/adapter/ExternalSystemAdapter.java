package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.ExternalSystemExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 外部系统适配器 — 调用 ExternalSystemExtractor 抽取外部系统调用并写入图谱。
 */
@Slf4j
@Component
public class ExternalSystemAdapter implements ExtractionAdapter {

    private final ExternalSystemExtractor extractor;
    private final GraphBuilder graphBuilder;

    public ExternalSystemAdapter(ExternalSystemExtractor extractor, GraphBuilder graphBuilder) {
        this.extractor = extractor;
        this.graphBuilder = graphBuilder;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        String path = asset.getRelativePath();
        return path != null && path.endsWith(".java") && path.contains("src/main/java");
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        try {
            List<ExternalSystemExtractor.ExternalCallFact> facts = extractor.extractFromFile(asset.getFile());
            if (!facts.isEmpty()) {
                graphBuilder.buildExternalSystemGraph(context.getProjectId(), context.getVersionId(), facts);
                log.info("Scanned {} external system calls from {}", facts.size(), asset.getRelativePath());
                return ExtractionResult.builder()
                        .processedAssets(1)
                        .nodeCount(facts.size() * 2) // ExternalSystem + ApiEndpoint
                        .summary("Scanned " + facts.size() + " external system calls")
                        .build();
            }
        } catch (IOException e) {
            log.warn("Failed to extract external system calls from {}: {}", asset.getRelativePath(), e.getMessage());
        }
        return ExtractionResult.builder().processedAssets(0).build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("ExternalSystemAdapter")
                .languages(Set.of("java"))
                .fileTypes(Set.of("java"))
                .frameworks(Set.of("spring", "resttemplate", "feign", "webclient"))
                .aiEnhanced(false)
                .priority(48)
                .build();
    }
}
