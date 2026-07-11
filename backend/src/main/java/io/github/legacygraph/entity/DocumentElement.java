package io.github.legacygraph.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档结构化元素 — 文档切块后的最小语义单元。
 * <p>
 * 由 {@code DocumentPartitionService} 产出，作为向量化 / 切片入库 / QA 检索的中间载体。
 * 字段说明见 spec 4.1：
 * <ul>
 *   <li>type：元素类型枚举 TITLE / NARRATIVE_TEXT / TABLE / CODE_BLOCK</li>
 *   <li>headingPath：章节路径，如 ["结算需求","验收条件"]</li>
 *   <li>bbox：文本坐标（可选，用于 PDF 等）</li>
 *   <li>sourceLocation：来源位置，如 file.md#heading-path 或 file.xlsx#sheet:rowStart-rowEnd</li>
 *   <li>parseConfidence：解析置信度（0.0~1.0），由解析层级决定：FAST≈0.95、LAYOUT≈0.85、OCR≈0.7</li>
 *   <li>ocrConfidence：OCR 引擎对当前元素输出的字面置信度（0.0~1.0），仅 OCR 路径产出，非 OCR 路径默认 -1</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentElement {

    /** 元素唯一标识（由调用方分配或使用 UUID） */
    private String id;

    /** 所属文档 ID */
    private String docId;

    /** 元素类型 */
    private Type type;

    /** 元素文本内容 */
    private String text;

    /** 章节路径（从一级标题到当前层级） */
    private List<String> headingPath;

    /** 文本坐标（可选，用于 PDF 等） */
    private BBox bbox;

    /** 来源位置字符串 */
    private String sourceLocation;

    /** 解析置信度（0.0~1.0），由解析层级决定：FAST≈0.95、LAYOUT≈0.85、OCR≈0.7 */
    private double parseConfidence;

    /**
     * OCR 引擎字面置信度（0.0~1.0），仅 OCR 路径产出（FAST/LAYOUT 路径下保持 -1）。
     * 由 {@code OcrFallbackService} 在真实接入 Tesseract/其他 OCR 引擎后填入。
     */
    private double ocrConfidence = -1.0;

    /** 页码（从 1 开始），用于 PDF 等带分页文档定位；无分页信息时为 1 */
    private int pageNo;

    /** 元素类型枚举 */
    public enum Type {
        /** 标题 */
        TITLE,
        /** 普通叙述文本 */
        NARRATIVE_TEXT,
        /** 表格 */
        TABLE,
        /** 代码块 */
        CODE_BLOCK
    }

    /** 文本坐标（左上 / 右下），用于 PDF 等带版式文档定位。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BBox {
        /** 左上角 x */
        private double x0;
        /** 左上角 y */
        private double y0;
        /** 右下角 x */
        private double x1;
        /** 右下角 y */
        private double y1;
    }
}
