package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Markdown 文档切块器（spec 4.3）。
 * <p>
 * 解析规则：
 * <ul>
 *   <li>按 #/##/### 标题层级生成 TITLE 元素，维护 headingPath（一级重置、下级追加）</li>
 *   <li>代码块（``` 包裹）生成 CODE_BLOCK</li>
 *   <li>表格（| 开头的连续行）生成 TABLE</li>
 *   <li>普通段落生成 NARRATIVE_TEXT</li>
 * </ul>
 * sourceLocation 格式：file.md#heading-path（用 - 连接 headingPath）。
 */
public class MarkdownPartitioner implements DocumentPartitioner {

    private static final String FENCE = "```";

    @Override
    public List<DocumentElement> partition(String docId, String fileName, String content) {
        List<DocumentElement> elements = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return elements;
        }
        String[] lines = content.split("\n", -1);
        // 当前章节路径（可变，按层级截断/追加）
        List<String> headingPath = new ArrayList<>();
        // 段落缓冲（普通文本行累积成段，遇空行或结构边界 flush）
        List<String> paragraphBuffer = new ArrayList<>();
        int index = 0;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String raw = stripTrailing(line);

            // 代码块
            if (raw.startsWith(FENCE)) {
                index = flushParagraph(elements, docId, fileName, headingPath, paragraphBuffer, index);
                StringBuilder code = new StringBuilder();
                // 跳过起始 ``` 行本身，但记录语言标识
                String lang = raw.length() > FENCE.length() ? raw.substring(FENCE.length()).trim() : "";
                i++;
                while (i < lines.length && !stripTrailing(lines[i]).startsWith(FENCE)) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                // 跳过结束 ``` 行
                if (i < lines.length) {
                    i++;
                }
                String codeText = code.toString();
                // 去掉末尾多余换行
                if (codeText.endsWith("\n")) {
                    codeText = codeText.substring(0, codeText.length() - 1);
                }
                elements.add(buildElement(docId, fileName, headingPath,
                        DocumentElement.Type.CODE_BLOCK,
                        lang.isEmpty() ? codeText : codeText,
                        index++));
                continue;
            }

            // 标题
            int level = headingLevel(raw);
            if (level > 0) {
                index = flushParagraph(elements, docId, fileName, headingPath, paragraphBuffer, index);
                String title = raw.substring(level).trim();
                updateHeadingPath(headingPath, level, title);
                elements.add(buildElement(docId, fileName, headingPath,
                        DocumentElement.Type.TITLE, title, index++));
                i++;
                continue;
            }

            // 表格：连续以 | 开头的行（允许首列前空白）
            if (isTableRow(raw)) {
                index = flushParagraph(elements, docId, fileName, headingPath, paragraphBuffer, index);
                StringBuilder table = new StringBuilder();
                while (i < lines.length && isTableRow(stripTrailing(lines[i]))) {
                    table.append(stripTrailing(lines[i])).append('\n');
                    i++;
                }
                String tableText = table.toString();
                if (tableText.endsWith("\n")) {
                    tableText = tableText.substring(0, tableText.length() - 1);
                }
                elements.add(buildElement(docId, fileName, headingPath,
                        DocumentElement.Type.TABLE, tableText, index++));
                continue;
            }

            // 空行 → flush 段落
            if (raw.isEmpty()) {
                index = flushParagraph(elements, docId, fileName, headingPath, paragraphBuffer, index);
                i++;
                continue;
            }

            // 普通文本行 → 累积
            paragraphBuffer.add(raw);
            i++;
        }
        flushParagraph(elements, docId, fileName, headingPath, paragraphBuffer, index);
        return elements;
    }

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".md");
    }

    /** 返回标题层级（# 个数），非标题返回 0。最多支持 6 级。 */
    private int headingLevel(String line) {
        if (line == null || line.isEmpty() || line.charAt(0) != '#') {
            return 0;
        }
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#' && level < 6) {
            level++;
        }
        // # 后须为空格或行尾，避免 ##abc 误判
        if (level > 0 && level < line.length() && line.charAt(level) != ' ') {
            return 0;
        }
        return level;
    }

    /** 按层级更新 headingPath：截断到 level-1 长度后追加标题。 */
    private void updateHeadingPath(List<String> headingPath, int level, String title) {
        while (headingPath.size() >= level) {
            headingPath.remove(headingPath.size() - 1);
        }
        headingPath.add(title);
    }

    private boolean isTableRow(String line) {
        return line != null && !line.isEmpty() && line.charAt(0) == '|';
    }

    /** flush 段落缓冲为一个 NARRATIVE_TEXT 元素，返回更新后的 index。 */
    private int flushParagraph(List<DocumentElement> elements, String docId, String fileName,
                               List<String> headingPath, List<String> buffer, int index) {
        if (buffer.isEmpty()) {
            return index;
        }
        String text = String.join("\n", buffer);
        buffer.clear();
        elements.add(buildElement(docId, fileName, headingPath,
                DocumentElement.Type.NARRATIVE_TEXT, text, index));
        return index + 1;
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

    private String stripTrailing(String line) {
        if (line == null) {
            return "";
        }
        // 去除行尾回车与空白
        int end = line.length();
        while (end > 0) {
            char c = line.charAt(end - 1);
            if (c == '\r' || c == ' ' || c == '\t') {
                end--;
            } else {
                break;
            }
        }
        return line.substring(0, end);
    }
}
