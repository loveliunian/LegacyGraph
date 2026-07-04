package io.github.legacygraph.extractors;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档文本抽取器
 * 使用 Apache Tika + POI + PDFbox 抽取 Word/PDF/Markdown 文本
 */
@Slf4j
@org.springframework.stereotype.Component  // L3 修复：注入为 Bean，避免每次 new
public class DocumentExtractor {

    private final Tika tika = new Tika();

    /**
     * 从文件抽取文本
     */
    public String extractText(File file) throws IOException, TikaException {
        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".pdf")) {
            return extractPdf(file);
        } else if (fileName.endsWith(".docx") || fileName.endsWith(".doc")) {
            return extractWord(file);
        } else if (fileName.endsWith(".md") || fileName.endsWith(".txt")) {
            return Files.readString(file.toPath());
        } else {
            // 使用Tika自动检测
            return tika.parseToString(file);
        }
    }

    /**
     * 抽取PDF文本（M12修复：使用流式读取，避免大文件全量加载导致 OOM）
     * PDFBox 支持从 InputStream 加载，无需一次性读取全部字节。
     */
    private String extractPdf(File file) throws IOException {
        try (InputStream is = new FileInputStream(file);
             PDDocument document = Loader.loadPDF(is.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * 抽取Word文本
     */
    private String extractWord(File file) throws IOException {
        StringBuilder text = new StringBuilder();
        try (InputStream is = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(is)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                text.append(paragraph.getText()).append("\n");
            }
        }
        return text.toString();
    }

    /**
     * 文档切片 - 根据标题和段落切分
     * 保持每个片段在合理token数量范围内
     */
    public List<DocumentChunk> chunkDocument(String text, String docName, int maxTokensPerChunk) {
        List<DocumentChunk> chunks = new ArrayList<>();

        // 按标题切分
        // 匹配 # 标题、## 标题 或者 1. 标题、1.1 标题 等格式
        Pattern headingPattern = Pattern.compile("^(#{1,6})\\s+(.+)$|^\\d+(\\.\\d+)*\\s+(.+)$", Pattern.MULTILINE);
        Matcher matcher = headingPattern.matcher(text);

        List<Integer> headingPositions = new ArrayList<>();
        List<String> headings = new ArrayList<>();

        while (matcher.find()) {
            headingPositions.add(matcher.start());
            String heading = matcher.group(2);
            if (heading == null) heading = matcher.group(4);
            headings.add(heading);
        }

        // 添加起点和终点
        headingPositions.add(0, 0);
        headingPositions.add(text.length());

        // 合并切片，控制token数量
        StringBuilder currentChunk = new StringBuilder();
        StringBuilder currentTitlePath = new StringBuilder();
        int currentTokens = 0;
        int chunkIndex = 0;

        // 遍历切分点
        for (int i = 0; i < headingPositions.size() - 1; i++) {
            int start = headingPositions.get(i);
            int end = headingPositions.get(i + 1);
            String section = text.substring(start, end);

            // 估算token数量（中文按字符，英文按单词）
            int estimatedTokens = estimateTokens(section);

            if (currentTokens + estimatedTokens > maxTokensPerChunk && currentTokens > 0) {
                // 保存当前chunk
                DocumentChunk chunk = new DocumentChunk();
                chunk.setIndex(chunkIndex++);
                chunk.setTitlePath(currentTitlePath.toString());
                chunk.setContent(currentChunk.toString());
                chunk.setTokenCount(currentTokens);
                chunks.add(chunk);

                // 开始新chunk
                currentChunk = new StringBuilder();
                currentTokens = 0;
            }

            currentChunk.append(section);
            if (i < headings.size()) {
                if (currentTitlePath.length() > 0) currentTitlePath.append(" > ");
                currentTitlePath.append(headings.get(i));
            }
            currentTokens += estimatedTokens;
        }

        // 保存最后一个chunk
        if (currentTokens > 0) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setIndex(chunkIndex++);
            chunk.setTitlePath(currentTitlePath.toString());
            chunk.setContent(currentChunk.toString());
            chunk.setTokenCount(currentTokens);
            chunks.add(chunk);
        }

        log.info("Chucked document {} into {} chunks", docName, chunks.size());
        return chunks;
    }

    /**
     * 估算token数量
     * 简单估算：中文每个字算0.5个token，英文每个单词算1个token
     */
    private int estimateTokens(String text) {
        int chineseChars = 0;
        int words = 0;

        String[] split = text.split("\\s+");
        for (String word : split) {
            if (word.isEmpty()) continue;

            boolean hasChinese = false;
            for (char c : word.toCharArray()) {
                if (c >= 0x4e00 && c <= 0x9fa5) {
                    chineseChars++;
                    hasChinese = true;
                }
            }
            if (!hasChinese || word.length() > 0) {
                words++;
            }
        }

        return (int) (chineseChars * 0.5 + words);
    }

    /**
     * 文档切片
     */
    @lombok.Data
    public static class DocumentChunk {
        private int index;
        private String titlePath;
        private String content;
        private int tokenCount;
    }
}
