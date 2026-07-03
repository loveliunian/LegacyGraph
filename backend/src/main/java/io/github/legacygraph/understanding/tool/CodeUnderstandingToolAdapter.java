package io.github.legacygraph.understanding.tool;

import java.util.Set;

/**
 * 代码理解工具适配器接口 —— 所有外部工具接入的统一契约。
 *
 * <p>设计原则：
 * <ul>
 *   <li>工具可插拔：新增工具只实现 Adapter，不改核心编排逻辑</li>
 *   <li>能力驱动路由：按能力选择工具，不按工具名写死流程</li>
 *   <li>结果可降级：任何外部工具不可用都不能导致系统 500</li>
 * </ul>
 */
public interface CodeUnderstandingToolAdapter {

    /** 工具唯一名称 */
    String toolName();

    /** 工具类型 */
    ToolKind toolKind();

    /** 健康检查 */
    ToolHealth checkHealth(ToolContext context);

    /** 工具提供的能力集合 */
    Set<ToolCapability> capabilities();

    /** 执行工具请求 */
    ToolResult execute(ToolRequest request);
}
