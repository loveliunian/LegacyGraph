package io.github.legacygraph.integration.graphify;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

/**
 * Graphify CLI 运行结果。
 */
@Data
@Builder
public class GraphifyRunResult {

    /**
     * 是否成功
     */
    private final boolean success;

    /**
     * 进程退出码
     */
    private final int exitCode;

    /**
     * 标准输出
     */
    private final String stdout;

    /**
     * 标准错误
     */
    private final String stderr;

    /**
     * 执行耗时（毫秒）
     */
    private final long elapsedMs;

    /**
     * 是否超时
     */
    private final boolean timedOut;

    /**
     * 输出目录路径
     */
    private final Path outputDir;

    /**
     * graph.json 文件路径
     */
    private final Path graphJsonPath;

    /**
     * 解析到的节点数
     */
    private final int nodeCount;

    /**
     * 解析到的边数
     */
    private final int edgeCount;
}
