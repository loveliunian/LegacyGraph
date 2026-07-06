package io.github.legacygraph.integration.graphify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Graphify CLI 命令构建器。
 * <p>
 * 构建 Graphify CLI 命令，支持：
 * <ul>
 *   <li>指定输出目录</li>
 *   <li>指定源文件目录</li>
 *   <li>自定义参数</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphifyCommandBuilder {

    private final GraphifyProperties properties;

    /**
     * 构建 Graphify 分析命令。
     *
     * @param sourceDir 源代码目录
     * @param outputDir 输出目录
     * @return 命令列表
     */
    public List<String> buildAnalyzeCommand(Path sourceDir, Path outputDir) {
        List<String> command = new ArrayList<>();

        // 1. 可执行文件
        command.add(properties.getExecutable());

        // 2. 基本参数
        command.add("analyze");
        command.add("--source");
        command.add(sourceDir.toAbsolutePath().toString());
        command.add("--output");
        command.add(outputDir.toAbsolutePath().toString());

        // 3. 额外参数
        if (properties.getExtraArgs() != null && !properties.getExtraArgs().isEmpty()) {
            command.addAll(properties.getExtraArgs());
        }

        log.debug("构建 Graphify 命令: {}", command);
        return command;
    }

    /**
     * 构建 Graphify 版本查询命令。
     *
     * @return 命令列表
     */
    public List<String> buildVersionCommand() {
        List<String> command = new ArrayList<>();
        command.add(properties.getExecutable());
        command.add("--version");
        return command;
    }

    /**
     * 构建 Graphify 帮助命令。
     *
     * @return 命令列表
     */
    public List<String> buildHelpCommand() {
        List<String> command = new ArrayList<>();
        command.add(properties.getExecutable());
        command.add("--help");
        return command;
    }
}
