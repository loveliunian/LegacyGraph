package io.github.legacygraph.task;

import io.github.legacygraph.config.GraphWriteConfig;
import io.github.legacygraph.dto.claim.CompileOptions;
import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.extractors.adapter.AdapterCapability;
import io.github.legacygraph.extractors.adapter.ExtractionAdapter;
import io.github.legacygraph.extractors.adapter.ExtractionAdapterRegistry;
import io.github.legacygraph.extractors.adapter.ExtractionResult;
import io.github.legacygraph.extractors.adapter.ScanContext;
import io.github.legacygraph.extractors.adapter.SourceAsset;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.service.graph.KnowledgeCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdapterExecutionServiceWriteModeTest {

    @Mock
    private ExtractionAdapterRegistry adapterRegistry;

    @Mock
    private ScanTaskRecorder taskRecorder;

    @Mock
    private KnowledgeClaimService knowledgeClaimService;

    @Mock
    private KnowledgeCompiler knowledgeCompiler;

    @Mock
    private ExtractionAdapter adapter;

    private GraphWriteConfig graphWriteConfig;
    private ScanContext context;
    private SourceAsset asset;
    private KnowledgeClaimDraft draft;

    @BeforeEach
    void setUp() {
        graphWriteConfig = new GraphWriteConfig();
        context = ScanContext.builder().projectId("p1").versionId("v1").build();
        asset = SourceAsset.builder().relativePath("src/Order.java").fileType("java").build();
        draft = KnowledgeClaimDraft.builder()
                .projectId("p1")
                .versionId("v1")
                .subjectType("Feature")
                .subjectKey("下单")
                .predicate("IMPLEMENTED_BY")
                .objectType("Controller")
                .objectKey("OrderController")
                .sourceType("CODE")
                .confidence(new BigDecimal("0.9000"))
                .build();

        when(adapterRegistry.selectAdapters(context, asset)).thenReturn(List.of(adapter));
        when(adapter.capability()).thenReturn(AdapterCapability.builder().name("TestAdapter").build());
        when(adapter.extract(context, asset)).thenReturn(ExtractionResult.builder()
                .processedAssets(1)
                .claimDrafts(List.of(draft))
                .build());
    }

    @Test
    void directModeKeepsAdapterPathWithoutClaimCompiler() {
        graphWriteConfig.setWriteMode("direct");
        AdapterExecutionService service = service();

        service.executeDiscoveredAssets(context, List.of(asset), 1, new ScanTask(), null, false, null);

        verify(knowledgeClaimService, never()).upsertDrafts(any());
        verify(knowledgeCompiler, never()).compile(any(), any(), any());
    }

    @Test
    void shadowModePersistsDraftsAndRunsCompilerDryRun() {
        graphWriteConfig.setWriteMode("shadow");
        AdapterExecutionService service = service();

        service.executeDiscoveredAssets(context, List.of(asset), 1, new ScanTask(), null, false, null);

        verify(knowledgeClaimService).upsertDrafts(List.of(draft));
        ArgumentCaptor<CompileOptions> optionsCaptor = ArgumentCaptor.forClass(CompileOptions.class);
        verify(knowledgeCompiler).compile(any(), any(), optionsCaptor.capture());
        assertTrue(optionsCaptor.getValue().isDryRun());
    }

    @Test
    void claimCompilerModePersistsDraftsAndRunsCompilerWithRealWrite() {
        graphWriteConfig.setWriteMode("claim-compiler");
        AdapterExecutionService service = service();

        service.executeDiscoveredAssets(context, List.of(asset), 1, new ScanTask(), null, false, null);

        verify(knowledgeClaimService).upsertDrafts(List.of(draft));
        ArgumentCaptor<CompileOptions> optionsCaptor = ArgumentCaptor.forClass(CompileOptions.class);
        verify(knowledgeCompiler).compile(any(), any(), optionsCaptor.capture());
        assertFalse(optionsCaptor.getValue().isDryRun());
    }

    private AdapterExecutionService service() {
        return new AdapterExecutionService(
                adapterRegistry,
                taskRecorder,
                knowledgeClaimService,
                knowledgeCompiler,
                graphWriteConfig);
    }
}
