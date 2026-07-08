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
 */
@Slf4j
@Component
public class PgEvidenceTxExecutor {

    private static final int BATCH_SIZE = 100;
    private static final long FLUSH_INTERVAL_MS = 200;

    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
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

    /** 入队（非阻塞），批量子线程异步消费 */
    public void execute(Runnable pgWrite) {
        if (!queue.offer(pgWrite)) {
            // 队列满（极少发生）→ 降级为同步单条写
            log.warn("Evidence queue full, fallback to sync write");
            try { executeBatch(List.of(pgWrite)); } catch (Exception e) { /* best-effort */ }
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
