package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultDocumentPartitionService} 单元测试 — 验证按扩展名分发与 PDF 异常。
 */
class DefaultDocumentPartitionServiceTest {

    private final DefaultDocumentPartitionService service = new DefaultDocumentPartitionService();

    @Test
    void dispatch_md() {
        List<DocumentElement> elements = service.partition("d1", "spec.md", "# 标题\n正文");
        assertFalse(elements.isEmpty());
        // 至少包含一个 TITLE
        assertTrue(elements.stream().anyMatch(e -> e.getType() == DocumentElement.Type.TITLE));
    }

    @Test
    void dispatch_txt() {
        List<DocumentElement> elements = service.partition("d1", "readme.txt", "第一段\n\n第二段");
        assertEquals(2, elements.size());
        elements.forEach(e -> assertEquals(DocumentElement.Type.NARRATIVE_TEXT, e.getType()));
    }

    @Test
    void dispatch_unknownType_fallbackPlainText() {
        List<DocumentElement> elements = service.partition("d1", "notes.log", "一段文本");
        assertEquals(1, elements.size());
        assertEquals(DocumentElement.Type.NARRATIVE_TEXT, elements.get(0).getType());
        assertEquals("一段文本", elements.get(0).getText());
    }

    @Test
    void dispatch_pdf_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> service.partition("d1", "spec.pdf", "ignored"));
    }

    @Test
    void dispatch_emptyFileName_fallbackPlainText() {
        List<DocumentElement> elements = service.partition("d1", "", "纯文本");
        assertEquals(1, elements.size());
        assertEquals(DocumentElement.Type.NARRATIVE_TEXT, elements.get(0).getType());
    }
}
