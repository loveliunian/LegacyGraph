package io.github.legacygraph.builder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * PG 证据写入事务执行器（批量模式）。
 *
 * <p>将逐条 {@link #execute(Runnable)} 改为入队 → 后台单线程批量 drain → 单事务提交，
 * 解决建图时数百个虚拟线程并发 {@code REQUIRES_NEW} 导致 HikariCP 连接池耗尽的问题。</p>
 *
 * <p>批量参数：最多等待 200ms 或攒够 100 条后 flush；超过 100 条立即 flush。</p>
 *
 * <p><b>背压保护：</b>队列有容量上限（{@link #QUEUE_CAPACITY}）。当消费端变慢
 * （如 PG 连接不可用，每批 flush 阻塞至连接超时）而建图阶段数百个虚拟线程持续入队时，
 * 无界队列会让证据写入闭包（强引用 GraphNode/GraphEdge/Evidence）无限堆积直至 OOM。
 * 改为有界队列后，队列满时对生产者施加背压（阻塞入队 + 超时降级同步写），
 * 使建图线程自然减速，把内存占用限制在可控范围。</p>
 */
@Slf4j
@Component
public class PgEvidenceTxExecutor {

    private static final int BATCH_SIZE = 100;
    private static final long FLUSH_INTERVAL_MS = 200;
    /** 队列容量上限：约 100 批的缓冲，足以吸收正常突发，又能在消费停摆时及时施加背压 */
    private static final int QUEUE_CAPACITY = 10_000;
    /** 入队最长等待时间：超过则降级为同步写，避免生产者被无限期阻塞 */
    private static final long OFFER_TIMEOUT_MS = 5_000;

    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread worker;

    public PgEvidenceTxExecutor() {
        worker = Thread.ofVirtual().name("pg-evidence-batcher").start(() -> {
            List<Runnable> batch = new ArrayList<>(BATCH_SIZE);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Runnable r = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                    if (r != null) {
                        batch.add(r);
                    }
                    // flush when batch full, or after idle timeout with non-empty batch
                    if (batch.size() >= BATCH_SIZE || (r == null && !batch.isEmpty())) {
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
        });
    }

    @PreDestroy
    public void shutdown() {
        worker.interrupt();
        try { worker.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** 入队（带背压），批量子线程异步消费 */
    public void execute(Runnable pgWrite) {
        try {
            // 队列未满则立即入队；已满则最多等待 OFFER_TIMEOUT_MS 让消费端追上（背压）。
            // 仍超时说明消费端持续停摆（如 PG 不可用），降级为同步单条写，
            // 避免生产者无限阻塞，也避免闭包在无界队列中堆积导致 OOM。
            if (!queue.offer(pgWrite, OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("Evidence queue full after {}ms wait, fallback to sync write", OFFER_TIMEOUT_MS);
                try { executeBatch(List.of(pgWrite)); } catch (Exception e) { /* best-effort */ }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 中断时降级同步写，尽量不丢证据
            try { executeBatch(List.of(pgWrite)); } catch (Exception ex) { /* best-effort */ }
        }
    }

    /** 批量事务：整批写在一个 REQUIRES_NEW 事务中，减少连接数 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeBatch(List<Runnable> batch) {
        for (Runnable r : batch) {
            r.run();
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
}
