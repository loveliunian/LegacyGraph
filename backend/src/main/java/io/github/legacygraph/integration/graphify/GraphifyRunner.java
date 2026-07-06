package io.github.legacygraph.integration.graphify;

import io.github.legacygraph.understanding.tool.adapter.CliProcessRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Graphify CLI 运行器。
 * <p>
 * 负责：
 * <ul>
 *   <li>执行 Graphify CLI 命令</li>
 *   <li>管理临时输出目录</li>
 *   <li>解析 graph.json 输出</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphifyRunner {

    private final GraphifyProperties properties;
    private final GraphifyCommandBuilder commandBuilder;
    private final GraphifyGraphParser parser;

    /**
     * 运行 Graphify 分析并返回结果。
     *
     * @param sourceDir 源代码目录
     * @return 运行结果
     * @throws GraphifyRunException 运行失败时抛出
     */
    public GraphifyRunResult run(Path sourceDir) throws GraphifyRunException {
        if (!properties.isEnabled()) {
            throw new GraphifyRunException("Graphify 集成已禁用");
        }

        Path outputDir = null;
        try {
            // 1. 创建临时输出目录
            outputDir = createOutputDir(sourceDir);
            log.info("Graphify 输出目录: {}", outputDir);

            // 2. 构建命令
            List<String> command = commandBuilder.buildAnalyzeCommand(sourceDir, outputDir);
            log.info("执行 Graphify 命令: {}", command);

            // 3. 执行命令
            CliProcessRunner runner = new CliProcessRunner(
                    Set.copyOf(properties.getWorkDirWhitelist())
            );

            long startTime = System.currentTimeMillis();
            CliProcessRunner.ProcessResult processResult = runner.run(
                    sourceDir.toString(),
                    command,
                    properties.getTimeoutSeconds(),
                    properties.getMaxOutputBytes()
            );
            long elapsedMs = System.currentTimeMillis() - startTime;

            // 4. 检查执行结果
            if (processResult.timedOut()) {
                throw new GraphifyRunException("Graphify 执行超时: " + properties.getTimeoutSeconds() + "s");
            }

            if (processResult.exitCode() != 0) {
                throw new GraphifyRunException(
                        "Graphify 执行失败: exitCode=" + processResult.exitCode() +
                        ", stderr=" + processResult.stderr()
                );
            }

            // 5. 查找 graph.json
            Path graphJsonPath = outputDir.resolve("graph.json");
            if (!Files.exists(graphJsonPath)) {
                throw new GraphifyRunException("Graphify 未生成 graph.json: " + graphJsonPath);
            }

            // 6. 解析 graph.json
            GraphifyGraphParser.ParseResult parseResult = parser.parse(graphJsonPath);
            GraphifyGraphJson graph = parseResult.graph();

            // 7. 返回结果
            return GraphifyRunResult.builder()
                    .success(true)
                    .exitCode(processResult.exitCode())
                    .stdout(processResult.stdout())
                    .stderr(processResult.stderr())
                    .elapsedMs(elapsedMs)
                    .timedOut(processResult.timedOut())
                    .outputDir(outputDir)
                    .graphJsonPath(graphJsonPath)
                    .nodeCount(graph.nodes().size())
                    .edgeCount(graph.resolvedEdges().size())
                    .build();

        } catch (IOException e) {
            throw new GraphifyRunException("Graphify IO 错误: " + e.getMessage(), e);
        } catch (GraphifyGraphParser.GraphifyParseException e) {
            throw new GraphifyRunException("Graphify 解析错误: " + e.getMessage(), e);
        } finally {
            // 清理临时目录（除非配置为保留）
            if (!properties.isKeepTempFiles() && outputDir != null) {
                cleanupOutputDir(outputDir);
            }
        }
    }

    /**
     * 创建输出目录。
     */
    private Path createOutputDir(Path sourceDir) throws IOException {
        Path outputDir = sourceDir.resolve(properties.getOutputDirName());
        if (Files.exists(outputDir)) {
            // 清理旧目录
            deleteDirectory(outputDir);
        }
        Files.createDirectories(outputDir);
        return outputDir;
    }

    /**
     * 清理输出目录。
     */
    private void cleanupOutputDir(Path outputDir) {
        try {
            deleteDirectory(outputDir);
            log.debug("已清理 Graphify 输出目录: {}", outputDir);
        } catch (IOException e) {
            log.warn("清理 Graphify 输出目录失败: {}", outputDir, e);
        }
    }

    /**
     * 递归删除目录。
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }

        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)) // 子目录优先
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("删除文件失败: {}", path, e);
                        }
                    });
        }
    }

    /**
     * 检查 Graphify CLI 是否可用。
     *
     * @return 是否可用
     */
    public boolean isAvailable() {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            List<String> command = commandBuilder.buildVersionCommand();
            CliProcessRunner runner = new CliProcessRunner(Set.of());
            CliProcessRunner.ProcessResult result = runner.run(
                    System.getProperty("java.io.tmpdir"),
                    command,
                    10,
                    10_000
            );
            return result.exitCode() == 0;
        } catch (IOException e) {
            log.debug("Graphify CLI 不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Graphify 运行异常。
     */
    public static class GraphifyRunException extends Exception {
        public GraphifyRunException(String message) {
            super(message);
        }

        public GraphifyRunException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
