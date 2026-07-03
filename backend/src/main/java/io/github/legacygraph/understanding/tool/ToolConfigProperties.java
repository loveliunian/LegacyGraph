package io.github.legacygraph.understanding.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具配置属性 —— 从 application.yml 读取 legacygraph.understanding.tools 配置。
 *
 * <p>设计原则：
 * <ul>
 *   <li>工具可插拔：通过 enabled 开关控制注册</li>
 *   <li>不因命令不存在而启动失败：checkHealth 时才判定 NOT_INSTALLED</li>
 *   <li>每个工具独立配置：path、超时、输出限制等</li>
 * </ul>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "legacygraph.understanding")
public class ToolConfigProperties {

    /**
     * 工具名 -> 工具配置 的映射。
     * key 示例：local-fallback, codebase-memory-mcp, codex, claude-code, zread
     */
    private Map<String, ToolConfig> tools = new HashMap<>();

    /**
     * 单个工具的配置。
     */
    @Data
    public static class ToolConfig {
        /** 是否启用该工具（默认 false） */
        private boolean enabled = false;
        /** 工具类型：MCP / CLI / HOSTED_SEARCH / LOCAL */
        private ToolKind kind = ToolKind.LOCAL;
        /** CLI 工具的命令路径（CLI 类型必填） */
        private String path;
        /** 执行超时时间（秒），默认 120 */
        private int timeoutSeconds = 120;
        /** 最大输出字节数，默认 100000 */
        private int maxOutputBytes = 100_000;
    }
}
