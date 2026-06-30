package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.EvidenceGraphWriter;
import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.JavaControllerExtractor;
import io.github.legacygraph.extractors.ServiceCallExtractor;
import io.github.legacygraph.model.ApiFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Java 代码适配器 — 处理 Java Controller/Service 等 Java 源文件。
 * <p>
 * 委托给现有的 JavaControllerExtractor 和 ServiceCallExtractor，
 * 结果通过 EvidenceGraphWriter 写入图谱。
 * </p>
 */
@Slf4j
@Component
public class JavaCodeAdapter implements ExtractionAdapter {

    private static final Set<String> JAVA_EXTENSIONS = Set.of("java");
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("java");
    private static final Set<String> SUPPORTED_FRAMEWORKS = Set.of("spring", "spring-boot", "mybatis");

    private final GraphBuilder graphBuilder;

    public JavaCodeAdapter(GraphBuilder graphBuilder) {
        this.graphBuilder = graphBuilder;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        if (asset.getFileType() == null || !JAVA_EXTENSIONS.contains(asset.getFileType().toLowerCase())) {
            return false;
        }
        // 检查文件是否存在并可读
        return asset.getFile() != null && Files.isReadable(asset.getFile());
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        int nodeCount = 0;
        int edgeCount = 0;

        try {
            Path file = asset.getFile();

            // 1. 抽取 Controller 接口
            JavaControllerExtractor controllerExtractor = new JavaControllerExtractor();
            List<ApiFact> apiFacts = controllerExtractor.extractFromFile(file);
            if (!apiFacts.isEmpty()) {
                var nodes = graphBuilder.buildApiNodes(
                        context.getProjectId(), context.getVersionId(),
                        apiFacts, asset.getRelativePath());
                nodeCount += nodes.size();
                edgeCount += apiFacts.size() * 3; // 估算：Controller+ApiEndpoint+Method + 2边
            }

            // 2. 抽取 Service 调用关系（仅关键文件）
            ServiceCallExtractor callExtractor = new ServiceCallExtractor();
            List<ServiceCallExtractor.CallRelation> calls = callExtractor.extractFromFile(file.toFile());
            if (!calls.isEmpty()) {
                graphBuilder.buildServiceCallGraph(
                        context.getProjectId(), context.getVersionId(), calls);
                edgeCount += calls.size();
            }

        } catch (IOException e) {
            log.warn("JavaCodeAdapter failed to process {}: {}", asset.getRelativePath(), e.getMessage());
        }

        String summary = String.format("Java: %d APIs extracted, ~%d edges created",
                nodeCount / 3, edgeCount);

        return ExtractionResult.builder()
                .processedAssets(1)
                .nodeCount(nodeCount)
                .edgeCount(edgeCount)
                .evidenceCount(nodeCount) // 每个节点一个证据
                .summary(summary)
                .build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("JavaCodeAdapter")
                .languages(SUPPORTED_LANGUAGES)
                .frameworks(SUPPORTED_FRAMEWORKS)
                .fileTypes(JAVA_EXTENSIONS)
                .aiEnhanced(false)
                .priority(10)
                .build();
    }
}
