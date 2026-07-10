package io.github.legacygraph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 图谱发布配置 — 从 application.yml 读取 {@code legacygraph.graph-release} 配置。
 * <p>
 * 配置项：
 * <ul>
 *   <li>{@code legacygraph.graph-release.enabled} — 是否启用图谱发布功能，默认 {@code false}
 *       （功能开关，关闭时 startValidation 等接口不生效）</li>
 * </ul>
 * </p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "legacygraph.graph-release")
public class GraphReleaseConfig {

    /**
     * 是否启用图谱发布功能。
     * <p>
     * 默认 {@code false}，需在 application.yml 中显式设置
     * {@code legacygraph.graph-release.enabled=true} 才开启。
     * </p>
     */
    private boolean enabled = false;
}
