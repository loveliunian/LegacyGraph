package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.SecurityExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * 安全风险适配器（v6.0 P8：SECURITY_AUDIT）— 调用 SecurityExtractor 抽取安全风险事实并写入图谱。
 * <p>匹配 src/main/java 下的 .java 文件，构建 SecurityRisk 节点及 HAS_RISK 边。</p>
 */
@Slf4j
@Component
public class SecurityAdapter implements ExtractionAdapter {

    private final SecurityExtractor extractor;
    private final GraphBuilder graphBuilder;

    public SecurityAdapter(SecurityExtractor extractor, GraphBuilder graphBuilder) {
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
            SecurityExtractor.SecurityScanResult result = extractor.extractFromFile(asset.getFile());
            int riskCount = result.getRisks().size();
            if (riskCount == 0) {
                return ExtractionResult.builder().processedAssets(0).build();
            }
            graphBuilder.buildSecurityGraph(context.getProjectId(), context.getVersionId(), result);
            log.info("Scanned {} security risks from {}", riskCount, asset.getRelativePath());
            return ExtractionResult.builder()
                    .processedAssets(1)
                    .nodeCount(riskCount)
                    .edgeCount(riskCount)
                    .summary("Scanned " + riskCount + " security risks")
                    .build();
        } catch (IOException e) {
            log.warn("Failed to extract security risks from {}: {}", asset.getRelativePath(), e.getMessage());
            return ExtractionResult.builder().processedAssets(0).build();
        }
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("SecurityAdapter")
                .languages(Set.of("java"))
                .fileTypes(Set.of("java"))
                .frameworks(Set.of("spring"))
                .aiEnhanced(false)
                .priority(46)
                .build();
    }
}
