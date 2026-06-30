package io.github.legacygraph.extractors.adapter;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * 适配器能力描述。
 */
@Data
@Builder
public class AdapterCapability {

    /** 适配器名称 */
    private String name;

    /** 支持的编程语言 */
    private Set<String> languages;

    /** 支持的框架 */
    private Set<String> frameworks;

    /** 支持的文件类型 */
    private Set<String> fileTypes;

    /** 是否 AI 增强型适配器 */
    private boolean aiEnhanced;

    /** 优先级（数值越小越优先） */
    private int priority;

    public AdapterCapability() {}

    public AdapterCapability(String name, Set<String> languages,
                             Set<String> frameworks, Set<String> fileTypes,
                             boolean aiEnhanced, int priority) {
        this.name = name;
        this.languages = languages;
        this.frameworks = frameworks;
        this.fileTypes = fileTypes;
        this.aiEnhanced = aiEnhanced;
        this.priority = priority;
    }
}
