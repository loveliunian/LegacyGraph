package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.MQExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 消息队列适配器 — 调用 MQExtractor 抽取 MQ 消费者并写入图谱。
 */
@Slf4j
@Component
public class MQAdapter implements ExtractionAdapter {

    private final MQExtractor extractor;
    private final GraphBuilder graphBuilder;

    public MQAdapter(MQExtractor extractor, GraphBuilder graphBuilder) {
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
            List<MQExtractor.MQConsumerFact> facts = extractor.extractFromFile(asset.getFile());
            if (!facts.isEmpty()) {
                graphBuilder.buildMQGraph(context.getProjectId(), context.getVersionId(), facts);
                log.info("Scanned {} MQ consumers from {}", facts.size(), asset.getRelativePath());
                return ExtractionResult.builder()
                        .processedAssets(1)
                        .nodeCount(facts.size() * 2) // MQConsumer + MQTopic
                        .summary("Scanned " + facts.size() + " MQ consumers")
                        .build();
            }
        } catch (IOException e) {
            log.warn("Failed to extract MQ consumers from {}: {}", asset.getRelativePath(), e.getMessage());
        }
        return ExtractionResult.builder().processedAssets(0).build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("MQAdapter")
                .languages(Set.of("java"))
                .fileTypes(Set.of("java"))
                .frameworks(Set.of("spring", "rabbitmq", "kafka", "rocketmq"))
                .aiEnhanced(false)
                .priority(46)
                .build();
    }
}
