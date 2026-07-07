package io.github.legacygraph.plugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.github.legacygraph.dto.plugin.ExternalPluginDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 插件注册中心（P3-1）
 * 统一管理 Scanner/Agent/Tool/GraphView 四类插件的注册与发现。
 * 所有插件通过 Spring Bean 自动注册，支持运行时查询和按类型过滤。
 * 支持运行时动态注册外部 MCP/HTTP 插件（04 阶段4）。
 */
@Slf4j
@Component
public class PluginRegistry {

    private final Map<String, PluginDescriptor> plugins = new ConcurrentHashMap<>();
    private final Map<String, ExternalPluginDescriptor> externalPlugins = new ConcurrentHashMap<>();

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
     * 动态注册外部插件（MCP/HTTP）。落地 04 阶段4：插件动态加载。
     */
    public PluginDescriptor registerExternal(ExternalPluginDescriptor ext) {
        if (ext.getId() == null || ext.getId().isBlank()) {
            throw new IllegalArgumentException("Plugin id cannot be blank");
        }
        PluginType type = PluginType.valueOf(ext.getPluginType().toUpperCase());
        Map<String, String> metadata = new java.util.HashMap<>();
        if (ext.getMcpEndpoint() != null) metadata.put("mcpEndpoint", ext.getMcpEndpoint());
        if (ext.getProtocol() != null) metadata.put("protocol", ext.getProtocol());
        PluginDescriptor descriptor = new PluginDescriptor(
                ext.getId(), ext.getName(), ext.getDescription(), type, "1.0.0", metadata);
        register(descriptor);
        externalPlugins.put(ext.getId(), ext);
        log.info("Registered external plugin: {} ({})", ext.getId(), ext.getMcpEndpoint());
        return descriptor;
    }

    /**
     * 获取外部插件描述符。
     */
    public ExternalPluginDescriptor getExternal(String id) {
        return externalPlugins.get(id);
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
