package io.github.legacygraph.plugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * 插件注册中心（P3-1）
 * 统一管理 Scanner/Agent/Tool/GraphView 四类插件的注册与发现。
 * 所有插件通过 Spring Bean 自动注册，支持运行时查询和按类型过滤。
 */
@Component
public class PluginRegistry {

    private final Map<String, PluginDescriptor> plugins = new ConcurrentHashMap<>();

    /**
     * 注册插件
     */
    public void register(PluginDescriptor descriptor) {
        plugins.put(descriptor.id(), descriptor);
    }

    /**
     * 注销插件
     */
    public void unregister(String id) {
        plugins.remove(id);
    }

    /**
     * 按类型查询插件
     */
    public List<PluginDescriptor> listByType(PluginType type) {
        return plugins.values().stream()
                .filter(p -> p.type() == type)
                .collect(Collectors.toList());
    }

    /**
     * 查询所有已注册插件
     */
    public List<PluginDescriptor> listAll() {
        return List.copyOf(plugins.values());
    }

    /**
     * 获取单个插件
     */
    public PluginDescriptor get(String id) {
        return plugins.get(id);
    }

    /**
     * 插件类型
     */
    public enum PluginType {
        SCANNER, AGENT, TOOL, GRAPH_VIEW
    }

    /**
     * 插件描述符
     */
    public record PluginDescriptor(
            String id,
            String name,
            String description,
            PluginType type,
            String version,
            Map<String, String> metadata
    ) {
        public PluginDescriptor(String id, String name, String description, PluginType type) {
            this(id, name, description, type, "1.0.0", Map.of());
        }
    }
}
