package io.github.legacygraph.service.document;

import io.github.legacygraph.entity.DocumentElement;

import java.util.List;

/**
 * 文档结构化切块服务接口（spec 4.2 / G-03 三层文档解析策略）。
 * <p>
 * 根据文件名/类型将文档切分为 {@link DocumentElement} 列表，供后续向量化与检索使用。
 * <p>
 * G-03 三层解析策略：每个实现对应一个解析层级（tier），由 {@link DocumentPartitionRouter}
 * 根据文档特征（文本层厚度、是否含表格/多列/页眉页脚）路由到合适的层级：
 * <ul>
 *   <li>{@code FAST}   — 快速文本解析（md/txt/简单 pdf/docx），置信度 0.95</li>
 *   <li>{@code LAYOUT} — 版面感知解析（PDF 含表格/多列/页眉页脚），置信度 0.85</li>
 *   <li>{@code OCR}    — OCR 兜底（扫描件/图片型 PDF），置信度 0.7，默认关闭</li>
 * </ul>
 */
public interface DocumentPartitionService {

    /**
     * 将文档内容切分为结构化元素列表（旧接口，保留向后兼容）。
     *
     * @param docId    文档 ID
     * @param fileName 文件名（用于判定文件类型与生成 sourceLocation）
     * @param content  文本内容（md/txt）或文件路径（docx/xlsx）
     * @return 结构化元素列表
     */
    List<DocumentElement> partition(String docId, String fileName, String content);

    /**
     * G-03 三层解析入口：按字节内容 + MIME 类型解析文档。
     * <p>
     * 默认实现抛出 {@link UnsupportedOperationException}，仅三层解析服务
     * （{@link FastPartitionService} / {@link LayoutPartitionService} / {@link OcrFallbackService}）
     * 需要覆写此方法。旧实现（如 {@code DefaultDocumentPartitionService}）继承默认实现即可保持编译通过。
     *
     * @param content  文档原始字节内容
     * @param mimeType MIME 类型（如 text/markdown、application/pdf）
     * @param fileName 文件名（用于生成 sourceLocation 与类型判定）
     * @return 结构化元素列表
     */
    default List<DocumentElement> partition(byte[] content, String mimeType, String fileName) {
        throw new UnsupportedOperationException(
                "此实现不支持三层解析（partition(byte[],String,String)），请使用 DocumentPartitionRouter");
    }

    /**
     * 返回此服务对应的解析层级标识。
     * <ul>
     *   <li>{@code FAST}   — 快速文本解析</li>
     *   <li>{@code LAYOUT} — 版面感知解析</li>
     *   <li>{@code OCR}    — OCR 兜底</li>
     * </ul>
     * 默认返回 {@code FAST}，旧实现继承此默认值。
     *
     * @return 层级标识字符串
     */
    default String supportedTier() {
        return "FAST";
    }
}
