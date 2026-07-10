package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.ConcurrencyExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * 事务与并发适配器（v6.0 P9：CONCURRENCY）— 调用 ConcurrencyExtractor 抽取事务与并发事实并写入图谱。
 * <p>匹配 src/main/java 下的 .java 文件，将事务/并发属性写入 Method 节点 properties，
 * 并构建 TransactionScope 节点及 Method --BOUND_BY--> TransactionScope 边。</p>
 */
@Slf4j
@Component
public class ConcurrencyAdapter implements ExtractionAdapter {

    private final ConcurrencyExtractor extractor;
    private final GraphBuilder graphBuilder;

    public ConcurrencyAdapter(ConcurrencyExtractor extractor, GraphBuilder graphBuilder) {
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
            ConcurrencyExtractor.ConcurrencyScanResult result = extractor.extractFromFile(asset.getFile());
            int factCount = result.getFacts().size();
            if (factCount == 0) {
                return ExtractionResult.builder().processedAssets(0).build();
            }
            graphBuilder.buildConcurrencyGraph(context.getProjectId(), context.getVersionId(), result);
            log.info("Scanned {} concurrency facts from {}", factCount, asset.getRelativePath());
            return ExtractionResult.builder()
                    .processedAssets(1)
                    .nodeCount(factCount)
                    .edgeCount(factCount)
                    .summary("Scanned " + factCount + " concurrency facts")
                    .build();
        } catch (IOException e) {
            log.warn("Failed to extract concurrency facts from {}: {}", asset.getRelativePath(), e.getMessage());
            return ExtractionResult.builder().processedAssets(0).build();
        }
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("ConcurrencyAdapter")
                .languages(Set.of("java"))
                .fileTypes(Set.of("java"))
                .frameworks(Set.of("spring"))
                .aiEnhanced(false)
                .priority(47)
                .build();
    }
}
