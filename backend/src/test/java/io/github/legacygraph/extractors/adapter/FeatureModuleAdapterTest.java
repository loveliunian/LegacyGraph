package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.FeatureModuleExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeatureModuleAdapterTest {

    @TempDir
    Path tempDir;

    @Mock
    private FeatureModuleExtractor extractor;

    @Mock
    private GraphBuilder graphBuilder;

    @Test
    void extractInfersFrontendRootAboveSrcViews() throws Exception {
        Path frontendRoot = tempDir.resolve("frontend");
        Path moduleDir = frontendRoot.resolve("src/views/orders");
        Files.createDirectories(moduleDir);
        Path indexVue = moduleDir.resolve("index.vue");
        Files.writeString(indexVue, "<template>Orders</template>");

        FeatureModuleExtractor.FeatureModuleFact module = new FeatureModuleExtractor.FeatureModuleFact();
        module.setModuleName("orders");
        module.setModulePath(moduleDir.toString());
        module.setPageCount(1);

        when(extractor.extractModules(frontendRoot)).thenReturn(List.of(module));
        when(extractor.extractFeatures(moduleDir)).thenReturn(List.of());

        FeatureModuleAdapter adapter = new FeatureModuleAdapter(extractor, graphBuilder);
        ScanContext context = ScanContext.builder()
                .projectId("project-1")
                .versionId("v1")
                .baseDir(tempDir.toString())
                .build();
        SourceAsset asset = SourceAsset.builder()
                .file(indexVue)
                .relativePath("frontend/src/views/orders/index.vue")
                .fileType("vue")
                .build();

        ExtractionResult result = adapter.extract(context, asset);

        assertEquals(1, result.getProcessedAssets());
        ArgumentCaptor<List<FeatureModuleExtractor.FeatureModuleFact>> modulesCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(graphBuilder).buildFeatureModuleGraph(
                eq("project-1"), eq("v1"), modulesCaptor.capture(), any());
        assertEquals("orders", modulesCaptor.getValue().get(0).getModuleName());
    }
}
