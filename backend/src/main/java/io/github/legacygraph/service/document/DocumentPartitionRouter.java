package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * G-03 三层文档解析路由器。
 * <p>
 * 注入所有 {@link DocumentPartitionService} 实现，按 {@link #supportedTier()} 分组，
 * 根据文档特征路由到合适的解析层级：
 * <ol>
 *   <li><b>OCR</b> — MIME 为 application/pdf 且文本层 &lt; 1000 字符（扫描件/图片型 PDF）</li>
 *   <li><b>LAYOUT</b> — 含表格/多列/页眉页脚等复杂版面特征</li>
 *   <li><b>FAST</b> — 其余文档（md/txt/简单 PDF/DOCX）</li>
 * </ol>
 * 降级策略：当目标层级返回空列表（如 OCR 未开启）时，自动回退到下一层级。
 */
@Slf4j
@Service
public class DocumentPartitionRouter {

    /** OCR 触发阈值：PDF 文本层字符数低于此值视为扫描件 */
    static final int OCR_TEXT_THRESHOLD = 1000;

    /** 层级标识常量 */
    private static final String TIER_FAST = "FAST";
    private static final String TIER_LAYOUT = "LAYOUT";
    private static final String TIER_OCR = "OCR";

    /** tier → service 映射（LEGACY 层级不参与路由） */
    private final Map<String, DocumentPartitionService> tierMap;

    /** G13: 三层解析总开关——关闭时全部走 FAST（一键回退） */
    @Value("${legacygraph.document.partition.three-layer.enabled:true}")
    private boolean threeLayerEnabled;

    public DocumentPartitionRouter(List<DocumentPartitionService> services) {
        Map<String, DocumentPartitionService> map = new HashMap<>();
        if (services != null) {
            for (DocumentPartitionService s : services) {
                String tier = s.supportedTier();
                // 跳过 LEGACY 层级，仅保留三层解析服务
                if (TIER_FAST.equals(tier) || TIER_LAYOUT.equals(tier) || TIER_OCR.equals(tier)) {
                    map.putIfAbsent(tier, s);
                }
            }
        }
        this.tierMap = map;
        log.info("DocumentPartitionRouter 初始化: tiers={}", tierMap.keySet());
    }

    /**
     * 路由并解析文档。
     * <p>
     * 路由顺序：OCR → LAYOUT → FAST，目标层级返回空列表时自动降级到下一层级。
     *
     * @param content  文档原始字节内容
     * @param mimeType MIME 类型
     * @param fileName 文件名
     * @return 结构化元素列表
     */
    public List<DocumentElement> partition(byte[] content, String mimeType, String fileName) {
        if (content == null || content.length == 0) {
            return List.of();
        }

        // G13: 三层总开关——关闭时全部走 FAST（一键回退）
        if (!threeLayerEnabled) {
            List<DocumentElement> fastResult = partitionByTier(TIER_FAST, content, mimeType, fileName);
            log.debug("Three-layer disabled, fallback to FAST: fileName={}, elements={}",
                    fileName, fastResult.size());
            return fastResult;
        }

        // 1. OCR 层：PDF 且文本层 < 1000 字符
        if (shouldUseOcr(content, mimeType)) {
            List<DocumentElement> result = partitionByTier(TIER_OCR, content, mimeType, fileName);
            if (!result.isEmpty()) {
                log.debug("Router 选中 OCR 层: fileName={}", fileName);
                return result;
            }
            log.info("OCR 层返回空列表（可能未启用），降级到 LAYOUT/FAST: fileName={}", fileName);
        }

        // 2. LAYOUT 层：含表格/多列/页眉页脚
        if (shouldUseLayout(mimeType, content)) {
            List<DocumentElement> result = partitionByTier(TIER_LAYOUT, content, mimeType, fileName);
            if (!result.isEmpty()) {
                log.debug("Router 选中 LAYOUT 层: fileName={}", fileName);
                return result;
            }
            log.info("LAYOUT 层返回空列表，降级到 FAST: fileName={}", fileName);
        }

        // 3. FAST 层：默认
        List<DocumentElement> result = partitionByTier(TIER_FAST, content, mimeType, fileName);
        log.debug("Router 选中 FAST 层: fileName={}, elements={}", fileName, result.size());
        return result;
    }

    /**
     * 判定是否应走 OCR 路径：MIME 为 application/pdf 且文本层 &lt; 1000 字符。
     *
     * @param content  文档字节内容
     * @param mimeType MIME 类型
     * @return 需要 OCR 返回 true
     */
    public boolean shouldUseOcr(byte[] content, String mimeType) {
        if (!"application/pdf".equalsIgnoreCase(mimeType)) {
            return false;
        }
        int textLength = extractPdfTextLength(content);
        log.debug("shouldUseOcr: textLength={}, threshold={}, useOcr={}",
                textLength, OCR_TEXT_THRESHOLD, textLength < OCR_TEXT_THRESHOLD);
        return textLength < OCR_TEXT_THRESHOLD;
    }

    /**
     * 判定是否应走 LAYOUT 路径：PDF 且含表格/多列/页眉页脚特征。
     *
     * @param mimeType MIME 类型
     * @param content  文档字节内容
     * @return 需要 LAYOUT 解析返回 true
     */
    public boolean shouldUseLayout(String mimeType, byte[] content) {
        if (!"application/pdf".equalsIgnoreCase(mimeType)) {
            return false;
        }
        return hasComplexLayout(content);
    }

    /**
     * 按层级调用对应服务解析。
     */
    private List<DocumentElement> partitionByTier(String tier, byte[] content, String mimeType, String fileName) {
        DocumentPartitionService service = tierMap.get(tier);
        if (service == null) {
            log.warn("未找到 {} 层级解析服务", tier);
            return List.of();
        }
        return service.partition(content, mimeType, fileName);
    }

    /**
     * 提取 PDF 文本层字符数（用于 OCR 判定）。
     */
    private int extractPdfTextLength(byte[] content) {
        try (PDDocument doc = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            return text != null ? text.strip().length() : 0;
        } catch (IOException e) {
            log.warn("提取 PDF 文本层失败，按 0 字符处理（触发 OCR）: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 检测 PDF 是否含复杂版面特征：表格（连续 | 行）或页数较多（>5 页暗示多列/页眉页脚）。
     */
    private boolean hasComplexLayout(byte[] content) {
        try (PDDocument doc = Loader.loadPDF(content)) {
            int pageCount = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            // 检测表格特征
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(pageCount, 10)); // 最多扫描前 10 页
            String text = stripper.getText(doc);
            if (text != null && containsTable(text)) {
                return true;
            }
            // 页数较多时视为可能含复杂版面
            return pageCount > 5;
        } catch (IOException e) {
            log.warn("检测 PDF 版面特征失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检测文本是否包含表格特征：连续 2 行以上以 | 开头。
     */
    private boolean containsTable(String text) {
        String[] lines = text.split("\\n");
        int consecutiveTableRows = 0;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("|")) {
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
}
