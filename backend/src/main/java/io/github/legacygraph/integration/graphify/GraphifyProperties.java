package io.github.legacygraph.integration.graphify;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Graphify CLI 配置属性。
 */
@Data
@ConfigurationProperties(prefix = "legacygraph.graphify")
public class GraphifyProperties {

    /**
     * 是否启用 Graphify 集成
     */
    private boolean enabled = false;

    /**
     * Graphify CLI 可执行文件路径
     */
    private String executable = "graphify";

    /**
     * 命令超时时间（秒）
     */
    private int timeoutSeconds = 300;

    /**
     * 最大输出字节数
     */
    private long maxOutputBytes = 500_000;

    /**
     * 额外命令行参数
     */
    private List<String> extraArgs = List.of();

    /**
     * 工作目录白名单（空列表 = 允许所有目录）
     */
    private List<String> workDirWhitelist = List.of();

    /**
     * Graphify 输出目录名称
     */
    private String outputDirName = "graphify-out";

    /**
     * 是否保留临时文件（调试用）
     */
    private boolean keepTempFiles = false;
}
