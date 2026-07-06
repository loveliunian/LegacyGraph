package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.ConfigItemExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 配置项适配器 — 调用 ConfigItemExtractor 抽取配置项并写入图谱。
 */
@Slf4j
@Component
public class ConfigItemAdapter implements ExtractionAdapter {

    private final ConfigItemExtractor extractor;
    private final GraphBuilder graphBuilder;

    public ConfigItemAdapter(ConfigItemExtractor extractor, GraphBuilder graphBuilder) {
        this.extractor = extractor;
        this.graphBuilder = graphBuilder;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        String path = asset.getRelativePath();
        return path != null && (path.endsWith(".yml") || path.endsWith(".yaml") 
                || path.endsWith(".properties")
                || (path.endsWith(".java") && path.contains("src/main/java")));
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        try {
            String path = asset.getRelativePath();
            if (path.endsWith(".yml") || path.endsWith(".yaml")) {
                List<ConfigItemExtractor.ConfigItemFact> facts = extractor.extractFromYaml(asset.getFile());
                if (!facts.isEmpty()) {
                    graphBuilder.buildConfigItemGraph(context.getProjectId(), context.getVersionId(), facts);
                    log.info("Scanned {} config items from YAML: {}", facts.size(), path);
                    return ExtractionResult.builder()
                            .processedAssets(1)
                            .nodeCount(facts.size())
                            .summary("Scanned " + facts.size() + " config items from YAML")
                            .build();
                }
            } else if (path.endsWith(".java")) {
                List<ConfigItemExtractor.ConfigItemFact> facts = extractor.extractFromJavaFile(asset.getFile());
                if (!facts.isEmpty()) {
                    graphBuilder.buildConfigItemGraph(context.getProjectId(), context.getVersionId(), facts);
                    log.info("Scanned {} config items from @Value: {}", facts.size(), path);
                    return ExtractionResult.builder()
                            .processedAssets(1)
                            .nodeCount(facts.size())
                            .summary("Scanned " + facts.size() + " config items from @Value")
                            .build();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to extract config items from {}: {}", asset.getRelativePath(), e.getMessage());
        }
        return ExtractionResult.builder().processedAssets(0).build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("ConfigItemAdapter")
                .languages(Set.of("java", "yaml"))
                .fileTypes(Set.of("java", "yml", "yaml", "properties"))
                .frameworks(Set.of("spring"))
                .aiEnhanced(false)
                .priority(40)
                .build();
    }
}
