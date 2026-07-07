package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.BusinessObjectExtractor;
import io.github.legacygraph.model.NodeExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class BusinessObjectAdapter implements ExtractionAdapter {

    private final BusinessObjectExtractor extractor;
    private final GraphBuilder graphBuilder;

    public BusinessObjectAdapter(BusinessObjectExtractor extractor, GraphBuilder graphBuilder) {
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
                graphBuilder.buildBusinessObjectGraph(context.getProjectId(), context.getVersionId(), results);
                log.info("Scanned {} business objects from {}", results.size(), asset.getRelativePath());
                return ExtractionResult.builder()
                        .processedAssets(1)
                        .nodeCount(results.size())
                        .summary("Scanned " + results.size() + " business objects")
                        .build();
            }
        } catch (Exception e) {
            log.warn("Failed to extract business objects from {}: {}", asset.getRelativePath(), e.getMessage());
        }
        return ExtractionResult.builder().processedAssets(0).build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("BusinessObjectAdapter")
                .languages(Set.of("java"))
                .fileTypes(Set.of("java"))
                .frameworks(Set.of("spring", "ddd", "jpa", "mybatis"))
                .aiEnhanced(false)
                .priority(60)
                .build();
    }
}
