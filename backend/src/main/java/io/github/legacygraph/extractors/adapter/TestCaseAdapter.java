package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.TestCaseExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 测试用例适配器 — 调用 TestCaseExtractor 抽取测试用例并写入图谱。
 */
@Slf4j
@Component
public class TestCaseAdapter implements ExtractionAdapter {

    private final TestCaseExtractor extractor;
    private final GraphBuilder graphBuilder;

    public TestCaseAdapter(TestCaseExtractor extractor, GraphBuilder graphBuilder) {
        this.extractor = extractor;
        this.graphBuilder = graphBuilder;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        String path = asset.getRelativePath();
        return path != null && (path.endsWith("Test.java") || path.endsWith("Tests.java")
                || path.contains("/test/") || path.contains("/tests/"));
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        try {
            List<TestCaseExtractor.TestCaseFact> facts = extractor.extractFromFile(asset.getFile());
            if (!facts.isEmpty()) {
                graphBuilder.buildTestCaseGraph(context.getProjectId(), context.getVersionId(), facts);
                log.info("Scanned {} test cases from {}", facts.size(), asset.getRelativePath());
                return ExtractionResult.builder()
                        .processedAssets(1)
                        .nodeCount(facts.size() * 2) // TestCase + Assertion
                        .summary("Scanned " + facts.size() + " test cases")
                        .build();
            }
        } catch (IOException e) {
            log.warn("Failed to extract test cases from {}: {}", asset.getRelativePath(), e.getMessage());
        }
        return ExtractionResult.builder().processedAssets(0).build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("TestCaseAdapter")
                .languages(Set.of("java"))
                .fileTypes(Set.of("java"))
                .frameworks(Set.of("junit"))
                .aiEnhanced(false)
                .priority(50)
                .build();
    }
}
