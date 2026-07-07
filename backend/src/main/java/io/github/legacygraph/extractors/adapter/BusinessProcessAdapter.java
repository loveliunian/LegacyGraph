package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.BusinessProcessExtractor;
import io.github.legacygraph.model.NodeExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class BusinessProcessAdapter implements ExtractionAdapter {

    private final BusinessProcessExtractor extractor;
    private final GraphBuilder graphBuilder;

    public BusinessProcessAdapter(BusinessProcessExtractor extractor, GraphBuilder graphBuilder) {
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
            List<NodeExtractionResult> results = extractor.extract(asset.getFile().toFile());
            if (!results.isEmpty()) {
                graphBuilder.buildBusinessProcessGraph(context.getProjectId(), context.getVersionId(), results);
                log.info("Scanned {} business processes from {}", results.size(), asset.getRelativePath());
                return ExtractionResult.builder()
                        .processedAssets(1)
                        .nodeCount(results.size())
                        .summary("Scanned " + results.size() + " business processes")
                        .build();
            }
        } catch (Exception e) {
            log.warn("Failed to extract business processes from {}: {}", asset.getRelativePath(), e.getMessage());
        }
        return ExtractionResult.builder().processedAssets(0).build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("BusinessProcessAdapter")
                .languages(Set.of("java"))
                .fileTypes(Set.of("java"))
                .frameworks(Set.of("spring", "bpm", "workflow"))
                .aiEnhanced(false)
                .priority(70)
                .build();
    }
}
