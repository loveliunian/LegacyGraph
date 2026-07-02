package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.graph.GraphWriteIntent;
import io.github.legacygraph.entity.GraphWriteIntentEntity;
import io.github.legacygraph.repository.GraphWriteIntentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 图谱写入意图服务 — outbox 写入、幂等、重试管理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphWriteIntentService {

    private final GraphWriteIntentRepository repository;
    private final ObjectMapper objectMapper;

    private static final int MAX_RETRY = 3;

    /**
     * 幂等写入意图。
     */
    public GraphWriteIntentEntity enqueue(GraphWriteIntent intent) {
        if (intent.getIdempotencyKey() == null || intent.getIdempotencyKey().isBlank()) {
            intent.setIdempotencyKey(computeIdempotencyKey(intent));
        }

        // 幂等检查
        GraphWriteIntentEntity existing = findByIdempotencyKey(intent.getIdempotencyKey());
        if (existing != null) {
            log.debug("GraphWriteIntent idempotencyKey={} already exists, status={}",
                    intent.getIdempotencyKey(), existing.getStatus());
            return existing;
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(intent);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize GraphWriteIntent", e);
        }

        GraphWriteIntentEntity entity = new GraphWriteIntentEntity();
        entity.setProjectId(intent.getProjectId());
        entity.setVersionId(intent.getVersionId());
        entity.setIdempotencyKey(intent.getIdempotencyKey());
        entity.setSource(intent.getSource() != null ? intent.getSource() : "SCAN");
        entity.setPayloadJson(payloadJson);
        entity.setStatus("PENDING");
        entity.setRetryCount(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        repository.insert(entity);
        log.debug("Enqueued GraphWriteIntent id={}, idempotencyKey={}", entity.getId(), intent.getIdempotencyKey());
        return entity;
    }

    /**
     * 拉取待处理的意图列表。
     */
    public List<GraphWriteIntentEntity> fetchPending(int limit) {
        return repository.selectList(
                new LambdaQueryWrapper<GraphWriteIntentEntity>()
                        .eq(GraphWriteIntentEntity::getStatus, "PENDING")
                        .or(w -> w.eq(GraphWriteIntentEntity::getStatus, "RETRYING")
                                .le(GraphWriteIntentEntity::getRetryCount, MAX_RETRY))
                        .orderByAsc(GraphWriteIntentEntity::getCreatedAt)
                        .last("LIMIT " + limit)
        );
    }

    /**
     * 标记开始处理。
     */
    public void markRunning(String id) {
        GraphWriteIntentEntity entity = new GraphWriteIntentEntity();
        entity.setId(id);
        entity.setStatus("RUNNING");
        entity.setUpdatedAt(LocalDateTime.now());
        repository.updateById(entity);
    }

    /**
     * 标记成功。
     */
    public void markSuccess(String id) {
        GraphWriteIntentEntity entity = new GraphWriteIntentEntity();
        entity.setId(id);
        entity.setStatus("SUCCESS");
        entity.setFinishedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        repository.updateById(entity);
    }

    /**
     * 标记失败，触发重试。
     */
    public void markFailed(String id, String error) {
        GraphWriteIntentEntity existing = repository.selectById(id);
        if (existing == null) return;

        GraphWriteIntentEntity entity = new GraphWriteIntentEntity();
        entity.setId(id);
        entity.setRetryCount((existing.getRetryCount() != null ? existing.getRetryCount() : 0) + 1);
        entity.setLastError(error != null && error.length() > 500 ? error.substring(0, 500) : error);
        entity.setStatus(entity.getRetryCount() >= MAX_RETRY ? "FAILED" : "RETRYING");
        entity.setUpdatedAt(LocalDateTime.now());
        repository.updateById(entity);
    }

    private GraphWriteIntentEntity findByIdempotencyKey(String idempotencyKey) {
        List<GraphWriteIntentEntity> results = repository.selectList(
                new LambdaQueryWrapper<GraphWriteIntentEntity>()
                        .eq(GraphWriteIntentEntity::getIdempotencyKey, idempotencyKey)
        );
        return results.isEmpty() ? null : results.get(0);
    }

    private String computeIdempotencyKey(GraphWriteIntent intent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = intent.getProjectId() + "|" + intent.getVersionId() + "|"
                    + (intent.getSource() != null ? intent.getSource() : "SCAN") + "|"
                    + canonicalNodeClaims(intent) + "|"
                    + canonicalEdgeClaims(intent) + "|"
                    + canonicalEvidence(intent);
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private List<String> canonicalNodeClaims(GraphWriteIntent intent) {
        if (intent.getNodeClaims() == null) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        intent.getNodeClaims().forEach(claim -> values.add(String.join("|",
                value(claim.getNodeType()),
                value(claim.getNodeKey()),
                value(claim.getNodeName()),
                value(claim.getSourceType()),
                value(claim.getSourcePath()),
                value(claim.getConfidence()),
                value(claim.getStatus()),
                value(claim.getProperties()))));
        Collections.sort(values);
        return values;
    }

    private List<String> canonicalEdgeClaims(GraphWriteIntent intent) {
        if (intent.getEdgeClaims() == null) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        intent.getEdgeClaims().forEach(claim -> values.add(String.join("|",
                value(claim.getEdgeType()),
                value(claim.getEdgeKey()),
                value(claim.getFromNodeId()),
                value(claim.getFromNodeKey()),
                value(claim.getToNodeId()),
                value(claim.getToNodeKey()),
                value(claim.getSourceType()),
                value(claim.getConfidence()),
                value(claim.getStatus()),
                value(claim.getProperties()),
                value(claim.getClaimId()))));
        Collections.sort(values);
        return values;
    }

    private List<String> canonicalEvidence(GraphWriteIntent intent) {
        if (intent.getEvidenceRecords() == null) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        intent.getEvidenceRecords().forEach(record -> values.add(String.join("|",
                value(record.getEvidenceType()),
                value(record.getSourcePath()),
                value(record.getSourceName()),
                value(record.getContentHash()),
                value(record.getSummary()),
                value(record.getMetadata()))));
        Collections.sort(values);
        return values;
    }

    private String value(Object value) {
        return Objects.toString(value, "");
    }
}
