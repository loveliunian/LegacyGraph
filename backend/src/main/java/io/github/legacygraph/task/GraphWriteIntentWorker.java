package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.builder.EvidenceGraphWriter;
import io.github.legacygraph.dto.graph.GraphWriteIntent;
import io.github.legacygraph.entity.GraphWriteIntentEntity;
import io.github.legacygraph.service.GraphWriteIntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 图谱写入意图后台执行器。
 * 定时拉取 outbox 中的 PENDING/RETRYING 意图，
 * 调用 EvidenceGraphWriter 写入 Neo4j。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphWriteIntentWorker {

    private final GraphWriteIntentService intentService;
    private final EvidenceGraphWriter evidenceGraphWriter;
    private final ObjectMapper objectMapper;

    private static final int BATCH_SIZE = 10;

    /**
     * 每 30 秒执行一次。
     */
    @Scheduled(fixedDelay = 30_000)
    public void processPendingIntents() {
        List<GraphWriteIntentEntity> pending = intentService.fetchPending(BATCH_SIZE);
        if (pending.isEmpty()) return;

        log.debug("GraphWriteIntentWorker: processing {} pending intents", pending.size());

        for (GraphWriteIntentEntity entity : pending) {
            processIntent(entity);
        }
    }

    private void processIntent(GraphWriteIntentEntity entity) {
        try {
            intentService.markRunning(entity.getId());

            GraphWriteIntent intent = objectMapper.readValue(entity.getPayloadJson(), GraphWriteIntent.class);
            evidenceGraphWriter.writeIntent(intent);

            intentService.markSuccess(entity.getId());
            log.debug("GraphWriteIntent id={} processed successfully", entity.getId());
        } catch (Exception e) {
            log.warn("GraphWriteIntent id={} failed: {}", entity.getId(), e.getMessage());
            intentService.markFailed(entity.getId(), e.getMessage());
        }
    }
}
