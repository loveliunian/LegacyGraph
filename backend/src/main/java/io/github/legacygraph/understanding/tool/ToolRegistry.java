package io.github.legacygraph.understanding.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册中心 —— 管理所有 CodeUnderstandingToolAdapter Bean。
 *
 * <p>职责：
 * <ul>
 *   <li>启动时收集所有实现了 CodeUnderstandingToolAdapter 的 Spring Bean</li>
 *   <li>按 ToolConfigProperties 过滤未启用的工具</li>
 *   <li>不因命令不存在而启动失败 —— 健康检查时才判定 NOT_INSTALLED</li>
 *   <li>提供按名称、按类型查询适配器的方法</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolRegistry {

    /** 已注册的适配器（工具名 -> 适配器） */
    private final Map<String, CodeUnderstandingToolAdapter> adapters = new ConcurrentHashMap<>();

    /** 工具配置 */
    private final ToolConfigProperties configProperties;

    /**
     * 构造器注入 —— Spring 自动注入所有 CodeUnderstandingToolAdapter Bean 和配置。
     */
    public ToolRegistry(List<CodeUnderstandingToolAdapter> adapterList,
                        ToolConfigProperties configProperties) {
        this.configProperties = configProperties;

        // 按配置过滤未启用的工具
        Map<String, ToolConfigProperties.ToolConfig> toolConfigs = configProperties.getTools();

        for (CodeUnderstandingToolAdapter adapter : adapterList) {
            String name = adapter.toolName();
            ToolConfigProperties.ToolConfig config = toolConfigs.get(name);

            if (config != null && !config.isEnabled()) {
                log.info("工具 [{}] 已在配置中禁用，跳过注册", name);
                continue;
            }

            adapters.put(name, adapter);
            log.info("已注册工具: name={}, kind={}, capabilities={}",
                    name, adapter.toolKind(), adapter.capabilities());
        }

        log.info("ToolRegistry 初始化完成，共注册 {} 个工具", adapters.size());
    }

    /**
     * 根据工具名获取适配器。
     *
     * @param toolName 工具唯一名称
     * @return 适配器实例，如果未注册则返回 Optional.empty()
     */
    public Optional<CodeUnderstandingToolAdapter> getAdapter(String toolName) {
        return Optional.ofNullable(adapters.get(toolName));
    }

    /**
     * 获取所有已注册的适配器列表。
     *
     * @return 所有适配器（按注册顺序）
     */
    public List<CodeUnderstandingToolAdapter> listAdapters() {
        return List.copyOf(adapters.values());
    }

    /**
     * 获取所有已注册的适配器名称。
     *
     * @return 工具名集合
     */
    public Set<String> listToolNames() {
        return Set.copyOf(adapters.keySet());
    }

    /**
     * 按工具类型获取适配器列表。
     *
     * @param kind 工具类型（MCP / CLI / HOSTED_SEARCH / LOCAL）
     * @return 匹配的适配器列表
     */
    public List<CodeUnderstandingToolAdapter> getAdaptersByKind(ToolKind kind) {
        return adapters.values().stream()
                .filter(a -> a.toolKind() == kind)
                .collect(Collectors.toList());
    }

    /**
     * 获取某个工具的配置（可能为 null —— 工具未在 application.yml 中显式配置）。
     */
    public Optional<ToolConfigProperties.ToolConfig> getToolConfig(String toolName) {
        return Optional.ofNullable(configProperties.getTools().get(toolName));
    }

    /**
     * 已注册工具数量。
     */
    public int size() {
        return adapters.size();
    }
}
