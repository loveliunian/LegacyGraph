package io.github.legacygraph.dto.graph;

/**
 * 证据隐私级别（见 doc/架构与三类图谱AI优化建议.md 4.4）。
 */
public enum PrivacyLevel {
    /** 公开 */
    PUBLIC,
    /** 内部 */
    INTERNAL,
    /** 机密 */
    CONFIDENTIAL,
    /** 绝密 — 禁止送入外部 LLM */
    SECRET;

    /**
     * 判断是否允许送入外部 LLM。
     */
    public boolean allowLLM() {
        return this == PUBLIC || this == INTERNAL;
    }

    /**
     * 判断是否需要脱敏。
     */
    public boolean needsRedaction() {
        return this == CONFIDENTIAL || this == SECRET;
    }
}
