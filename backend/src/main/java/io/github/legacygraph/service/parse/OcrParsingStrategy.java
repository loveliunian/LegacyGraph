package io.github.legacygraph.service.parse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * G-03: OcrParsingStrategy — OCR 解析策略。
 * <p>
 * 处理扫描件/图片类文档：image/*, 扫描 PDF 等。
 * 调用外部 OCR 服务提取文本。
 * </p>
 * <p>
 * 当前为占位实现：OCR 服务地址通过 {@code legacygraph.ocr.endpoint} 配置，
 * 未配置时返回空字符串并记录 WARN 日志。未来接入真实 OCR 服务（如 Tesseract / 云 OCR API）。
 * </p>
 */
@Slf4j
@Component
public class OcrParsingStrategy implements DocumentParsingStrategy {

    @Value("${legacygraph.ocr.endpoint:}")
    private String ocrEndpoint;

    @Value("${legacygraph.ocr.enabled:false}")
    private boolean ocrEnabled;

    @Override
    public String strategyName() {
        return "OCR";
    }

    @Override
    public boolean supports(String mimeType, long fileSize) {
        if (mimeType == null) {
            return false;
        }
        String mt = mimeType.toLowerCase();
        // 图片类
        if (mt.startsWith("image/")) {
            return true;
        }
        // 扫描 PDF（大于 1MB 的 PDF 可能是扫描件）— 启发式判断
        if (mt.contains("pdf") && fileSize > 1_000_000) {
            return true;
        }
        return false;
    }

    @Override
    public String parse(String content, String mimeType) {
        if (!ocrEnabled || ocrEndpoint == null || ocrEndpoint.isBlank()) {
            log.warn("OcrParsingStrategy: OCR not configured (endpoint={}, enabled={}), returning empty for mimeType={}",
                    ocrEndpoint, ocrEnabled, mimeType);
            return "";
        }
        // TODO: 接入真实 OCR 服务
        // POST {ocrEndpoint} with body={ "image": content(Base64) }
        // 返回识别文本
        log.warn("OcrParsingStrategy: OCR endpoint configured but not yet implemented (endpoint={})", ocrEndpoint);
        return "";
    }
}
