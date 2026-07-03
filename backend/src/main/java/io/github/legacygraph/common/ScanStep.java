package io.github.legacygraph.common;

/** 扫描流水线步骤枚举 — 状态机驱动 */
public enum ScanStep {
    INIT,              // 初始化
    PARSE_FILES,       // 文件解析
    EXTRACT_FACTS,     // 事实抽取
    BUILD_GRAPH,       // 图谱构建
    MERGE_ENTITIES,    // 实体合并
    WRITE_INTENT,      // 写入意图
    ENHANCE,           // 增强分析
    INDEX,             // 索引构建
    COMPLETE,          // 完成
    FAILED;            // 失败
    
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
            case WRITE_INTENT -> ENHANCE;
            case ENHANCE -> INDEX;
            case INDEX -> COMPLETE;
            case COMPLETE, FAILED -> this;
        };
    }
}
