package io.github.legacygraph.understanding.tool;

/**
 * 工具能力枚举 —— 按能力选择工具，而非按工具名写死流程。
 */
public enum ToolCapability {
    /** 发现项目/代码库 */
    DISCOVER_PROJECT,
    /** 搜索符号（类、方法、字段） */
    SEARCH_SYMBOL,
    /** 追踪调用链 */
    TRACE_CALL,
    /** 读取资源（文件、配置） */
    READ_RESOURCE,
    /** 读取代码片段 */
    READ_SNIPPET,
    /** 打包上下文 */
    PACK_CONTEXT,
    /** 生成摘要 */
    SUMMARIZE,
    /** 运行 agent 研究（只读） */
    RUN_AGENT_RESEARCH,
    /** 验证证据 */
    VALIDATE_EVIDENCE
}
