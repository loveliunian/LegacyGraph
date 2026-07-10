package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.ExceptionExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * 异常与日志适配器（P6-28 + P6-32）— 调用 ExceptionExtractor 抽取异常与日志事实并写入图谱。
 * <p>匹配 src/main/java 下的 .java 文件，构建 Exception/LogPoint 节点及
 * THROWS/CATCHES/LOGS 边。</p>
 */
@Slf4j
@Component
public class ExceptionAdapter implements ExtractionAdapter {

    private final ExceptionExtractor extractor;
    private final GraphBuilder graphBuilder;

    public ExceptionAdapter(ExceptionExtractor extractor, GraphBuilder graphBuilder) {
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
            ExceptionExtractor.ExceptionScanResult result = extractor.extractFromFile(asset.getFile());
            int exceptionCount = result.getExceptions().size();
            int logPointCount = result.getLogPoints().size();
            if (exceptionCount == 0 && logPointCount == 0) {
                return ExtractionResult.builder().processedAssets(0).build();
            }
            graphBuilder.buildExceptionGraph(context.getProjectId(), context.getVersionId(), result);
            log.info("Scanned {} exceptions and {} log points from {}",
                    exceptionCount, logPointCount, asset.getRelativePath());
            return ExtractionResult.builder()
                    .processedAssets(1)
                    .nodeCount(exceptionCount + logPointCount)
                    .edgeCount(exceptionCount + logPointCount)
                    .summary("Scanned " + exceptionCount + " exceptions, " + logPointCount + " log points")
                    .build();
        } catch (IOException e) {
            log.warn("Failed to extract exceptions from {}: {}", asset.getRelativePath(), e.getMessage());
            return ExtractionResult.builder().processedAssets(0).build();
        }
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("ExceptionAdapter")
                .languages(Set.of("java"))
                .fileTypes(Set.of("java"))
                .frameworks(Set.of("spring", "slf4j", "logback"))
                .aiEnhanced(false)
                .priority(48)
                .build();
    }
}
