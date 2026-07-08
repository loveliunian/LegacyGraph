package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.builder.EvidenceGraphWriter;
import io.github.legacygraph.dto.graph.GraphWriteIntent;
import io.github.legacygraph.entity.GraphWriteIntentEntity;
import io.github.legacygraph.service.graph.GraphWriteIntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import io.github.legacygraph.util.IdUtil;

/**
 * 图谱写入意图后台执行器。
 * 定时拉取 outbox 中的 PENDING/RETRYING 意图，
 * 调用 EvidenceGraphWriter 写入 Neo4j。
 * <p>
 * 使用 CAS 锁（tryAcquireLock）防止多 worker 并发处理同一条意图。
 * 超过重试上限的意图进入死信队列（dead-letter）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphWriteIntentWorker {

    private final GraphWriteIntentService intentService;
    private final EvidenceGraphWriter evidenceGraphWriter;
    private final ObjectMapper objectMapper;

    private static final int BATCH_SIZE = 50;
    private static final int MAX_RETRY = 3;
    private static final int WORKER_CONCURRENCY = 4;
    private static final String WORKER_ID = "worker-" + IdUtil.fastUUID().substring(0, 8);

    /** 有界并发执行器 — 限制同时写入 Neo4j 的 intent 数量 */
    private final ExecutorService writeExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore concurrencyLimit = new Semaphore(WORKER_CONCURRENCY);

    /**
     * 每 30 秒执行一次，有界并发处理 pending intents。
     */
    @Scheduled(fixedDelay = 30_000)
    public void processPendingIntents() {
        List<GraphWriteIntentEntity> pending = intentService.fetchPending(BATCH_SIZE);
        if (pending.isEmpty()) return;

        log.debug("GraphWriteIntentWorker[{}]: processing {} pending intents (concurrency={})",
                WORKER_ID, pending.size(), WORKER_CONCURRENCY);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (GraphWriteIntentEntity entity : pending) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    concurrencyLimit.acquire();
                    try {
                        processIntent(entity);
                    } finally {
                        concurrencyLimit.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, writeExecutor));
        }

        // 等待所有 intent 处理完成（最多 2 分钟）
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .orTimeout(2, TimeUnit.MINUTES)
            .exceptionally(ex -> { log.warn("Intent processing timed out: {}", ex.getMessage()); return null; })
            .join();
    }

    private void processIntent(GraphWriteIntentEntity entity) {
        // CAS 锁：仅当 status=PENDING/RETRYING 且 running_lock=NULL 时成功
        if (!intentService.tryAcquireLock(entity.getId(), WORKER_ID)) {
            log.debug("GraphWriteIntent id={} locked by another worker, skipping", entity.getId());
            return;
        }

        try {
            GraphWriteIntent intent = objectMapper.readValue(entity.getPayloadJson(), GraphWriteIntent.class);
            evidenceGraphWriter.writeIntent(intent);

            intentService.markSuccess(entity.getId());
            log.debug("GraphWriteIntent id={} processed successfully", entity.getId());
        } catch (Exception e) {
            log.warn("GraphWriteIntent id={} failed: {}", entity.getId(), e.getMessage());

            int retryCount = (entity.getRetryCount() != null ? entity.getRetryCount() : 0) + 1;
            if (retryCount >= MAX_RETRY) {
                // 死信：超过最大重试次数
                log.error("GraphWriteIntent id={} exceeded max retries ({}), moving to dead-letter",
                        entity.getId(), MAX_RETRY);
                intentService.markDeadLetter(entity.getId(),
                        "Exceeded max retries (" + MAX_RETRY + "): " + e.getMessage());
            } else {
                intentService.markFailed(entity.getId(), e.getMessage());
            }
        }
    }
}
