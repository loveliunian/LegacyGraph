package io.github.legacygraph.graphify;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Graphify 导入指标收集。
 * <p>
 * 使用 Micrometer 发布以下指标：
 * <ul>
 *   <li>{@code legacygraph.graphify.import.duration} - 导入耗时</li>
 *   <li>{@code legacygraph.graphify.import.success} - 导入成功次数</li>
 *   <li>{@code legacygraph.graphify.import.failures} - 导入失败次数</li>
 *   <li>{@code legacygraph.graphify.import.nodes} - 导入节点总数</li>
 *   <li>{@code legacygraph.graphify.import.edges} - 导入边总数</li>
 *   <li>{@code legacygraph.graphify.review.queue.size} - 审核队列大小</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class GraphifyMetrics {

    private final Timer importTimer;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter nodeCounter;
    private final Counter edgeCounter;
    private final AtomicLong reviewQueueSize;

    public GraphifyMetrics(MeterRegistry registry) {
        this.importTimer = Timer.builder("legacygraph.graphify.import.duration")
                .description("Graphify import duration")
                .register(registry);
        this.successCounter = Counter.builder("legacygraph.graphify.import.success")
                .description("Graphify import success count")
                .register(registry);
        this.failureCounter = Counter.builder("legacygraph.graphify.import.failures")
                .description("Graphify import failure count")
                .register(registry);
        this.nodeCounter = Counter.builder("legacygraph.graphify.import.nodes")
                .description("Total imported nodes")
                .register(registry);
        this.edgeCounter = Counter.builder("legacygraph.graphify.import.edges")
                .description("Total imported edges")
                .register(registry);
        this.reviewQueueSize = new AtomicLong(0);
        registry.gauge("legacygraph.graphify.review.queue.size", reviewQueueSize);
    }

    /**
     * 记录导入成功。
     *
     * @param nodes   节点数
     * @param edges   边数
     * @param attempt 尝试次数
     */
    public void recordSuccess(int nodes, int edges, int attempt) {
        successCounter.increment();
        nodeCounter.increment(nodes);
        edgeCounter.increment(edges);
        log.debug("Graphify import success: nodes={}, edges={}, attempt={}", nodes, edges, attempt);
    }

    /**
     * 记录导入失败。
     *
     * @param attempt 尝试次数
     */
    public void recordFailure(int attempt) {
        failureCounter.increment();
        log.debug("Graphify import failure: attempt={}", attempt);
    }

    /**
     * 记录导入耗时。
     *
     * @param durationMs 耗时（毫秒）
     */
    public void recordDuration(long durationMs) {
        importTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 更新审核队列大小。
     *
     * @param size 当前队列大小
     */
    public void updateReviewQueueSize(long size) {
        reviewQueueSize.set(size);
    }

    /**
     * 获取成功次数。
     */
    public double getSuccessCount() {
        return successCounter.count();
    }

    /**
     * 获取失败次数。
     */
    public double getFailureCount() {
        return failureCounter.count();
    }

    /**
     * 获取导入节点总数。
     */
    public double getTotalNodes() {
        return nodeCounter.count();
    }

    /**
     * 获取导入边总数。
     */
    public double getTotalEdges() {
        return edgeCounter.count();
    }
}
