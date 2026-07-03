package io.github.legacygraph.understanding.tool;

/**
 * 工具类型枚举。
 */
public enum ToolKind {
    /** MCP Server 工具 */
    MCP,
    /** CLI 命令行工具 */
    CLI,
    /** 托管搜索（Sourcegraph 等） */
    HOSTED_SEARCH,
    /** 本地降级工具 */
    LOCAL
}
