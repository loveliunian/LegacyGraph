package io.github.legacygraph.dto.plugin;

import lombok.Data;

import java.util.List;

/**
 * 插件安装测试结果。
 * <p>
 * 按协议分层执行多阶段检查（连通性/握手/鉴权/能力），每阶段独立输出结果。
 * 用于注册前预检和已注册插件复测。
 * </p>
 */
@Data
public class PluginTestResult {

    /** 插件 ID */
    private String pluginId;

    /** 协议：MCP / HTTP */
    private String protocol;

    /** 总体状态：READY / UNAVAILABLE / AUTH_FAILED / PROTOCOL_ERROR */
    private String overallStatus;

    /** 总耗时（毫秒） */
    private long elapsedMs;

    /** 分阶段检查结果 */
    private List<PluginCheckResult> checks;

    /** 总体消息 */
    private String message;
}
