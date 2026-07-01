package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.extractors.DocumentExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.util.Set;

/**
 * 文档适配器（doc 3.2）— 抽取 md/pdf/word/txt 文本并落 Fact，
 * 供后续 AI 文档理解（{@code DocUnderstandingAgent}）消费。
 */
@Slf4j
@Component
public class DocumentAdapter implements ExtractionAdapter {

    private static final Set<String> DOC_EXTENSIONS = Set.of("md", "pdf", "docx", "txt", "rst", "adoc");

    private final FactPersister factPersister;
    private final DocumentExtractor documentExtractor = new DocumentExtractor();

    public DocumentAdapter(FactPersister factPersister) {
        this.factPersister = factPersister;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        return asset.getFileType() != null
                && DOC_EXTENSIONS.contains(asset.getFileType().toLowerCase())
                && asset.getFile() != null;
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        File file = asset.getFile().toFile();
        try {
            String text = documentExtractor.extractText(file);
            String excerpt = text != null && text.length() > 1000 ? text.substring(0, 1000) : text;
            factPersister.saveFact(context.getProjectId(), context.getVersionId(),
                    "DOCUMENT", "DOC", asset.getRelativePath(),
                    asset.getFile().getFileName().toString(),
                    asset.getRelativePath(), null, null,
                    excerpt, BigDecimal.ONE, "EXTRACTED");
            return ExtractionResult.builder()
                    .processedAssets(1)
                    .evidenceCount(1)
                    .summary("Document: " + asset.getRelativePath())
                    .build();
        } catch (Exception e) {
            log.warn("DocumentAdapter failed for {}: {}", asset.getRelativePath(), e.getMessage());
            return ExtractionResult.builder().processedAssets(1).summary("Document failed: " + e.getMessage()).build();
        }
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("DocumentAdapter")
                .languages(Set.of("markdown", "text"))
                .frameworks(Set.of())
                .fileTypes(DOC_EXTENSIONS)
                .aiEnhanced(false)
                .priority(50)
                .build();
    }
}
