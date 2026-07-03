package io.github.legacygraph.understanding.tool.adapter;

import io.github.legacygraph.understanding.tool.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 本地降级适配器 —— 始终可用的兜底工具。
 *
 * <p>职责：
 * <ul>
 *   <li>当所有外部工具（MCP / CLI / HOSTED_SEARCH）不可用时，提供基本的代码理解能力</li>
 *   <li>checkHealth() 永远返回 READY —— 不依赖任何外部命令</li>
 *   <li>capabilities() 返回 SEARCH_SYMBOL / READ_SNIPPET / READ_RESOURCE</li>
 *   <li>MVP 阶段 execute() 返回空结果 —— 后续接入 Neo4jGraphDao / VectorRetrievalService / 文件读取</li>
 * </ul>
 */
@Slf4j
@Component
public class LocalFallbackAdapter implements CodeUnderstandingToolAdapter {

    /** 工具唯一名称 */
    public static final String TOOL_NAME = "local-fallback";

    /** 本地降级工具支持的能力 */
    private static final Set<ToolCapability> CAPABILITIES = Collections.unmodifiableSet(
            EnumSet.of(
                    ToolCapability.SEARCH_SYMBOL,
                    ToolCapability.READ_SNIPPET,
                    ToolCapability.READ_RESOURCE
            )
    );

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    @Override
    public ToolKind toolKind() {
        return ToolKind.LOCAL;
    }

    /**
     * 健康检查 —— 本地降级工具永远就绪。
     * 不依赖任何外部命令或服务，始终返回 READY。
     */
    @Override
    public ToolHealth checkHealth(ToolContext context) {
        return ToolHealth.builder()
                .toolName(TOOL_NAME)
                .toolKind(ToolKind.LOCAL)
                .status(ToolStatus.READY)
                .capabilities(CAPABILITIES)
                .indexFreshness("FRESH")
                .message("本地降级工具始终可用")
                .build();
    }

    /**
     * 工具能力集合 —— MVP 阶段返回基础能力。
     */
    @Override
    public Set<ToolCapability> capabilities() {
        return CAPABILITIES;
    }

    /**
     * 执行工具请求 —— MVP 阶段返回空结果占位。
     * <p>
     * 后续迭代将接入：
     * <ul>
     *   <li>Neo4jGraphDao —— 图查询</li>
     *   <li>VectorRetrievalService —— 向量检索</li>
     *   <li>文件系统直接读取 —— 简单文件操作</li>
     * </ul>
     */
    @Override
    public ToolResult execute(ToolRequest request) {
        log.debug("LocalFallbackAdapter 收到请求: operation={}, projectId={}",
                request.getOperation(), request.getProjectId());

        // MVP 阶段返回空结果
        return ToolResult.builder()
                .toolName(TOOL_NAME)
                .toolKind(ToolKind.LOCAL)
                .operation(request.getOperation())
                .status("SUCCESS")
                .exitCode(0)
                .elapsedMs(0L)
                .indexFreshness("FRESH")
                .stdoutExcerpt("(MVP 占位 —— 后续接入 Neo4j / 向量检索 / 文件读取)")
                .evidenceRecords(Collections.emptyList())
                .build();
    }
}
