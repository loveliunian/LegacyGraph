package io.github.legacygraph.common;

/** 扫描流水线步骤枚举 — 状态机驱动 */
public enum ScanStep {
    INIT,                      // 初始化
    PARSE_FILES,               // 文件解析
    EXTRACT_FACTS,             // 事实抽取
    BUILD_GRAPH,               // 图谱构建
    MERGE_ENTITIES,            // 实体合并
    WRITE_INTENT,              // 写入意图
    GAP_FINDING,               // H14: 知识缺口发现（替代 ENHANCE for KnowledgeGapStep）
    UNDERSTANDING_ENHANCEMENT, // H14: 理解增强（替代 ENHANCE for UnderstandingEnhancementStep）
    PROCESS_MINING,            // H25: 流程挖掘一致性校验（PM4Py conformance checking）
    /**
     * @deprecated H14 拆分为 {@link #GAP_FINDING} 和 {@link #UNDERSTANDING_ENHANCEMENT}，
     *             保留 1 个版本用于兼容旧 scan_version 记录。
     */
    @Deprecated
    ENHANCE,                   // 增强分析（已拆分）
    INDEX,                     // 索引构建
    COMPLETE,                  // 完成
    FAILED;                    // 失败

    public boolean isTerminal() {
        return this == COMPLETE || this == FAILED;
    }

    public ScanStep next() {
        return switch (this) {
            case INIT -> PARSE_FILES;
            case PARSE_FILES -> EXTRACT_FACTS;
            case EXTRACT_FACTS -> BUILD_GRAPH;
            case BUILD_GRAPH -> MERGE_ENTITIES;
            case MERGE_ENTITIES -> WRITE_INTENT;
            case WRITE_INTENT -> GAP_FINDING;
            case GAP_FINDING -> UNDERSTANDING_ENHANCEMENT;
            case UNDERSTANDING_ENHANCEMENT -> PROCESS_MINING;
            case PROCESS_MINING -> INDEX;
            case ENHANCE -> INDEX; // @Deprecated: 兼容旧值，直接跳到 INDEX
            case INDEX -> COMPLETE;
            case COMPLETE, FAILED -> this;
        };
    }
}
