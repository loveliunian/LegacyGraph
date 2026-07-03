package io.github.legacygraph.understanding.tool.adapter;

import java.util.Map;

/**
 * MCP 客户端门面接口 —— 统一 MCP Server 通信契约。
 *
 * <p>所有对 codebase-memory MCP Server 的远程调用都通过此接口，
 * 实现可替换（fake/stub/real HTTP），便于测试和降级。</p>
 *
 * @author LegacyGraph
 */
public interface McpClientFacade {

    /**
     * 查询项目索引状态。
     *
     * @param projectName 项目名称/标识
     * @return 索引状态信息，包含 indexFreshness、fileCount 等
     */
    Map<String, Object> indexStatus(String projectName);

    /**
     * 搜索代码图谱中的符号。
     *
     * @param query 搜索关键词（符号名、类名、方法名等）
     * @return 搜索结果列表，每项包含 source_path、symbol_qn、line_start、line_end、excerpt
     */
    Map<String, Object> searchGraph(String query);

    /**
     * 读取指定文件中的代码片段。
     *
     * @param filePath  文件路径（相对于项目根目录）
     * @param lineStart 起始行号（1-based）
     * @param lineEnd   结束行号（1-based，包含）
     * @return 代码片段信息，包含 source_path、line_start、line_end、excerpt
     */
    Map<String, Object> getCodeSnippet(String filePath, int lineStart, int lineEnd);

    /**
     * 执行 Cypher 查询（调用链追踪）。
     *
     * @param cypherQuery Cypher 查询语句
     * @return 查询结果 map
     */
    Map<String, Object> queryGraph(String cypherQuery);
}
