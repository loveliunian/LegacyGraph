package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.DbSchemaAnalysisAgent;
import io.github.legacygraph.builder.FrontendGraphBuilder;
import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.extractors.adapter.ExtractionAdapter;
import io.github.legacygraph.extractors.adapter.ExtractionAdapterRegistry;
import io.github.legacygraph.extractors.adapter.ExtractionResult;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectScannerAdapterTest {

    @TempDir
    Path tempDir;

    @Mock private ScanVersionRepository scanVersionRepository;
    @Mock private ScanTaskRepository scanTaskRepository;
    @Mock private FactRepository factRepository;
    @Mock private DbConnectionRepository dbConnectionRepository;
    @Mock private CodeRepoRepository codeRepoRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private GraphBuilder graphBuilder;
    @Mock private FrontendGraphBuilder frontendGraphBuilder;
    @Mock private AiScanOrchestrator aiScanOrchestrator;
    @Mock private DbSchemaAnalysisAgent dbSchemaAnalysisAgent;
    @Mock private ExtractionAdapterRegistry adapterRegistry;
    @Mock private ExtractionAdapter adapter;

    @Test
    void scanAssetsWithAdaptersDelegatesSupportedFilesToRegistry() throws Exception {
        Files.writeString(tempDir.resolve("OrderController.java"), "class OrderController {}");
        when(adapterRegistry.selectAdapter(any(), any())).thenReturn(Optional.of(adapter));
        when(adapter.extract(any(), any())).thenReturn(ExtractionResult.builder()
                .processedAssets(1)
                .nodeCount(3)
                .edgeCount(2)
                .summary("ok")
                .build());

        ProjectScanner scanner = new ProjectScanner(
                scanVersionRepository,
                scanTaskRepository,
                factRepository,
                dbConnectionRepository,
                codeRepoRepository,
                documentRepository,
                graphBuilder,
                frontendGraphBuilder,
                null,
                new ObjectMapper(),
                aiScanOrchestrator,
                dbSchemaAnalysisAgent,
                adapterRegistry
        );

        int processed = scanner.scanAssetsWithAdapters("project-1", "v1",
                tempDir.toString(), tempDir.toString(), tempDir.toString());

        assertEquals(1, processed);
        verify(adapterRegistry).selectAdapter(any(), any());
        verify(adapter).extract(any(), any());
    }
}
