package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.extractors.DocumentExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DocumentAdapter 单元测试。
 * 验证文档适配器的 supports/extract/capability 方法。
 */
@ExtendWith(MockitoExtension.class)
class DocumentAdapterTest {

    @TempDir
    Path tempDir;

    @Mock
    private FactPersister factPersister;

    @Mock
    private DocumentExtractor documentExtractor;

    private DocumentAdapter adapter() {
        return new DocumentAdapter(factPersister, documentExtractor);
    }

    /**
     * 测试 supports — 支持 md/pdf/docx/txt/rst/adoc。
     */
    @Test
    void supports_returnsTrueForSupportedDocTypes() {
        DocumentAdapter adapter = adapter();
        ScanContext ctx = ScanContext.builder().build();

        for (String ext : new String[]{"md", "pdf", "docx", "txt", "rst", "adoc"}) {
            SourceAsset asset = SourceAsset.builder()
                    .file(tempDir.resolve("test." + ext))
                    .fileType(ext)
                    .build();
            assertTrue(adapter.supports(ctx, asset), "Should support " + ext);
        }
    }

    /**
     * 测试 supports — 不支持 java/xml 等非文档扩展名。
     */
    @Test
    void supports_returnsFalseForNonDocTypes() {
        DocumentAdapter adapter = adapter();
        ScanContext ctx = ScanContext.builder().build();

        SourceAsset javaAsset = SourceAsset.builder()
                .file(tempDir.resolve("Test.java"))
                .fileType("java")
                .build();
        assertFalse(adapter.supports(ctx, javaAsset));
    }

    /**
     * 测试 capability 返回正确的适配器信息。
     */
    @Test
    void capability_returnsCorrectCapability() {
        DocumentAdapter adapter = adapter();

        AdapterCapability cap = adapter.capability();

        assertEquals("DocumentAdapter", cap.getName());
        assertEquals(Set.of("markdown", "text"), cap.getLanguages());
        assertEquals(Set.of(), cap.getFrameworks());
        assertFalse(cap.isAiEnhanced());
        assertEquals(50, cap.getPriority());
    }

    /**
     * 测试 extract 对空文件句柄 fail-safe。
     */
    @Test
    void extract_invalidFile_returnsResultWithSummary() throws Exception {
        DocumentAdapter adapter = adapter();
        ScanContext ctx = ScanContext.builder().projectId("p1").versionId("v1").build();
        SourceAsset asset = SourceAsset.builder()
                .file(tempDir.resolve("nonexistent.md"))
                .relativePath("nonexistent.md")
                .fileType("md")
                .build();
        when(documentExtractor.extractText(any())).thenThrow(new RuntimeException("missing file"));

        ExtractionResult result = adapter.extract(ctx, asset);

        assertNotNull(result);
        assertTrue(result.getSummary().contains("Document failed"));
    }
}
