package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Word 文档切块器（spec 4.4）。
 * <p>
 * 使用 Apache POI 按段落样式解析 .docx：
 * <ul>
 *   <li>Heading1/2/3... 样式生成 TITLE，并维护 headingPath（一级重置、下级追加）</li>
 *   <li>Normal（或无样式）生成 NARRATIVE_TEXT</li>
 * </ul>
 * 因二进制内容无法以字符串承载，{@code content} 参数约定为 .docx 文件路径。
 * sourceLocation 格式：file.docx#heading-path。
 */
public class WordPartitioner implements DocumentPartitioner {

    @Override
    public List<DocumentElement> partition(String docId, String fileName, String content) {
        List<DocumentElement> elements = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return elements;
        }
        try (InputStream in = new FileInputStream(content);
             XWPFDocument doc = new XWPFDocument(in)) {
            List<String> headingPath = new ArrayList<>();
            int index = 0;
            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                String text = paragraph.getText();
                if (text == null) {
                    continue;
                }
                text = text.trim();
                String styleId = paragraph.getStyleID();
                int level = headingLevel(styleId);
                if (level > 0) {
                    // 标题
                    if (text.isEmpty()) {
                        continue;
                    }
                    updateHeadingPath(headingPath, level, text);
                    elements.add(buildElement(docId, fileName, headingPath,
                            DocumentElement.Type.TITLE, text, index++));
                } else {
                    // 正文
                    if (text.isEmpty()) {
                        continue;
                    }
                    elements.add(buildElement(docId, fileName, headingPath,
                            DocumentElement.Type.NARRATIVE_TEXT, text, index++));
                }
            }
            return elements;
        } catch (IOException e) {
            throw new IllegalStateException("解析 Word 文档失败: " + content, e);
        }
    }

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".docx");
    }

    /** 从样式 ID 提取标题层级；Heading1 返回 1，Heading2 返回 2，非标题返回 0。 */
    private int headingLevel(String styleId) {
        if (styleId == null || !styleId.startsWith("Heading")) {
            return 0;
        }
        String num = styleId.substring("Heading".length());
        try {
            int level = Integer.parseInt(num);
            return (level >= 1 && level <= 9) ? level : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void updateHeadingPath(List<String> headingPath, int level, String title) {
        while (headingPath.size() >= level) {
            headingPath.remove(headingPath.size() - 1);
        }
        headingPath.add(title);
    }

    private DocumentElement buildElement(String docId, String fileName, List<String> headingPath,
                                         DocumentElement.Type type, String text, int index) {
        return DocumentElement.builder()
                .id(UUID.randomUUID().toString())
                .docId(docId)
                .type(type)
                .text(text)
                .headingPath(new ArrayList<>(headingPath))
                .sourceLocation(buildSourceLocation(fileName, headingPath))
                .build();
    }

    private String buildSourceLocation(String fileName, List<String> headingPath) {
        if (headingPath == null || headingPath.isEmpty()) {
            return fileName + "#";
        }
        return fileName + "#" + String.join("-", headingPath);
    }
}
