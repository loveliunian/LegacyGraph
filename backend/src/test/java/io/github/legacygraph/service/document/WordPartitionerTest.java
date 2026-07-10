package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WordPartitioner} 单元测试 — 动态生成临时 .docx 验证解析。
 */
class WordPartitionerTest {

    private final WordPartitioner partitioner = new WordPartitioner();

    @Test
    void headingAndNormal(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("doc.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph h1 = doc.createParagraph();
            h1.setStyle("Heading1");
            h1.createRun().setText("结算需求");

            XWPFParagraph normal1 = doc.createParagraph();
            normal1.createRun().setText("这是正文内容。");

            XWPFParagraph h2 = doc.createParagraph();
            h2.setStyle("Heading2");
            h2.createRun().setText("验收条件");

            XWPFParagraph normal2 = doc.createParagraph();
            normal2.createRun().setText("验收正文。");

            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                doc.write(out);
            }
        }

        List<DocumentElement> elements = partitioner.partition("d1", "doc.docx", file.toString());

        // 期望：TITLE(h1), NARRATIVE_TEXT, TITLE(h2), NARRATIVE_TEXT
        assertEquals(4, elements.size());

        assertEquals(DocumentElement.Type.TITLE, elements.get(0).getType());
        assertEquals("结算需求", elements.get(0).getText());
        assertEquals(List.of("结算需求"), elements.get(0).getHeadingPath());

        assertEquals(DocumentElement.Type.NARRATIVE_TEXT, elements.get(1).getType());
        assertEquals("这是正文内容。", elements.get(1).getText());
        assertEquals(List.of("结算需求"), elements.get(1).getHeadingPath());

        assertEquals(DocumentElement.Type.TITLE, elements.get(2).getType());
        assertEquals("验收条件", elements.get(2).getText());
        assertEquals(List.of("结算需求", "验收条件"), elements.get(2).getHeadingPath());
        assertEquals("doc.docx#结算需求-验收条件", elements.get(2).getSourceLocation());

        assertEquals(DocumentElement.Type.NARRATIVE_TEXT, elements.get(3).getType());
        assertEquals("验收正文。", elements.get(3).getText());
    }

    @Test
    void headingLevelReset_onH1(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("doc.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph h1a = doc.createParagraph();
            h1a.setStyle("Heading1");
            h1a.createRun().setText("第一章");

            XWPFParagraph h3 = doc.createParagraph();
            h3.setStyle("Heading3");
            h3.createRun().setText("三级标题");

            XWPFParagraph h1b = doc.createParagraph();
            h1b.setStyle("Heading1");
            h1b.createRun().setText("第二章");

            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                doc.write(out);
            }
        }

        List<DocumentElement> elements = partitioner.partition("d1", "doc.docx", file.toString());

        assertEquals(3, elements.size());
        assertEquals(List.of("第一章"), elements.get(0).getHeadingPath());
        assertEquals(List.of("第一章", "三级标题"), elements.get(1).getHeadingPath());
        // 新一级标题重置
        assertEquals(List.of("第二章"), elements.get(2).getHeadingPath());
    }

    @Test
    void supports_docx() {
        assertTrue(partitioner.supports("a.docx"));
        assertFalse(partitioner.supports("a.doc"));
        assertFalse(partitioner.supports("a.pdf"));
        assertFalse(partitioner.supports(null));
    }

    @Test
    void emptyOrInvalid_returnsEmptyOrThrows() {
        assertTrue(partitioner.partition("d1", "doc.docx", "").isEmpty());
        assertTrue(partitioner.partition("d1", "doc.docx", null).isEmpty());
    }
}
