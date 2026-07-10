package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PlainTextPartitioner} 单元测试。
 */
class PlainTextPartitionerTest {

    private final PlainTextPartitioner partitioner = new PlainTextPartitioner();

    @Test
    void supports_txt() {
        assertTrue(partitioner.supports("readme.txt"));
        assertTrue(partitioner.supports("readme.text"));
        assertFalse(partitioner.supports("readme.md"));
        assertFalse(partitioner.supports(null));
    }

    @Test
    void emptyContent_returnsEmpty() {
        assertTrue(partitioner.partition("d1", "f.txt", "").isEmpty());
        assertTrue(partitioner.partition("d1", "f.txt", null).isEmpty());
    }

    @Test
    void splitByBlankLine() {
        String text = """
                第一段内容。
                续行。

                第二段。

                第三段内容。
                """;
        List<DocumentElement> elements = partitioner.partition("d1", "f.txt", text);

        assertEquals(3, elements.size());
        elements.forEach(e -> assertEquals(DocumentElement.Type.NARRATIVE_TEXT, e.getType()));
        assertEquals("第一段内容。\n续行。", elements.get(0).getText());
        assertEquals("第二段。", elements.get(1).getText());
        assertEquals("第三段内容。", elements.get(2).getText());
    }

    @Test
    void sourceLocation_isFileName() {
        List<DocumentElement> elements = partitioner.partition("d1", "notes.txt", "一段文本");
        assertEquals(1, elements.size());
        assertEquals("notes.txt", elements.get(0).getSourceLocation());
        assertTrue(elements.get(0).getHeadingPath().isEmpty());
    }

    @Test
    void leadingBlankLines_ignored() {
        List<DocumentElement> elements = partitioner.partition("d1", "f.txt", "\n\n\n实际内容\n");
        assertEquals(1, elements.size());
        assertEquals("实际内容", elements.get(0).getText());
    }
}
