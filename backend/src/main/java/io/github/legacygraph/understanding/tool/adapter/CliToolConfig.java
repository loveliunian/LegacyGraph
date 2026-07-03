package io.github.legacygraph.understanding.tool.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CLI 工具适配器 Spring 配置 —— 负责创建 CliProcessRunner 和 CliCommandPolicy Bean。
 *
 * <p>工作目录白名单从配置 {@code legacy-graph.tool.cli.work-dir-whitelist} 读取，
 * 默认为空列表（允许所有目录，测试友好模式）。
 */
@Configuration(proxyBeanMethods = false)
public class CliToolConfig {

    /**
     * 工作目录白名单列表（从 YAML 配置读取）。
     * 每个条目是一个允许执行 CLI 命令的目录路径。
     */
    @Value("${legacy-graph.tool.cli.work-dir-whitelist:#{T(java.util.Collections).emptyList()}}")
    private List<String> workDirWhitelist;

    /**
     * 创建 CliProcessRunner Bean。
     *
     * @return CliProcessRunner 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public CliProcessRunner cliProcessRunner() {
        final Set<String> whitelist = workDirWhitelist != null
                ? new HashSet<>(workDirWhitelist)
                : Collections.emptySet();
        return new CliProcessRunner(whitelist);
    }

    /**
     * 创建 CliCommandPolicy Bean。
     *
     * @return CliCommandPolicy 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public CliCommandPolicy cliCommandPolicy() {
        final Set<String> whitelist = workDirWhitelist != null
                ? new HashSet<>(workDirWhitelist)
                : Collections.emptySet();
        return new CliCommandPolicy(whitelist);
    }
}
