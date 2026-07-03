package io.github.legacygraph.understanding;

import io.github.legacygraph.understanding.tool.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 工具路由器 —— 根据能力需求将计划步骤路由到最合适的工具。
 *
 * <p>路由优先级：
 * <ol>
 *   <li>MCP 结构化查询（SEARCH_SYMBOL, TRACE_CALL）</li>
 *   <li>CLI 深读（READ_SNIPPET, SUMMARIZE）</li>
 *   <li>本地降级（LocalFallbackAdapter）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRouter {

    private final ToolRegistry toolRegistry;

    /**
     * 根据计划步骤和可用工具状态选择工具。
     */
    public Optional<CodeUnderstandingToolAdapter> route(ToolQueryPlanner.PlanStep step, ToolPolicy policy) {
        ToolCapability capability = step.getCapability();
        ToolKind preferredKind = preferredKind(capability);

        // 按优先级尝试
        List<ToolKind> tryOrder = buildTryOrder(preferredKind);
        for (ToolKind kind : tryOrder) {
            if (policy.getEnabledToolKinds() != null
                    && !policy.getEnabledToolKinds().contains(kind)) {
                continue;
            }
            Optional<CodeUnderstandingToolAdapter> adapter = findAdapter(kind, capability, policy);
            if (adapter.isPresent()) {
                log.debug("路由: step={} → tool={}", step.getDescription(), adapter.get().toolName());
                return adapter;
            }
        }

        // 最终降级到本地 fallback
        CodeUnderstandingToolAdapter fallback = toolRegistry.getAdapter("local-fallback").orElse(null);
        if (fallback != null && fallback.checkHealth(
                ToolContext.builder().build()).getStatus() == ToolStatus.READY) {
            log.info("路由降级到本地: step={}", step.getDescription());
            return Optional.of(fallback);
        }

        log.warn("无可用的工具: capability={}", capability);
        return Optional.empty();
    }

    /**
     * 根据能力确定首选工具类型。
     */
    private ToolKind preferredKind(ToolCapability capability) {
        return switch (capability) {
            case SEARCH_SYMBOL, TRACE_CALL, DISCOVER_PROJECT -> ToolKind.MCP;
            case READ_SNIPPET, READ_RESOURCE, SUMMARIZE, RUN_AGENT_RESEARCH -> ToolKind.CLI;
            case PACK_CONTEXT, VALIDATE_EVIDENCE -> ToolKind.LOCAL;
        };
    }

    /**
     * 构建尝试顺序：首选 → 备选 → 本地。
     */
    private List<ToolKind> buildTryOrder(ToolKind preferred) {
        List<ToolKind> order = new ArrayList<>();
        order.add(preferred);
        if (preferred != ToolKind.LOCAL) {
            order.add(ToolKind.LOCAL);
        }
        // 其他类型作为备选
        for (ToolKind kind : ToolKind.values()) {
            if (!order.contains(kind)) {
                order.add(kind);
            }
        }
        return order;
    }

    /**
     * 查找具备指定能力且健康的适配器。
     */
    private Optional<CodeUnderstandingToolAdapter> findAdapter(
            ToolKind kind, ToolCapability capability, ToolPolicy policy) {
        List<CodeUnderstandingToolAdapter> adapters = toolRegistry.getAdaptersByKind(kind);
        for (CodeUnderstandingToolAdapter adapter : adapters) {
            // 检查工具是否在允许列表中
            if (policy.getAllowedTools() != null
                    && !policy.getAllowedTools().contains(adapter.toolName())) {
                continue;
            }
            // 检查能力
            if (!adapter.capabilities().contains(capability)) {
                continue;
            }
            // 检查健康状态
            ToolHealth health = adapter.checkHealth(
                    ToolContext.builder().build());
            if (health.getStatus() == ToolStatus.READY) {
                return Optional.of(adapter);
            }
        }
        return Optional.empty();
    }
}
