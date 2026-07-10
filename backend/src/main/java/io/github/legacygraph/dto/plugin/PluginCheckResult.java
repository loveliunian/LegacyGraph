package io.github.legacygraph.dto.plugin;

import lombok.Data;

/**
 * 插件安装测试单项检查结果。
 */
@Data
public class PluginCheckResult {

    /** 检查阶段名称：connectivity / handshake / auth / capability */
    private String name;

    /** 状态：PASS / FAIL / SKIP */
    private String status;

    /** 该阶段耗时（毫秒） */
    private long elapsedMs;

    /** 详细消息 */
    private String message;

    public PluginCheckResult() {
    }

    public PluginCheckResult(String name, String status, long elapsedMs, String message) {
        this.name = name;
        this.status = status;
        this.elapsedMs = elapsedMs;
        this.message = message;
    }
}
