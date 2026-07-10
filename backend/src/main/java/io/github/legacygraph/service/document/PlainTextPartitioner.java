package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 纯文本切块器（spec 4.6）。
 * <p>
 * 按空行分段生成 NARRATIVE_TEXT 元素；sourceLocation 为文件名本身（无章节路径）。
 */
public class PlainTextPartitioner implements DocumentPartitioner {

    @Override
    public List<DocumentElement> partition(String docId, String fileName, String content) {
        List<DocumentElement> elements = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return elements;
        }
        // 按空行（含仅含空白符的行）分段
        String[] lines = content.split("\n", -1);
        List<String> buffer = new ArrayList<>();
        int index = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                index = flush(elements, docId, fileName, buffer, index);
            } else {
                buffer.add(line.stripTrailing());
            }
        }
        flush(elements, docId, fileName, buffer, index);
        return elements;
    }

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".text");
    }

    private int flush(List<DocumentElement> elements, String docId, String fileName,
                      List<String> buffer, int index) {
        if (buffer.isEmpty()) {
            return index;
        }
        String text = String.join("\n", buffer);
        buffer.clear();
        elements.add(DocumentElement.builder()
                .id(UUID.randomUUID().toString())
                .docId(docId)
                .type(DocumentElement.Type.NARRATIVE_TEXT)
                .text(text)
                .headingPath(Collections.emptyList())
                .sourceLocation(fileName)
                .build());
        return index + 1;
    }
}
