package io.github.legacygraph.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 事实抽取结果 - 对应文档中的 FactExtractionResult JSON Schema
 */
@Data
public class FactExtractionResult {

    private String factType;
    private String projectId;
    private List<FactItem> items;

    @Data
    public static class FactItem {
        private String key;
        private String name;
        private ItemAttributes attributes;
        private List<EvidenceRef> evidence;
        private BigDecimal confidence;
    }

    @Data
    public static class ItemAttributes {
        // 灵活属性，不同 factType 有不同属性
    }

    @Data
    public static class EvidenceRef {
        private String sourceType;
        private String sourceUri;
        private Integer lineStart;
        private Integer lineEnd;
        private String excerpt;
    }
}
