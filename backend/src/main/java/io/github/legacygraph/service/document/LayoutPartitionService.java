package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * LAYOUT 层文档解析服务（G-03 三层文档解析策略）。
 * <p>
 * 版面感知解析，适用于含复杂版面结构的 PDF：
 * <ul>
 *   <li>多列排版</li>
 *   <li>表格（| 分隔或对齐的多列文本）</li>
 *   <li>页眉/页脚（重复出现的短行）</li>
 * </ul>
 * 当前为简化实现：使用 PDFBox 按页提取文本，每页生成一个 NARRATIVE_TEXT 元素，
 * 并检测表格特征（连续 | 开头的行）记录在日志中。每个元素携带 parseConfidence。
 * <p>
 * 路由策略：{@link DocumentPartitionRouter#shouldUseLayout} 判定文档含复杂版面特征后走此层。
 */
@Slf4j
@Service
public class LayoutPartitionService implements DocumentPartitionService {

    /** 版面感知解析的默认置信度（PDFBox 文本提取质量中等） */
    static final double CONFIDENCE = 0.85;
    /** 置信度下限（方案文档 §G-03 验收要求 ≥0.7） */
    static final double MIN_CONFIDENCE = 0.7;
    /** 表格特征带来的置信度加成（保留率良好时小幅加成） */
    static final double TABLE_BONUS = 0.05;

    /** 表格行特征：以 | 开头（Markdown 表格或竖线分隔表格） */
    private static final String TABLE_ROW_PREFIX = "|";

    @Override
    public List<DocumentElement> partition(byte[] content, String mimeType, String fileName) {
        if (content == null || content.length == 0) {
            return List.of();
        }
        String name = fileName != null ? fileName.toLowerCase() : "";
        boolean isPdf = name.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(mimeType);
        if (!isPdf) {
            // 非 PDF 的复杂版面文档暂不支持，返回空列表
            log.debug("LayoutPartition 跳过非 PDF 文件: {}", fileName);
            return List.of();
        }
        return partitionPdf(content, fileName);
    }

    /**
     * PDF 版面感知解析：按页提取文本，检测表格/多列特征。
     * 每页生成一个 NARRATIVE_TEXT 元素，sourceLocation 标注页码。
     */
    private List<DocumentElement> partitionPdf(byte[] content, String fileName) {
        List<DocumentElement> elements = new ArrayList<>();
        int totalPagesScanned = 0;
        int tablePagesFound = 0;
        try (PDDocument doc = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = doc.getNumberOfPages();
            for (int i = 1; i <= pageCount; i++) {
                totalPagesScanned++;
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(doc);
                if (pageText == null || pageText.isBlank()) {
                    continue;
                }
                // 检测表格特征：连续以 | 开头的行
                boolean pageHasTable = containsTable(pageText);
                if (pageHasTable) {
                    tablePagesFound++;
                }
                String trimmed = pageText.strip();
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
                        .parseConfidence(CONFIDENCE)
                        .build());
            }
            if (tablePagesFound > 0) {
                log.info("LayoutPartition 检测到表格特征: fileName={}, tablePages={}/{}", fileName, tablePagesFound, pageCount);
            }
            log.debug("LayoutPartition 完成: fileName={}, pages={}, elements={}", fileName, pageCount, elements.size());
        } catch (IOException e) {
            log.warn("LayoutPartition PDF 解析失败: {}", fileName, e);
            return elements;
        }
        // 应用置信度调整：根据空页比例与表格保留率，最低 0.7，最高 1.0
        double adjustedConfidence = computeAdjustedConfidence(totalPagesScanned, elements.size(), tablePagesFound);
        for (DocumentElement el : elements) {
            el.setParseConfidence(adjustedConfidence);
        }
        log.debug("LayoutPartition parseConfidence: fileName={}, base={}, adjusted={}",
                fileName, CONFIDENCE, adjustedConfidence);
        return elements;
    }

    /**
     * 根据抽取结果调整置信度：
     * <ul>
     *   <li>非空元素 / 总扫描页数 → 抽取完整度（避免空页过多拉低）</li>
     *   <li>表格页数比例 → 表格保留率加成（封顶 1.0）</li>
     *   <li>下限 0.7（方案文档 §G-03 验收）</li>
     * </ul>
     */
    private double computeAdjustedConfidence(int totalScanned, int nonEmpty, int tablePages) {
        if (totalScanned <= 0) {
            return CONFIDENCE;
        }
        double completeness = (double) nonEmpty / totalScanned; // 0.0 ~ 1.0
        // 基础 = CONFIDENCE * completeness；表格页数若大于 0 给一个 TABLE_BONUS
        double base = CONFIDENCE * completeness;
        double bonus = tablePages > 0 ? TABLE_BONUS : 0.0;
        double adjusted = Math.min(1.0, base + bonus);
        return Math.max(MIN_CONFIDENCE, adjusted);
    }

    /**
     * 检测页面文本是否包含表格特征：连续 2 行以上以 | 开头。
     */
    private boolean containsTable(String pageText) {
        String[] lines = pageText.split("\\n");
        int consecutiveTableRows = 0;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith(TABLE_ROW_PREFIX)) {
                consecutiveTableRows++;
                if (consecutiveTableRows >= 2) {
                    return true;
                }
            } else {
                consecutiveTableRows = 0;
            }
        }
        return false;
    }

    @Override
    public String supportedTier() {
        return "LAYOUT";
    }

    /**
     * 旧接口实现（向后兼容）：LAYOUT 层不支持旧接口，委托失败时返回空列表。
     */
    @Override
    public List<DocumentElement> partition(String docId, String fileName, String content) {
        log.warn("LayoutPartition 不支持旧接口 partition(String,String,String)，请使用 DocumentPartitionRouter");
        return List.of();
    }
}
