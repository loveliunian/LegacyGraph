package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OCR 兜底解析服务（G-03 三层文档解析策略）。
 * <p>
 * 用于处理扫描件、图片型 PDF 等无文本层或文本层极薄的文档。
 * 默认关闭，通过配置 {@code legacygraph.ocr.enabled} 控制：
 * <ul>
 *   <li>{@code false}（默认）— 返回空列表并记录 {@link OcrSkippedReason#DISABLED}</li>
 *   <li>{@code true} — 记录 {@link OcrSkippedReason#NOT_INTEGRATED}（OCR 引擎尚未集成，后续按需补充）</li>
 * </ul>
 * 真实接入 OCR 引擎后，每个元素应同时写入 parseConfidence 与 ocrConfidence：
 * <ul>
 *   <li>parseConfidence — OCR 路径默认 0.7</li>
 *   <li>ocrConfidence — Tesseract 实际置信度（按字/按行），默认模拟值 0.85</li>
 * </ul>
 * <p>
 * 路由策略：{@link DocumentPartitionRouter#shouldUseOcr} 判定 PDF 文本层 &lt;1000 字符后走此层。
 */
@Slf4j
@Service
public class OcrFallbackService implements DocumentPartitionService {

    /** OCR 路径整体解析置信度（OCR 文本质量偏低） */
    static final double CONFIDENCE = 0.7;
    /** OCR 引擎字面置信度默认值（Tesseract 实际量级 0.6~0.9 的中位） */
    static final double OCR_CONFIDENCE = 0.85;

    /** OCR 开关（默认关闭） */
    @Value("${legacygraph.ocr.enabled:false}")
    private boolean ocrEnabled;

    @Override
    public List<DocumentElement> partition(byte[] content, String mimeType, String fileName) {
        if (!ocrEnabled) {
            log.info("OCR 兜底跳过（未启用）: fileName={}, reason={}", fileName, OcrSkippedReason.DISABLED);
            return List.of();
        }
        // OCR 引擎尚未集成，记录跳过原因
        log.info("OCR 兜底已启用但引擎未集成，跳过: fileName={}, reason={}", fileName, OcrSkippedReason.NOT_INTEGRATED);
        return List.of();
    }

    @Override
    public String supportedTier() {
        return "OCR";
    }

    /**
     * 旧接口实现（向后兼容）：OCR 层不支持旧接口，返回空列表。
     */
    @Override
    public List<DocumentElement> partition(String docId, String fileName, String content) {
        log.warn("OcrFallback 不支持旧接口 partition(String,String,String)，请使用 DocumentPartitionRouter");
        return List.of();
    }

    /**
     * OCR 跳过原因枚举，用于记录为何跳过 OCR 解析。
     */
    public enum OcrSkippedReason {
        /** OCR 功能未启用（legacygraph.ocr.enabled=false） */
        DISABLED,
        /** OCR 引擎尚未集成（开关已开但无可用引擎） */
        NOT_INTEGRATED
    }

    /**
     * 为 OCR 解析出的元素写入 parseConfidence / ocrConfidence 默认值。
     * <p>供真实 OCR 引擎接入后的解析器调用：在产出每个 {@link DocumentElement} 时立即调用此方法
     * 写入默认置信度，后续由 OCR 引擎按字/按行结果覆盖 ocrConfidence。</p>
     *
     * @param element 待填充置信度的元素
     */
    public static void stampOcrConfidence(DocumentElement element) {
        if (element == null) {
            return;
        }
        element.setParseConfidence(CONFIDENCE);
        if (element.getOcrConfidence() < 0) {
            element.setOcrConfidence(OCR_CONFIDENCE);
        }
    }
}
