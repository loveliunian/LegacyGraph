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

import java.util.List;
import java.util.UUID;
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

    private static final int BATCH_SIZE = 10;
    private static final int MAX_RETRY = 3;
    private static final String WORKER_ID = "worker-" + IdUtil.fastUUID().substring(0, 8);

    /**
     * 每 30 秒执行一次。
     */
    @Scheduled(fixedDelay = 30_000)
    public void processPendingIntents() {
        List<GraphWriteIntentEntity> pending = intentService.fetchPending(BATCH_SIZE);
        if (pending.isEmpty()) return;

        log.debug("GraphWriteIntentWorker[{}]: processing {} pending intents", WORKER_ID, pending.size());

        for (GraphWriteIntentEntity entity : pending) {
            processIntent(entity);
        }
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
