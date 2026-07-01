package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.FrontendGraphBuilder;
import io.github.legacygraph.extractors.FrontendApiExtractor;
import io.github.legacygraph.extractors.VueRouteExtractor;
import io.github.legacygraph.model.FrontendPageFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/**
 * 前端 Vue/JSX/TSX 适配器（doc 3.2）— 抽取路由页面与 API 调用事实并写图。
 */
@Slf4j
@Component
public class VueFrontendAdapter implements ExtractionAdapter {

    private static final Set<String> FRONTEND_EXTENSIONS = Set.of("vue", "jsx", "tsx");

    private final FrontendGraphBuilder frontendGraphBuilder;
    private final FactPersister factPersister;
    private final VueRouteExtractor vueExtractor = new VueRouteExtractor();
    private final FrontendApiExtractor apiExtractor = new FrontendApiExtractor();

    public VueFrontendAdapter(FrontendGraphBuilder frontendGraphBuilder, FactPersister factPersister) {
        this.frontendGraphBuilder = frontendGraphBuilder;
        this.factPersister = factPersister;
    }

    @Override
    public boolean supports(ScanContext context, SourceAsset asset) {
        return asset.getFileType() != null
                && FRONTEND_EXTENSIONS.contains(asset.getFileType().toLowerCase())
                && asset.getFile() != null && Files.isReadable(asset.getFile());
    }

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        int count = 0;
        try {
            List<FrontendPageFact> pages = vueExtractor.extractFromFile(asset.getFile());
            if (pages != null && !pages.isEmpty()) {
                for (FrontendPageFact page : pages) {
                    factPersister.saveFact(context.getProjectId(), context.getVersionId(),
                            "FRONTEND_AST", "FRONTEND_PAGE", page.getRoutePath(), page.getPageName(),
                            asset.getRelativePath(), page.getStartLine(), page.getEndLine(),
                            page, BigDecimal.ONE, "EXTRACTED");
                    count++;
                }
                frontendGraphBuilder.buildFrontendGraph(
                        context.getProjectId(), context.getVersionId(), pages, asset.getRelativePath());
            }
            List<FrontendPageFact.FrontendApiCall> apiCalls = apiExtractor.extractFromFile(asset.getFile());
            if (apiCalls != null && !apiCalls.isEmpty()) {
                for (FrontendPageFact.FrontendApiCall api : apiCalls) {
                    factPersister.saveFact(context.getProjectId(), context.getVersionId(),
                            "FRONTEND_AST", "FRONTEND_API", api.getUrl(),
                            api.getMethod() + " " + api.getUrl(),
                            asset.getRelativePath(), api.getLineNumber(), api.getLineNumber(),
                            api, BigDecimal.ONE, "EXTRACTED");
                    count++;
                }
                frontendGraphBuilder.buildFrontendApiGraph(
                        context.getProjectId(), context.getVersionId(), apiCalls);
            }
        } catch (IOException e) {
            log.warn("VueFrontendAdapter failed for {}: {}", asset.getRelativePath(), e.getMessage());
        }
        return ExtractionResult.builder()
                .processedAssets(1)
                .evidenceCount(count)
                .summary("Frontend: " + count + " pages/APIs")
                .build();
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("VueFrontendAdapter")
                .languages(Set.of("javascript"))
                .frameworks(Set.of("vue", "react"))
                .fileTypes(FRONTEND_EXTENSIONS)
                .aiEnhanced(false)
                .priority(40)
                .build();
    }
}
