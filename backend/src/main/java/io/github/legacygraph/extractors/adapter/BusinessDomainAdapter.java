package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.BusinessDomainExtractor;
import io.github.legacygraph.model.NodeExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class BusinessDomainAdapter implements ExtractionAdapter {

    private final BusinessDomainExtractor extractor;
    private final GraphBuilder graphBuilder;

    public BusinessDomainAdapter(BusinessDomainExtractor extractor, GraphBuilder graphBuilder) {
        this.extractor = extractor;
        this.graphBuilder = graphBuilder;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        // BusinessDomainExtractor 扫描目录而非单文件
        return false;
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        return ExtractionResult.builder().processedAssets(0).build();
    }

    /**
     * 目录级扫描入口（由 ProjectScanner 直接调用）
     */
    public ExtractionResult extractFromDirectory(File sourceDir, String projectId, String versionId) {
        List<NodeExtractionResult> results = extractor.extract(sourceDir);
        if (!results.isEmpty()) {
            graphBuilder.buildBusinessDomainGraph(projectId, versionId, results);
            log.info("Scanned {} business domains from {}", results.size(), sourceDir);
            return ExtractionResult.builder()
                    .processedAssets(results.size())
                    .nodeCount(results.size())
                    .summary("Scanned " + results.size() + " business domains")
                    .build();
        }
        return ExtractionResult.builder().processedAssets(0).build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("BusinessDomainAdapter")
                .languages(Set.of("java"))
                .fileTypes(Set.of("java"))
                .frameworks(Set.of("spring", "ddd"))
                .aiEnhanced(false)
                .priority(55)
                .build();
    }
}
