package io.github.legacygraph.plugin;

import java.util.List;
import java.util.Map;

import io.github.legacygraph.plugin.PluginRegistry.PluginDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 插件状态持久化服务
 * 负责从数据库加载插件的启用/禁用状态，以及持久化状态变更。
 * 使用 @Order 确保在 BuiltinPluginRegistrar 之后执行状态覆盖。
 */
@Slf4j
@Component
public class PluginStatusService {

    private final JdbcTemplate jdbcTemplate;
    private final PluginRegistry pluginRegistry;

    public PluginStatusService(JdbcTemplate jdbcTemplate, PluginRegistry pluginRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.pluginRegistry = pluginRegistry;
    }

    /**
     * 应用启动后，从数据库加载插件状态覆盖（在内置插件注册完成后执行）
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void applyStatusOverrides() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT plugin_id, enabled FROM plugin_status");
            for (Map<String, Object> row : rows) {
                String pluginId = (String) row.get("plugin_id");
                Boolean enabled = (Boolean) row.get("enabled");
                if (pluginId != null && enabled != null && pluginRegistry.get(pluginId) != null) {
                    pluginRegistry.setEnabled(pluginId, enabled);
                }
            }
            log.info("Applied plugin status overrides for {} plugins", rows.size());
        } catch (Exception e) {
            log.warn("Failed to apply plugin status overrides, using defaults: {}", e.getMessage());
        }
    }

    /**
     * 启用插件（更新内存 + 持久化到数据库）
     */
    public void enable(String pluginId) {
        PluginDescriptor p = pluginRegistry.get(pluginId);
        if (p == null) {
            throw new IllegalArgumentException("Plugin not found: " + pluginId);
        }
        pluginRegistry.setEnabled(pluginId, true);
        saveStatus(pluginId, true);
        log.info("Plugin enabled: {}", pluginId);
    }

    /**
     * 禁用插件（更新内存 + 持久化到数据库）
     */
    public void disable(String pluginId) {
        PluginDescriptor p = pluginRegistry.get(pluginId);
        if (p == null) {
            throw new IllegalArgumentException("Plugin not found: " + pluginId);
        }
        pluginRegistry.setEnabled(pluginId, false);
        saveStatus(pluginId, false);
        log.info("Plugin disabled: {}", pluginId);
    }

    private void saveStatus(String pluginId, boolean enabled) {
        int updated = jdbcTemplate.update(
                "UPDATE plugin_status SET enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE plugin_id = ?",
                enabled, pluginId);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO plugin_status (plugin_id, enabled, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                    pluginId, enabled);
        }
    }
}
