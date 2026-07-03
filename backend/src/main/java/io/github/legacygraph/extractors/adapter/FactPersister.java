package io.github.legacygraph.extractors.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.repository.FactRepository;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fact 持久化助手（doc 3.2）— 从 {@code ProjectScanner} 抽取的 saveFact 逻辑，
 * 供各 {@link ExtractionAdapter} 复用，避免 Adapter 旁路 Fact 落库。
 */
@Slf4j
@Component
public class FactPersister {

    private final FactRepository factRepository;
    private final ObjectMapper objectMapper;

    public FactPersister(FactRepository factRepository, ObjectMapper objectMapper) {
        this.factRepository = factRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Fact 批量写入草稿 DTO。
     */
    @Data
    @Builder
    public static class FactDraft {
        private String projectId;
        private String versionId;
        private String sourceType;
        private String factType;
        private String factKey;
        private String factName;
        private String sourcePath;
        private Integer startLine;
        private Integer endLine;
        private Object data;
        private BigDecimal confidence;
        private String status;
    }

    /**
     * 批量保存事实（单次 DB 往返，替代逐条 upsert）。
     */
    public void saveFacts(List<FactDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        // 按冲突键去重，避免同一批次中出现重复的 (project_id, version_id, fact_type, fact_key)
        // 否则 PostgreSQL 会报: ON CONFLICT DO UPDATE command cannot affect row a second time
        Map<String, FactDraft> deduped = new LinkedHashMap<>();
        for (FactDraft draft : drafts) {
            String key = draft.getProjectId() + "|" + draft.getVersionId() + "|"
                    + draft.getFactType() + "|" + draft.getFactKey();
            deduped.put(key, draft);
        }
        if (deduped.size() < drafts.size()) {
            log.info("Deduplicated facts: {} -> {}", drafts.size(), deduped.size());
        }
        List<Fact> facts = new ArrayList<>(deduped.size());
        for (FactDraft draft : deduped.values()) {
            try {
                Fact fact = new Fact();
                fact.setId(UUID.randomUUID().toString());
                fact.setProjectId(draft.getProjectId());
                fact.setVersionId(draft.getVersionId());
                fact.setFactType(draft.getFactType());
                fact.setFactKey(draft.getFactKey());
                fact.setFactName(draft.getFactName());
                fact.setSourceType(draft.getSourceType());
                fact.setSourcePath(draft.getSourcePath());
                fact.setStartLine(draft.getStartLine());
                fact.setEndLine(draft.getEndLine());
                fact.setNormalizedData(objectMapper.writeValueAsString(draft.getData()));
                fact.setConfidence(draft.getConfidence() != null ? draft.getConfidence().doubleValue() : 0.0);
                fact.setStatus(draft.getStatus());
                fact.setCreatedAt(LocalDateTime.now());
                fact.setUpdatedAt(LocalDateTime.now());
                facts.add(fact);
            } catch (Exception ex) {
                log.warn("Skip invalid fact draft {}: {}", draft.getFactKey(), ex.getMessage());
            }
        }
        if (!facts.isEmpty()) {
            factRepository.batchUpsert(facts);
        }
    }

    /**
     * 保存抽取事实（使用 DB 级 INSERT ... ON CONFLICT DO NOTHING，消除 check-then-insert 竞态）。
     */
    public void saveFact(String projectId, String versionId, String sourceType, String factType,
                         String factKey, String factName, String sourcePath,
                         Integer startLine, Integer endLine, Object data,
                         BigDecimal confidence, String status) {
        try {
            Fact fact = new Fact();
            fact.setId(UUID.randomUUID().toString());
            fact.setProjectId(projectId);
            fact.setVersionId(versionId);
            fact.setFactType(factType);
            fact.setFactKey(factKey);
            fact.setFactName(factName);
            fact.setSourceType(sourceType);
            fact.setSourcePath(sourcePath);
            fact.setStartLine(startLine);
            fact.setEndLine(endLine);
            fact.setNormalizedData(objectMapper.writeValueAsString(data));
            fact.setConfidence(confidence != null ? confidence.doubleValue() : 0.0);
            fact.setStatus(status);
            fact.setCreatedAt(LocalDateTime.now());
            fact.setUpdatedAt(LocalDateTime.now());
            factRepository.upsert(fact);
        } catch (Exception e) {
            log.error("Failed to save fact", e);
        }
    }
}
