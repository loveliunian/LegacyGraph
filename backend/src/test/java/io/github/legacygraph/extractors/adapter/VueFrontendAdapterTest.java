package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.FrontendGraphBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * VueFrontendAdapter 单元测试。
 * 验证 Vue 前端适配器的 supports/extract/capability 方法。
 */
@ExtendWith(MockitoExtension.class)
class VueFrontendAdapterTest {

    @TempDir
    Path tempDir;

    @Mock
    private FrontendGraphBuilder frontendGraphBuilder;

    @Mock
    private FactPersister factPersister;

    @InjectMocks
    private VueFrontendAdapter adapter;

    /**
     * 测试 supports — 支持 vue/jsx/tsx 文件。
     */
    @Test
    void supports_returnsTrueForFrontendFiles() {
        ScanContext ctx = ScanContext.builder().build();

        for (String ext : new String[]{"vue", "jsx", "tsx"}) {
            Path file = tempDir.resolve("component." + ext);
            assertDoesNotThrow(() -> Files.writeString(file, "export default {}"));
            SourceAsset asset = SourceAsset.builder()
                    .file(file)
                    .fileType(ext)
                    .build();
            assertTrue(adapter.supports(ctx, asset), "Should support " + ext);
        }
    }

    /**
     * 测试 supports — 不支持 java/xml 文件。
     */
    @Test
    void supports_returnsFalseForBackendFiles() {
        ScanContext ctx = ScanContext.builder().build();
        SourceAsset javaAsset = SourceAsset.builder()
                .file(tempDir.resolve("Test.java"))
                .fileType("java")
                .build();

        assertFalse(adapter.supports(ctx, javaAsset));
    }

    /**
     * 测试 capability 返回正确信息。
     */
    @Test
    void capability_returnsCorrectCapability() {
        AdapterCapability cap = adapter.capability();

        assertEquals("VueFrontendAdapter", cap.getName());
        assertEquals(Set.of("javascript"), cap.getLanguages());
        assertEquals(Set.of("vue", "react"), cap.getFrameworks());
        assertEquals(Set.of("vue", "jsx", "tsx"), cap.getFileTypes());
        assertFalse(cap.isAiEnhanced());
        assertEquals(40, cap.getPriority());
    }

    /**
     * 测试 extract 对非 vue/jsx 文件返回处理结果。
     */
    @Test
    void extract_nonVueFile_returnsResult() throws Exception {
        Path file = tempDir.resolve("Test.tsx");
        Files.writeString(file, "export default {}");
        ScanContext ctx = ScanContext.builder()
                .projectId("project-1").versionId("v1").build();
        SourceAsset asset = SourceAsset.builder()
                .file(file).relativePath("Test.tsx")
                .fileType("tsx").language("javascript").build();

        ExtractionResult result = adapter.extract(ctx, asset);

        assertNotNull(result);
        assertTrue(result.getSummary().contains("Frontend"));
    }

    @Test
    void extractBatchesFrontendPageFacts() throws Exception {
        Path file = tempDir.resolve("routes.tsx");
        Files.writeString(file, """
                export default [
                  {
                    path: '/orders',
                    name: 'Orders',
                    component: 'OrderList',
                    meta: { title: '订单管理' }
                  }
                ]
                """);
        ScanContext ctx = ScanContext.builder()
                .projectId("project-1")
                .versionId("v1")
                .build();
        SourceAsset asset = SourceAsset.builder()
                .file(file)
                .relativePath("src/router/routes.tsx")
                .fileType("tsx")
                .language("javascript")
                .build();

        adapter.extract(ctx, asset);

        verify(factPersister).saveFacts(argThat(drafts -> drafts != null
                && drafts.stream().anyMatch(draft -> "FRONTEND_PAGE".equals(draft.getFactType())
                && "/orders".equals(draft.getFactKey()))));
        verify(factPersister, never()).saveFact(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
