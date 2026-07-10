package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MarkdownPartitioner} 单元测试。
 */
class MarkdownPartitionerTest {

    private final MarkdownPartitioner partitioner = new MarkdownPartitioner();

    @Test
    void supports_md() {
        assertTrue(partitioner.supports("spec.md"));
        assertTrue(partitioner.supports("SPEC.MD"));
        assertFalse(partitioner.supports("spec.docx"));
        assertFalse(partitioner.supports(null));
    }

    @Test
    void emptyContent_returnsEmpty() {
        assertTrue(partitioner.partition("d1", "f.md", "").isEmpty());
        assertTrue(partitioner.partition("d1", "f.md", null).isEmpty());
    }

    @Test
    void headingHierarchy_resetsAndAppends() {
        String md = """
                # 结算需求
                ## 验收条件
                ### 子项
                ## 另一验收
                # 新章节
                """;
        List<DocumentElement> elements = partitioner.partition("d1", "spec.md", md);

        // 期望 5 个 TITLE
        assertEquals(5, elements.size());
        elements.forEach(e -> assertEquals(DocumentElement.Type.TITLE, e.getType()));

        assertEquals(List.of("结算需求"), elements.get(0).getHeadingPath());
        assertEquals(List.of("结算需求", "验收条件"), elements.get(1).getHeadingPath());
        assertEquals(List.of("结算需求", "验收条件", "子项"), elements.get(2).getHeadingPath());
        // 从 ### 回到 ##：截断到 1 级后追加
        assertEquals(List.of("结算需求", "另一验收"), elements.get(3).getHeadingPath());
        // 新一级标题重置
        assertEquals(List.of("新章节"), elements.get(4).getHeadingPath());
    }

    @Test
    void codeBlock_emitsCodeBlock() {
        String md = """
                # 标题
                ```java
                int x = 1;
                String y = "hi";
                ```
                正文段落
                """;
        List<DocumentElement> elements = partitioner.partition("d1", "f.md", md);

        // 期望：TITLE, CODE_BLOCK, NARRATIVE_TEXT
        assertEquals(3, elements.size());
        DocumentElement code = elements.get(1);
        assertEquals(DocumentElement.Type.CODE_BLOCK, code.getType());
        assertEquals("int x = 1;\nString y = \"hi\";", code.getText());
        assertEquals(List.of("标题"), code.getHeadingPath());
    }

    @Test
    void table_emitsTable() {
        String md = """
                # 数据
                | 字段 | 类型 |
                | ---- | ---- |
                | id   | int  |
                正文
                """;
        List<DocumentElement> elements = partitioner.partition("d1", "f.md", md);

        // 期望：TITLE, TABLE, NARRATIVE_TEXT
        assertEquals(3, elements.size());
        DocumentElement table = elements.get(1);
        assertEquals(DocumentElement.Type.TABLE, table.getType());
        assertTrue(table.getText().contains("| 字段 | 类型 |"));
        assertTrue(table.getText().contains("| id   | int  |"));
    }

    @Test
    void narrativeText_splitByBlankLine() {
        String md = """
                # 章节
                第一段。

                第二段内容。
                仍是第二段。
                """;
        List<DocumentElement> elements = partitioner.partition("d1", "f.md", md);

        // 期望：TITLE, NARRATIVE_TEXT, NARRATIVE_TEXT
        assertEquals(3, elements.size());
        assertEquals(DocumentElement.Type.TITLE, elements.get(0).getType());
        assertEquals("第一段。", elements.get(1).getText());
        assertEquals("第二段内容。\n仍是第二段。", elements.get(2).getText());
    }

    @Test
    void sourceLocation_format() {
        String md = """
                # 结算需求
                ## 验收条件
                正文
                """;
        List<DocumentElement> elements = partitioner.partition("d1", "spec.md", md);

        assertEquals("spec.md#结算需求", elements.get(0).getSourceLocation());
        assertEquals("spec.md#结算需求-验收条件", elements.get(1).getSourceLocation());
        // 正文元素也继承当前 headingPath
        assertEquals("spec.md#结算需求-验收条件", elements.get(2).getSourceLocation());
    }

    @Test
    void noHeading_sourceLocationEmpty() {
        List<DocumentElement> elements = partitioner.partition("d1", "f.md", "纯文本内容");
        assertEquals(1, elements.size());
        assertEquals(DocumentElement.Type.NARRATIVE_TEXT, elements.get(0).getType());
        assertEquals("f.md#", elements.get(0).getSourceLocation());
        assertTrue(elements.get(0).getHeadingPath().isEmpty());
    }
}
