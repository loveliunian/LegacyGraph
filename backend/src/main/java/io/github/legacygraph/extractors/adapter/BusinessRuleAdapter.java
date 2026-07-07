package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.BusinessRuleExtractor;
import io.github.legacygraph.model.NodeExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class BusinessRuleAdapter implements ExtractionAdapter {

    private final BusinessRuleExtractor extractor;
    private final GraphBuilder graphBuilder;

    public BusinessRuleAdapter(BusinessRuleExtractor extractor, GraphBuilder graphBuilder) {
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
                graphBuilder.buildBusinessRuleGraph(context.getProjectId(), context.getVersionId(), results);
                log.info("Scanned {} business rules from {}", results.size(), asset.getRelativePath());
                return ExtractionResult.builder()
                        .processedAssets(1)
                        .nodeCount(results.size())
                        .summary("Scanned " + results.size() + " business rules")
                        .build();
            }
        } catch (Exception e) {
            log.warn("Failed to extract business rules from {}: {}", asset.getRelativePath(), e.getMessage());
        }
        return ExtractionResult.builder().processedAssets(0).build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("BusinessRuleAdapter")
                .languages(Set.of("java"))
                .fileTypes(Set.of("java"))
                .frameworks(Set.of("spring", "hibernate", "javax-validation"))
                .aiEnhanced(false)
                .priority(65)
                .build();
    }
}
