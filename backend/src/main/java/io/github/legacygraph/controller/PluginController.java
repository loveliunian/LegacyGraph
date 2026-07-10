package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.plugin.ExternalPluginDescriptor;
import io.github.legacygraph.plugin.PluginRegistry;
import io.github.legacygraph.plugin.PluginRegistry.PluginDescriptor;
import io.github.legacygraph.plugin.PluginRegistry.PluginType;
import io.github.legacygraph.plugin.PluginStatusService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 插件管理 API（P3-1）
 * 提供插件注册表的查询接口，支持按类型过滤和按 ID 获取单个插件。
 * 支持插件的启用/禁用（可插拔视图插件动态注入菜单和路由）。
 */
@RestController
@RequestMapping("/lg/plugins")
public class PluginController {

    private final PluginRegistry registry;
    private final PluginStatusService statusService;

    public PluginController(PluginRegistry registry, PluginStatusService statusService) {
        this.registry = registry;
        this.statusService = statusService;
    }

    /**
     * 查询所有已注册插件，可选按类型过滤
     */
    @GetMapping
    public Result<List<PluginDescriptor>> list(@RequestParam(required = false) PluginType type) {
        if (type == null) {
            return Result.ok(registry.listAll());
        }
        return Result.ok(registry.listByType(type));
    }

    /**
     * 获取单个插件详情
     */
    @GetMapping("/{id}")
    public Result<PluginDescriptor> get(@PathVariable String id) {
        return Result.ok(registry.get(id));
    }

    /**
     * 动态注册外部插件（MCP/HTTP）。落地 04 阶段4：插件动态加载。
     * §11.2 安全加固：高危操作需要 ADMIN 角色
     */
    @PostMapping("/register")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PluginDescriptor> register(@RequestBody ExternalPluginDescriptor external) {
        return Result.ok(registry.registerExternal(external));
    }

    /**
     * 启用插件（ADMIN 操作）
     */
    @PostMapping("/{id}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PluginDescriptor> enable(@PathVariable String id) {
        statusService.enable(id);
        return Result.ok(registry.get(id));
    }

    /**
     * 禁用插件（ADMIN 操作）
     */
    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PluginDescriptor> disable(@PathVariable String id) {
        statusService.disable(id);
        return Result.ok(registry.get(id));
    }
}
