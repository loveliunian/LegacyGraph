package io.github.legacygraph.understanding.tool.adapter;

import io.github.legacygraph.understanding.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * MCP 代码图谱适配器 —— 对接 codebase-memory MCP Server。
 *
 * <p>实现 {@link CodeUnderstandingToolAdapter} 接口，提供以下能力：</p>
 * <ul>
 *   <li>{@link ToolCapability#SEARCH_SYMBOL}: 搜索代码符号</li>
 *   <li>{@link ToolCapability#TRACE_CALL}: 追踪调用链</li>
 *   <li>{@link ToolCapability#READ_SNIPPET}: 读取代码片段</li>
 * </ul>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>所有外部调用通过 {@link McpClientFacade} 门面，方便 mock 测试</li>
 *   <li>异常不向上抛 —— 捕获后返回 UNAVAILABLE/FAILED 状态</li>
 *   <li>结果经由 {@link McpToolResultMapper} 归一化</li>
 * </ul>
 *
 * @author LegacyGraph
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpCodeGraphAdapter implements CodeUnderstandingToolAdapter {

    /** 工具唯一标识 */
    private static final String ADAPTER_TOOL_NAME = "codebase-memory-mcp";

    /** 调用关系标签（用于 TRACE_CALL Cypher 查询） */
    private static final String RELATIONSHIP_CALLS = "CALLS";

    /** indexStatus 健康检查超时阈值（毫秒） */
    private static final long HEALTH_TIMEOUT_MS = 5_000L;

    private final McpClientFacade mcpClientFacade;

    @Override
    public String toolName() {
        return ADAPTER_TOOL_NAME;
    }

    @Override
    public ToolKind toolKind() {
        return ToolKind.MCP;
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return Set.of(ToolCapability.SEARCH_SYMBOL, ToolCapability.TRACE_CALL, ToolCapability.READ_SNIPPET);
    }

    /**
     * 健康检查 —— 调用 MCP 的 indexStatus 确认服务可达。
     *
     * <p>超时会 catch 异常并返回 UNAVAILABLE，外部调用者据此降级到其他工具。</p>
     *
     * @param context 工具上下文（包含 projectRoot 等）
     * @return 健康状态
     */
    @Override
    public ToolHealth checkHealth(ToolContext context) {
        long start = System.currentTimeMillis();
        try {
            String projectName = resolveProjectName(context);
            Map<String, Object> status = mcpClientFacade.indexStatus(projectName);
            String freshness = extractFreshness(status);

            return ToolHealth.builder()
                    .toolName(ADAPTER_TOOL_NAME)
                    .toolKind(ToolKind.MCP)
                    .status(ToolStatus.READY)
                    .capabilities(capabilities())
                    .indexFreshness(freshness)
                    .message("MCP 服务正常，索引新鲜度: " + freshness)
                    .build();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("MCP 健康检查失败 [{}ms]: {}", elapsed, e.getMessage());
            return ToolHealth.builder()
                    .toolName(ADAPTER_TOOL_NAME)
                    .toolKind(ToolKind.MCP)
                    .status(ToolStatus.UNAVAILABLE)
                    .capabilities(Collections.emptySet())
                    .indexFreshness("UNKNOWN")
                    .message("MCP 服务不可达: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 执行工具请求 —— 按 operation 路由到对应的 MCP 调用。
     *
     * <p>三层降级：</p>
     * <ol>
     *   <li>不支持的操作 → status=FAILED</li>
     *   <li>MCP 调用异常 → status=FAILED，不抛异常</li>
     *   <li>正常返回 → status=SUCCESS，含 evidenceRecords</li>
     * </ol>
     *
     * @param request 工具请求
     * @return 归一化的执行结果
     */
    @Override
    public ToolResult execute(ToolRequest request) {
        long start = System.currentTimeMillis();
        ToolCapability operation = request.getOperation();
        Map<String, Object> parameters = request.getParameters();

        try {
            Map<String, Object> rawResult;
            long elapsed;

            switch (operation) {
                case SEARCH_SYMBOL -> {
                    String query = getStringParam(parameters, "query", "");
                    rawResult = mcpClientFacade.searchGraph(query);
                    elapsed = System.currentTimeMillis() - start;
                    return McpToolResultMapper.fromSearchGraph(rawResult, operation, elapsed);
                }
                case READ_SNIPPET -> {
                    String filePath = getStringParam(parameters, "filePath", "");
                    int lineStart = getIntParam(parameters, "lineStart", 1);
                    int lineEnd = getIntParam(parameters, "lineEnd", lineStart + 50);
                    rawResult = mcpClientFacade.getCodeSnippet(filePath, lineStart, lineEnd);
                    elapsed = System.currentTimeMillis() - start;
                    return McpToolResultMapper.fromCodeSnippet(rawResult, operation, elapsed);
                }
                case TRACE_CALL -> {
                    String symbolQn = getStringParam(parameters, "symbolQn", "");
                    // 构建 Cypher 查询：追踪 CALLS 关系的上下游
                    String cypher = buildTraceCallCypher(symbolQn);
                    rawResult = mcpClientFacade.queryGraph(cypher);
                    elapsed = System.currentTimeMillis() - start;
                    return McpToolResultMapper.fromQueryGraph(rawResult, operation, elapsed);
                }
                default -> {
                    long elapsedDefault = System.currentTimeMillis() - start;
                    log.warn("不支持的操作类型: {}", operation);
                    return McpToolResultMapper.fromError(operation, "不支持的操作类型: " + operation, elapsedDefault);
                }
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("MCP 调用失败 [{}ms] operation={}: {}", elapsed, operation, e.getMessage(), e);
            return McpToolResultMapper.fromError(operation, e.getMessage(), elapsed);
        }
    }

    // ──────────────────────────── 内部辅助方法 ────────────────────────────

    /**
     * 从上下文中解析项目名。
     */
    private String resolveProjectName(ToolContext context) {
        if (context == null || context.getProjectId() == null) {
            return "default";
        }
        return context.getProjectId();
    }

    /**
     * 从 indexStatus 结果中提取索引新鲜度。
     */
    private String extractFreshness(Map<String, Object> status) {
        if (status == null) {
            return "UNKNOWN";
        }
        Object freshness = status.get("indexFreshness");
        if (freshness == null) {
            freshness = status.get("freshness");
        }
        return freshness != null ? freshness.toString() : "UNKNOWN";
    }

    /**
     * 构建追踪调用链的 Cypher 查询。
     */
    private String buildTraceCallCypher(String symbolQn) {
        // MATCH (a)-[:CALLS]-(b) WHERE a.qualifiedName CONTAINS $symbolQn
        return String.format(
                "MATCH (a)-[:%s]-(b) WHERE a.qualifiedName CONTAINS '%s' RETURN a, b LIMIT 50",
                RELATIONSHIP_CALLS, escapeCypher(symbolQn));
    }

    /**
     * 简单的 Cypher 字符串转义（防止注入）。
     */
    private String escapeCypher(String input) {
        if (input == null) {
            return "";
        }
        // 转义单引号和反斜杠
        return input.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * 从参数 map 中安全获取 String 值。
     */
    private String getStringParam(Map<String, Object> params, String key, String defaultValue) {
        if (params == null) {
            return defaultValue;
        }
        Object value = params.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * 从参数 map 中安全获取 int 值。
     */
    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        if (params == null) {
            return defaultValue;
        }
        Object value = params.get(key);
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
