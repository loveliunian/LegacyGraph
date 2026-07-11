package io.github.legacygraph.service.parse;

/**
 * G-03: 文档解析策略接口 — 三层解析（FAST / LAYOUT / OCR）。
 * <p>
 * 根据文档类型和特征选择不同解析层级：
 * <ul>
 *   <li>{@link FastParsingStrategy} — 快速解析：纯文本/Markdown/源码，直接读取内容</li>
 *   <li>{@link LayoutParsingStrategy} — 版面解析：PDF/Word/HTML，保留版面结构</li>
 *   <li>{@link OcrParsingStrategy} — OCR 解析：扫描件/图片，调用 OCR 服务提取文本</li>
 * </ul>
 * </p>
 * <p>
 * 策略由 {@link DocumentParsingRouter} 根据 mimeType / 文件大小 / 特征自动选择。
 * </p>
 */
public interface DocumentParsingStrategy {

    /**
     * 解析层级标识。
     */
    String strategyName();

    /**
     * 是否支持给定的文档类型。
     *
     * @param mimeType 文档 MIME 类型
     * @param fileSize 文件大小（字节，-1 表示未知）
     * @return true 表示此策略可以处理该文档
     */
    boolean supports(String mimeType, long fileSize);

    /**
     * 解析文档内容为纯文本。
     *
     * @param content 原始内容（文本或 Base64 编码的二进制内容）
     * @param mimeType 文档 MIME 类型
     * @return 解析后的纯文本
     */
    String parse(String content, String mimeType);
}
