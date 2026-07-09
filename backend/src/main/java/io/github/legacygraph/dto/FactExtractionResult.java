package io.github.legacygraph.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 动态属性容器：LLM 返回的 attributes 字段是开放结构（任意键值）。
     * <p>序列化修复：原先 Jackson 找不到常规 Bean 属性，报
     * "No serializer found for class ItemAttributes and no properties discovered"，
     * 导致代码事实抽取结果写不进 Redis 缓存 → 重扫重复调 LLM、内存放大。
     * 通过 @JsonTypeInfo(none) 让 Jackson 识别为可序列化对象（不附加类型信息），
     * 并显式声明无参构造 + @JsonInclude(NON_EMPTY)。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
    public static class ItemAttributes {
        private final Map<String, Object> props = new HashMap<>();

        public ItemAttributes() {
        }

        @JsonAnySetter
        public void set(String key, Object value) { props.put(key, value); }

        @JsonAnyGetter
        public Map<String, Object> get() { return props; }
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
