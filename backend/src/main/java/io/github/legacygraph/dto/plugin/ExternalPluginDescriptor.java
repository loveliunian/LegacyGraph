package io.github.legacygraph.dto.plugin;

import lombok.Builder;
import lombok.Data;

/**
 * 外部插件描述符 — 动态注册外部 MCP/HTTP 工具插件。
 * <p>
 * 落地 {@code doc/系统关系总览/04-落地实施计划.md} 阶段4：插件动态加载。
 * </p>
 */
@Data
@Builder
public class ExternalPluginDescriptor {

    /** 插件 ID（唯一） */
    private String id;

    /** 插件名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 插件类型：TOOL / SCANNER / AGENT / GRAPH_VIEW */
    private String pluginType;

    /** MCP/HTTP 端点 URL */
    private String mcpEndpoint;

    /** 协议：MCP / HTTP */
    private String protocol;

    /** 鉴权信息（token/headers，可选） */
    private String auth;
}
