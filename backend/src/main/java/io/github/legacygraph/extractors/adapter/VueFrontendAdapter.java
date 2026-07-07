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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 前端 Vue/JSX/TSX 适配器（doc 3.2）— 抽取路由页面与 API 调用事实并写图。
 */
@Slf4j
@Component
public class VueFrontendAdapter implements ExtractionAdapter {

    private static final Set<String> FRONTEND_EXTENSIONS = Set.of("vue", "jsx", "tsx", "ts", "js");

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
            List<FactPersister.FactDraft> drafts = new ArrayList<>();
            List<FrontendPageFact> pages = vueExtractor.extractFromFile(asset.getFile());
            List<FrontendPageFact.FrontendButton> buttons = List.of();
            if (asset.getFileType().equalsIgnoreCase("vue")) {
                buttons = apiExtractor.extractButtonsFromVue(asset.getFile());
            }
            List<FrontendPageFact.FrontendApiCall> apiCalls = apiExtractor.extractFromFile(asset.getFile());

            if ((pages == null || pages.isEmpty()) && shouldCreateSyntheticPage(asset, buttons, apiCalls)) {
                pages = List.of(createSyntheticPage(asset));
            }
            if (pages != null && !pages.isEmpty()) {
                for (FrontendPageFact page : pages) {
                    if (!buttons.isEmpty()) {
                        page.setButtons(buttons);
                    }
                    if (apiCalls != null && !apiCalls.isEmpty()) {
                        page.setApiCalls(apiCalls);
                    }
                }
                for (FrontendPageFact page : pages) {
                    drafts.add(FactPersister.FactDraft.builder()
                            .projectId(context.getProjectId())
                            .versionId(context.getVersionId())
                            .sourceType("FRONTEND_AST")
                            .factType("FRONTEND_PAGE")
                            .factKey(page.getRoutePath())
                            .factName(page.getPageName())
                            .sourcePath(asset.getRelativePath())
                            .startLine(page.getStartLine())
                            .endLine(page.getEndLine())
                            .data(page)
                            .confidence(BigDecimal.ONE)
                            .status("EXTRACTED")
                            .build());
                    count++;
                }
                frontendGraphBuilder.buildFrontendGraph(
                        context.getProjectId(), context.getVersionId(), pages, asset.getRelativePath());
            }
            // 抽取按钮（仅对 .vue 文件）
            if (buttons != null && !buttons.isEmpty()) {
                for (FrontendPageFact.FrontendButton button : buttons) {
                    drafts.add(FactPersister.FactDraft.builder()
                            .projectId(context.getProjectId())
                            .versionId(context.getVersionId())
                            .sourceType("FRONTEND_AST")
                            .factType("FRONTEND_BUTTON")
                            .factKey(button.getText() + "#" + button.getLineNumber())
                            .factName(button.getText())
                            .sourcePath(asset.getRelativePath())
                            .startLine(button.getLineNumber())
                            .endLine(button.getLineNumber())
                            .data(button)
                            .confidence(BigDecimal.ONE)
                            .status("EXTRACTED")
                            .build());
                    count++;
                }
            }
            
            if (apiCalls != null && !apiCalls.isEmpty()) {
                for (FrontendPageFact.FrontendApiCall api : apiCalls) {
                    drafts.add(FactPersister.FactDraft.builder()
                            .projectId(context.getProjectId())
                            .versionId(context.getVersionId())
                            .sourceType("FRONTEND_AST")
                            .factType("FRONTEND_API")
                            .factKey(api.getUrl())
                            .factName(api.getMethod() + " " + api.getUrl())
                            .sourcePath(asset.getRelativePath())
                            .startLine(api.getLineNumber())
                            .endLine(api.getLineNumber())
                            .data(api)
                            .confidence(BigDecimal.ONE)
                            .status("EXTRACTED")
                            .build());
                    count++;
                }
                frontendGraphBuilder.buildFrontendApiGraph(
                        context.getProjectId(), context.getVersionId(), apiCalls);
            }
            factPersister.saveFacts(drafts);
        } catch (IOException e) {
            log.warn("VueFrontendAdapter failed for {}: {}", asset.getRelativePath(), e.getMessage());
        }
        return ExtractionResult.builder()
                .processedAssets(1)
                .evidenceCount(count)
                .summary("Frontend: " + count + " pages/APIs")
                .build();
    }

    private boolean shouldCreateSyntheticPage(SourceAsset asset,
            List<FrontendPageFact.FrontendButton> buttons,
            List<FrontendPageFact.FrontendApiCall> apiCalls) {
        return asset.getFileType() != null
                && asset.getFileType().equalsIgnoreCase("vue")
                && ((buttons != null && !buttons.isEmpty()) || (apiCalls != null && !apiCalls.isEmpty()));
    }

    private FrontendPageFact createSyntheticPage(SourceAsset asset) {
        FrontendPageFact page = new FrontendPageFact();
        String componentPath = asset.getRelativePath() != null
                ? asset.getRelativePath()
                : asset.getFile().toString();
        String routePath = inferRoutePath(componentPath);
        page.setRoutePath(routePath);
        page.setRouteName(routePath.replace("/", "_").replaceAll("^_+", ""));
        page.setPageName(page.getRouteName());
        page.setTitle(page.getRouteName());
        page.setComponentPath(componentPath);
        return page;
    }

    private String inferRoutePath(String componentPath) {
        String normalized = componentPath.replace('\\', '/');
        String marker = "/src/views/";
        int markerIdx = normalized.indexOf(marker);
        if (markerIdx < 0 && normalized.startsWith("src/views/")) {
            markerIdx = -marker.length() + "src/views/".length();
        }
        if (markerIdx < 0) {
            marker = "/src/pages/";
            markerIdx = normalized.indexOf(marker);
            if (markerIdx < 0 && normalized.startsWith("src/pages/")) {
                markerIdx = -marker.length() + "src/pages/".length();
            }
        }

        String route;
        if (markerIdx >= 0) {
            int start = markerIdx + marker.length();
            if (markerIdx < 0) {
                start = marker.replace("/src/", "src/").length();
            }
            route = normalized.substring(start);
        } else if (normalized.startsWith("src/views/")) {
            route = normalized.substring("src/views/".length());
        } else if (normalized.startsWith("src/pages/")) {
            route = normalized.substring("src/pages/".length());
        } else {
            route = Path.of(normalized).getFileName().toString();
        }
        route = route.replaceAll("/index\\.vue$", "");
        route = route.replaceAll("\\.(vue|jsx|tsx|ts|js)$", "");
        if (!route.startsWith("/")) {
            route = "/" + route;
        }
        return route;
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("VueFrontendAdapter")
                .languages(Set.of("javascript", "typescript"))
                .frameworks(Set.of("vue", "react", "angular"))
                .fileTypes(FRONTEND_EXTENSIONS)
                .aiEnhanced(false)
                .priority(40)
                .build();
    }
}
