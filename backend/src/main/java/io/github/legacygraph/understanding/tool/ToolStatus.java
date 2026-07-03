package io.github.legacygraph.understanding.tool;

/**
 * 工具状态枚举。
 */
public enum ToolStatus {
    /** 就绪可用 */
    READY,
    /** 未安装 */
    NOT_INSTALLED,
    /** 不可用（如连接失败） */
    UNAVAILABLE,
    /** 索引/数据过期 */
    STALE
}
