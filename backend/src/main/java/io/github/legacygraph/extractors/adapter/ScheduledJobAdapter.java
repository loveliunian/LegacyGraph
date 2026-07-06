package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.ScheduledJobExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 定时任务适配器 — 调用 ScheduledJobExtractor 抽取定时任务并写入图谱。
 */
@Slf4j
@Component
public class ScheduledJobAdapter implements ExtractionAdapter {

    private final ScheduledJobExtractor extractor;
    private final GraphBuilder graphBuilder;

    public ScheduledJobAdapter(ScheduledJobExtractor extractor, GraphBuilder graphBuilder) {
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
            List<ScheduledJobExtractor.ScheduledJobFact> facts = extractor.extractFromFile(asset.getFile());
            if (!facts.isEmpty()) {
                graphBuilder.buildScheduledJobGraph(context.getProjectId(), context.getVersionId(), facts);
                log.info("Scanned {} scheduled jobs from {}", facts.size(), asset.getRelativePath());
                return ExtractionResult.builder()
                        .processedAssets(1)
                        .nodeCount(facts.size())
                        .summary("Scanned " + facts.size() + " scheduled jobs")
                        .build();
            }
        } catch (IOException e) {
            log.warn("Failed to extract scheduled jobs from {}: {}", asset.getRelativePath(), e.getMessage());
        }
        return ExtractionResult.builder().processedAssets(0).build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("ScheduledJobAdapter")
                .languages(Set.of("java"))
                .fileTypes(Set.of("java"))
                .frameworks(Set.of("spring", "xxl-job"))
                .aiEnhanced(false)
                .priority(45)
                .build();
    }
}
