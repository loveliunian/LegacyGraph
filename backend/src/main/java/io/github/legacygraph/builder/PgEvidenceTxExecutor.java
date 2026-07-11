package io.github.legacygraph.builder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PG 证据写入事务执行器（批量模式）。
 *
 * <p>将逐条 {@link #execute(Runnable)} 改为入队 → 后台多线程批量 drain → 单事务提交，
 * 解决建图时数百个虚拟线程并发 {@code REQUIRES_NEW} 导致 HikariCP 连接池耗尽的问题。</p>
 *
 * <p>批量参数：最多等待 200ms 或攒够 100 条后 flush；超过 100 条立即 flush。</p>
 *
 * <p><b>背压保护：</b>队列有容量上限（{@link #QUEUE_CAPACITY}）。当消费端变慢
 * （如 PG 连接不可用，每批 flush 阻塞至连接超时）而建图阶段数百个虚拟线程持续入队时，
 * 无界队列会让证据写入闭包（强引用 GraphNode/GraphEdge/Evidence）无限堆积直至 OOM。
 * 改为有界队列后，队列满时对生产者施加背压（阻塞入队 + 超时丢弃，不降级同步写），
 * 使建图线程自然减速，把内存占用限制在可控范围。</p>
 *
 * <p><b>L-04：</b>新增 {@link #flushBlocking()} 用于扫描末段强制 drain，
 * Prometheus 指标 {@code legacygraph_evidence_queue_depth} / {@code legacygraph_evidence_degraded_writes_total}。</p>
 */
@Slf4j
@Component
public class PgEvidenceTxExecutor {

    private static final int BATCH_SIZE = 100;
    private static final long FLUSH_INTERVAL_MS = 200;
    /** 队列容量上限：约 80 批的缓冲，足以吸收正常突发，又能在消费停摆时及时施加背压 */
    private static final int QUEUE_CAPACITY = 8_000;
    /** 入队最长等待时间：超过则丢弃本条，避免生产者被无限期阻塞 */
    private static final long OFFER_TIMEOUT_MS = 5_000;
    /** 消费线程数：2 个 worker 并行 drain，提升消费吞吐 */
    private static final int WORKER_COUNT = 2;
    /** 内存高水位阈值：JVM 堆使用率超过此值时跳过当前批次（不降级同步写） */
    private static final double MEMORY_HIGH_WATERMARK = 0.85;
    /** L-04: flushBlocking 最长等待时间 */
    private static final long FLUSH_BLOCKING_TIMEOUT_MS = 30_000;

    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final List<Thread> workers = new ArrayList<>();
    private final PlatformTransactionManager txManager;
    private final TransactionTemplate txTemplate;

    /** L-04: 降级写入计数器（队列满超时丢弃 + 内存高水位跳批） */
    private final AtomicLong degradedWritesCount = new AtomicLong(0);

    /** L-04: Micrometer 指标注册（可选，测试环境可能无） */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    private Counter degradedWritesCounter;

    public PgEvidenceTxExecutor() {
        this(null);
    }

    /** 注入 PlatformTransactionManager，使用 TransactionTemplate 管理批量事务 */
    public PgEvidenceTxExecutor(PlatformTransactionManager txManager) {
        this.txManager = txManager;
        this.txTemplate = txManager != null ? new TransactionTemplate(txManager) : null;
        for (int i = 0; i < WORKER_COUNT; i++) {
            Thread t = Thread.ofVirtual().name("pg-evidence-batcher-" + i).start(this::workerLoop);
            workers.add(t);
        }
    }

    @PostConstruct
    void initMetrics() {
        if (meterRegistry != null) {
            // L-04: 队列深度 Gauge
            meterRegistry.gauge("legacygraph_evidence_queue_depth", queue, LinkedBlockingQueue::size);
            // L-04: 降级写入计数器
            degradedWritesCounter = Counter.builder("legacygraph_evidence_degraded_writes_total")
                    .description("Evidence writes skipped due to queue full or high memory watermark")
                    .register(meterRegistry);
            log.info("PgEvidenceTxExecutor metrics registered: queue_depth gauge + degraded_writes counter");
        }
    }

    /** worker 主循环：poll →攒批 → 内存水位检查 → 单事务批量提交 */
    private void workerLoop() {
        List<Runnable> batch = new ArrayList<>(BATCH_SIZE);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Runnable r = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (r != null) {
                    batch.add(r);
                }
                // flush when batch full, or after idle timeout with non-empty batch
                if (batch.size() >= BATCH_SIZE || (r == null && !batch.isEmpty())) {
                    if (isMemoryHigh()) {
                        log.warn("内存水位超过 {}，跳过当前批次（{} 条），不降级同步写",
                                MEMORY_HIGH_WATERMARK, batch.size());
                        degradedWritesCount.addAndGet(batch.size());
                        incrementDegradedCounter(batch.size());
                        batch.clear();
                        continue;
                    }
                    List<Runnable> toWrite = new ArrayList<>(batch);
                    batch.clear();
                    try {
                        executeBatch(toWrite);
                    } catch (Exception e) {
                        log.error("Batch evidence write failed ({} items): {}", toWrite.size(), e.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // drain remaining on shutdown
        List<Runnable> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            try { executeBatch(remaining); } catch (Exception e) { log.error("Final drain failed: {}", e.getMessage()); }
        }
    }

    /** 检查 JVM 堆内存使用率是否超过高水位阈值 */
    private boolean isMemoryHigh() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        if (max <= 0) {
            return false;
        }
        long used = runtime.totalMemory() - runtime.freeMemory();
        return (double) used / max > MEMORY_HIGH_WATERMARK;
    }

    private void incrementDegradedCounter(long count) {
        if (degradedWritesCounter != null) {
            degradedWritesCounter.increment(count);
        }
    }

    @PreDestroy
    public void shutdown() {
        for (Thread t : workers) {
            t.interrupt();
        }
        for (Thread t : workers) {
            try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** 入队（带背压），后台 worker 异步消费；队列满超时则丢弃，不降级同步写 */
    public void execute(Runnable pgWrite) {
        try {
            if (!queue.offer(pgWrite, OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("Evidence queue full after {}ms wait, skip (no sync write)", OFFER_TIMEOUT_MS);
                degradedWritesCount.incrementAndGet();
                incrementDegradedCounter(1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Evidence enqueue interrupted, skip (no sync write)");
            degradedWritesCount.incrementAndGet();
            incrementDegradedCounter(1);
        }
    }

    /** 批量事务：整批写在一个事务中，减少连接数；null/empty 直接 noop */
    public void executeBatch(List<Runnable> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        if (txTemplate != null) {
            txTemplate.execute(status -> {
                for (Runnable r : batch) {
                    r.run();
                }
                return null;
            });
        } else {
            for (Runnable r : batch) {
                r.run();
            }
        }
    }

    /** 强制 flush（扫描结束时调用，确保最后一批写入） */
    public void flush() {
        List<Runnable> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            try { executeBatch(remaining); } catch (Exception e) { log.error("Flush failed: {}", e.getMessage()); }
        }
    }

    /**
     * L-04: 阻塞式 flush — 等待队列排空 + worker 处理完在途批次，用于扫描末段强制 drain。
     * <p>最多等待 {@value #FLUSH_BLOCKING_TIMEOUT_MS}ms，超时后强制 drain 并告警。</p>
     */
    public void flushBlocking() {
        long deadline = System.currentTimeMillis() + FLUSH_BLOCKING_TIMEOUT_MS;
        // 1. 等待队列排空（worker 持续消费）
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!queue.isEmpty()) {
            log.warn("flushBlocking: queue still has {} items after {}ms timeout, forcing drain",
                    queue.size(), FLUSH_BLOCKING_TIMEOUT_MS);
        }
        // 2. 强制 drain 剩余项
        flush();
        // 3. 等待在途批次完成（FLUSH_INTERVAL_MS + buffer）
        try {
            Thread.sleep(FLUSH_INTERVAL_MS + 200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("flushBlocking complete: degradedWritesCount={}", degradedWritesCount.get());
    }
}
