package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * FAST 层文档解析服务（G-03 三层文档解析策略）。
 * <p>
 * 快速文本解析，适用于结构清晰的文档：
 * <ul>
 *   <li>Markdown（.md）— 按 # 标题/段落/代码块/表格分割</li>
 *   <li>纯文本（.txt/.text）— 按空行分段</li>
 *   <li>简单 PDF — PDFBox 提取文本层，按页按段落分割</li>
 *   <li>DOCX — POI 按段落样式解析（写入临时文件后委托 {@link WordPartitioner}）</li>
 * </ul>
 * 每个元素携带 parseConfidence=0.95、pageNo=1（PDF 按实际页码）、sourceLocation。
 * <p>
 * 路由策略：文本层厚度足够（&gt;1000 字符）且无复杂版面特征的文档优先走此层。
 */
@Slf4j
@Service
public class FastPartitionService implements DocumentPartitionService {

    /** 快速解析的置信度 */
    static final double CONFIDENCE = 0.95;

    private final MarkdownPartitioner markdownPartitioner = new MarkdownPartitioner();
    private final PlainTextPartitioner plainTextPartitioner = new PlainTextPartitioner();
    private final WordPartitioner wordPartitioner = new WordPartitioner();

    @Override
    public List<DocumentElement> partition(byte[] content, String mimeType, String fileName) {
        if (content == null || content.length == 0) {
            return List.of();
        }
        String name = fileName != null ? fileName.toLowerCase() : "";
        String mime = mimeType != null ? mimeType.toLowerCase() : "";

        List<DocumentElement> elements;
        if (name.endsWith(".md") || mime.contains("markdown")) {
            String text = new String(content, StandardCharsets.UTF_8);
            elements = markdownPartitioner.partition(null, fileName, text);
        } else if (name.endsWith(".txt") || name.endsWith(".text") || mime.startsWith("text/plain")) {
            String text = new String(content, StandardCharsets.UTF_8);
            elements = plainTextPartitioner.partition(null, fileName, text);
        } else if (name.endsWith(".docx")) {
            elements = partitionDocx(content, fileName);
        } else if (name.endsWith(".pdf") || mime.equals("application/pdf")) {
            elements = partitionPdf(content, fileName);
        } else {
            // 未识别类型按纯文本处理
            String text = new String(content, StandardCharsets.UTF_8);
            elements = plainTextPartitioner.partition(null, fileName, text);
        }

        // 填充置信度与默认页码（PDF 已按页设置 pageNo）
        for (DocumentElement e : elements) {
            e.setParseConfidence(CONFIDENCE);
            if (e.getPageNo() == 0) {
                e.setPageNo(1);
            }
        }
        return elements;
    }

    /**
     * PDF 文本提取：PDFBox 按页提取文本层，每页按空行分段。
     * 仅提取文本层（不识别版面），适合文本层完整的简单 PDF。
     */
    private List<DocumentElement> partitionPdf(byte[] content, String fileName) {
        List<DocumentElement> elements = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = doc.getNumberOfPages();
            for (int i = 1; i <= pageCount; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(doc);
                if (pageText == null || pageText.isBlank()) {
                    continue;
                }
                // 按连续空行分段
                String[] paragraphs = pageText.split("\\n\\s*\\n");
                for (String para : paragraphs) {
                    String trimmed = para.strip();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    elements.add(DocumentElement.builder()
                            .id(UUID.randomUUID().toString())
                            .type(DocumentElement.Type.NARRATIVE_TEXT)
                            .text(trimmed)
                            .headingPath(Collections.emptyList())
                            .sourceLocation(fileName + "#page" + i)
                            .pageNo(i)
                            .build());
                }
            }
        } catch (IOException e) {
            log.warn("FastPartition PDF 解析失败: {}", fileName, e);
        }
        return elements;
    }

    /**
     * DOCX 解析：写入临时文件后委托 {@link WordPartitioner}，解析完删除临时文件。
     */
    private List<DocumentElement> partitionDocx(byte[] content, String fileName) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("fast-partition-", ".docx");
            Files.write(tmp, content);
            return wordPartitioner.partition(null, fileName, tmp.toString());
        } catch (IOException e) {
            log.warn("FastPartition DOCX 解析失败: {}", fileName, e);
            return List.of();
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 临时文件删除失败不影响主流程
                }
            }
        }
    }

    @Override
    public String supportedTier() {
        return "FAST";
    }

    /**
     * 旧接口实现（向后兼容）：按文件名分发到对应 partitioner，并填充置信度。
     */
    @Override
    public List<DocumentElement> partition(String docId, String fileName, String content) {
        if (fileName == null) {
            fileName = "unknown.txt";
        }
        String lower = fileName.toLowerCase();
        List<DocumentElement> elements;
        if (lower.endsWith(".md")) {
            elements = markdownPartitioner.partition(docId, fileName, content);
        } else if (lower.endsWith(".docx")) {
            elements = wordPartitioner.partition(docId, fileName, content);
        } else {
            elements = plainTextPartitioner.partition(docId, fileName, content);
        }
        for (DocumentElement e : elements) {
            e.setParseConfidence(CONFIDENCE);
            if (e.getPageNo() == 0) {
                e.setPageNo(1);
            }
        }
        return elements;
    }
}
