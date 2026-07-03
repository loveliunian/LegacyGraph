package io.github.legacygraph.service.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.repository.KnowledgeClaimRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识断言服务 — Claim 的幂等写入、状态计算、Fact 桥接。
 * <p>
 * 核心规则：
 * <ul>
 *   <li>按 (projectId, versionId, subjectType, subjectKey, predicate, objectType, objectKey) 去重</li>
 *   <li>AI 来源（DOC_AI / CODE_AI / AI_INFERENCE）默认 PENDING_CONFIRM，不自动 CONFIRMED</li>
 *   <li>CODE / DB / RUNTIME / TEST 来源且 confidence >= 0.85 可设为 CONFIRMED</li>
 *   <li>重复 upsert 时合并 evidenceIds，confidence 取 max</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeClaimService {

    private final KnowledgeClaimRepository claimRepository;
    private final ObjectMapper objectMapper;

    /**
     * 幂等写入单条 Claim draft。
     * 存在则合并 evidenceIds 和更新 confidence（取 max），不存在则插入。
     */
    @Transactional
    public KnowledgeClaim upsertDraft(KnowledgeClaimDraft draft) {
        KnowledgeClaim existing = findByClaimKey(
                draft.getProjectId(), draft.getVersionId(),
                draft.getSubjectType(), draft.getSubjectKey(),
                draft.getPredicate(),
                draft.getObjectType(), draft.getObjectKey()
        );

        if (existing != null) {
            return mergeExisting(existing, draft);
        }

        return insertNew(draft);
    }

    /**
     * 批量幂等写入。
     */
    @Transactional
    public List<KnowledgeClaim> upsertDrafts(List<KnowledgeClaimDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return Collections.emptyList();
        }
        return drafts.stream()
                .map(this::upsertDraft)
                .collect(Collectors.toList());
    }

    /**
     * 从 Fact 实体桥接为 Claim 并幂等写入。
     * 映射规则基于 factType 识别主体类型与谓词。
     */
    @Transactional
    public List<KnowledgeClaim> upsertFromFact(Fact fact) {
        if (fact == null) {
            return Collections.emptyList();
        }

        List<KnowledgeClaimDraft> drafts = new ArrayList<>();

        switch (fact.getFactType()) {
            case "FEATURE":
            case "CODE_FEATURE":
                drafts.add(buildDraft(fact, "Feature", fact.getFactKey(), "DESCRIBED_BY", "Evidence", fact.getId()));
                break;
            case "BUSINESS_OBJECT":
                drafts.add(buildDraft(fact, "BusinessObject", fact.getFactKey(), "MENTIONED_IN", "Evidence", fact.getId()));
                break;
            case "BUSINESS_RULE":
                drafts.add(buildDraft(fact, "BusinessRule", fact.getFactKey(), "MENTIONED_IN", "Evidence", fact.getId()));
                break;
            case "STATUS_TRANSITION":
                drafts.add(buildDraft(fact, "StateTransition", fact.getFactKey(), "MENTIONED_IN", "Evidence", fact.getId()));
                break;
            default:
                log.debug("Unknown factType for claim bridge: {}", fact.getFactType());
        }

        return upsertDrafts(drafts);
    }

    /**
     * 按项目+版本查询 Claim 列表（支持多种过滤条件）。
     */
    public List<KnowledgeClaim> listClaims(String projectId, String versionId,
                                           String subjectType, String predicate,
                                           String status, String sourceType,
                                           int limit) {
        LambdaQueryWrapper<KnowledgeClaim> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeClaim::getProjectId, projectId);
        if (versionId != null && !versionId.isEmpty()) {
            wrapper.eq(KnowledgeClaim::getVersionId, versionId);
        }
        if (subjectType != null && !subjectType.isEmpty()) {
            wrapper.eq(KnowledgeClaim::getSubjectType, subjectType);
        }
        if (predicate != null && !predicate.isEmpty()) {
            wrapper.eq(KnowledgeClaim::getPredicate, predicate);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(KnowledgeClaim::getStatus, status);
        }
        if (sourceType != null && !sourceType.isEmpty()) {
            wrapper.eq(KnowledgeClaim::getSourceType, sourceType);
        }
        wrapper.orderByDesc(KnowledgeClaim::getConfidence);
        wrapper.last("LIMIT " + normalizedLimit(limit));

        return claimRepository.selectList(wrapper);
    }

    /**
     * 按版本统计各类 Claim 数量。
     */
    public Map<String, Long> countClaimsByStatus(String projectId, String versionId) {
        LambdaQueryWrapper<KnowledgeClaim> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeClaim::getProjectId, projectId);
        wrapper.eq(KnowledgeClaim::getVersionId, versionId);
        wrapper.select(KnowledgeClaim::getStatus);

        List<KnowledgeClaim> claims = claimRepository.selectList(wrapper);
        return claims.stream()
                .collect(Collectors.groupingBy(KnowledgeClaim::getStatus, Collectors.counting()));
    }

    /**
     * 统计 AI-only Claim 数量（sourceType 包含 AI）。
     */
    public long countAiOnlyClaims(String projectId, String versionId) {
        LambdaQueryWrapper<KnowledgeClaim> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeClaim::getProjectId, projectId);
        wrapper.eq(KnowledgeClaim::getVersionId, versionId);
        wrapper.and(w -> w.like(KnowledgeClaim::getSourceType, "AI")
                .or().eq(KnowledgeClaim::getSourceType, "DOC_AI")
                .or().eq(KnowledgeClaim::getSourceType, "CODE_AI")
                .or().eq(KnowledgeClaim::getSourceType, "AI_INFERENCE"));

        return claimRepository.selectCount(wrapper);
    }

    public KnowledgeClaim getById(String id) {
        return claimRepository.selectById(id);
    }

    public KnowledgeClaim getClaim(String projectId, String id) {
        LambdaQueryWrapper<KnowledgeClaim> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeClaim::getProjectId, projectId);
        wrapper.eq(KnowledgeClaim::getId, id);
        return claimRepository.selectOne(wrapper);
    }

    // ──────────── Private helpers ────────────

    private KnowledgeClaim findByClaimKey(String projectId, String versionId,
                                          String subjectType, String subjectKey,
                                          String predicate,
                                          String objectType, String objectKey) {
        LambdaQueryWrapper<KnowledgeClaim> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeClaim::getProjectId, projectId);
        wrapper.eq(KnowledgeClaim::getVersionId, versionId);
        wrapper.eq(KnowledgeClaim::getSubjectType, subjectType);
        wrapper.eq(KnowledgeClaim::getSubjectKey, subjectKey);
        wrapper.eq(KnowledgeClaim::getPredicate, predicate);
        if (objectType != null) {
            wrapper.eq(KnowledgeClaim::getObjectType, objectType);
        } else {
            wrapper.isNull(KnowledgeClaim::getObjectType);
        }
        if (objectKey != null) {
            wrapper.eq(KnowledgeClaim::getObjectKey, objectKey);
        } else {
            wrapper.isNull(KnowledgeClaim::getObjectKey);
        }

        List<KnowledgeClaim> results = claimRepository.selectList(wrapper);
        if (results.size() > 1) {
            log.warn("Found multiple claims for unique key: projectId={}, versionId={}, subjectType={}, subjectKey={}, predicate={}, objectType={}, objectKey={}",
                    projectId, versionId, subjectType, subjectKey, predicate, objectType, objectKey);
        }
        return results.isEmpty() ? null : results.get(0);
    }

    private KnowledgeClaim insertNew(KnowledgeClaimDraft draft) {
        KnowledgeClaim claim = new KnowledgeClaim();
        claim.setId(UUID.randomUUID().toString());
        claim.setProjectId(draft.getProjectId());
        claim.setVersionId(draft.getVersionId());
        claim.setSubjectType(draft.getSubjectType());
        claim.setSubjectKey(draft.getSubjectKey());
        claim.setPredicate(draft.getPredicate());
        claim.setObjectType(draft.getObjectType());
        claim.setObjectKey(draft.getObjectKey());
        claim.setObjectValue(draft.getObjectValue());
        claim.setQualifiers(toJson(draft.getQualifiers()));
        claim.setEvidenceIds(toJson(draft.getEvidenceIds()));
        claim.setSupportingClaimIds("[]");
        claim.setContradictingClaimIds("[]");
        claim.setSourceType(draft.getSourceType());
        claim.setExtractor(draft.getExtractor());
        claim.setConfidence(draft.getConfidence() != null ? draft.getConfidence() : BigDecimal.valueOf(0.5));
        claim.setLineage("[]");

        // 状态计算
        claim.setStatus(computeStatus(draft.getSourceType(), draft.getConfidence()));

        claim.setCompileStatus("NEW");
        claim.setCreatedAt(LocalDateTime.now());
        claim.setUpdatedAt(LocalDateTime.now());

        claimRepository.insert(claim);
        log.debug("Inserted claim: id={}, subject={}:{}, predicate={}", claim.getId(), claim.getSubjectType(), claim.getSubjectKey(), claim.getPredicate());
        return claim;
    }

    private KnowledgeClaim mergeExisting(KnowledgeClaim existing, KnowledgeClaimDraft draft) {
        boolean updated = false;

        // 合并 evidenceIds
        String mergedEvidenceIds = mergeJsonArray(existing.getEvidenceIds(), toJson(draft.getEvidenceIds()));
        if (!mergedEvidenceIds.equals(existing.getEvidenceIds())) {
            existing.setEvidenceIds(mergedEvidenceIds);
            updated = true;
        }

        // confidence 取 max
        BigDecimal incomingConf = draft.getConfidence() != null ? draft.getConfidence() : BigDecimal.valueOf(0.5);
        if (incomingConf.compareTo(existing.getConfidence()) > 0) {
            existing.setConfidence(incomingConf);
            updated = true;
        }

        // 更新 sourceType：非 AI 证据不能被后来的 AI 推断降级。
        String mergedSourceType = mergeSourceType(existing.getSourceType(), draft.getSourceType(),
                existing.getConfidence(), incomingConf);
        if (!Objects.equals(mergedSourceType, existing.getSourceType())) {
            existing.setSourceType(mergedSourceType);
            updated = true;
        }

        // 重新计算状态（新来源可能允许 CONFIRMED）
        if (updated) {
            existing.setStatus(computeStatus(existing.getSourceType(), existing.getConfidence()));
        }

        existing.setUpdatedAt(LocalDateTime.now());

        if (updated) {
            claimRepository.updateById(existing);
        }
        return existing;
    }

    /**
     * 状态计算逻辑：
     * AI 来源必须保持 PENDING_CONFIRM，除非有其他非 AI 来源支持。
     * CODE/DB/RUNTIME/TEST 且 confidence >= 0.85 可设为 CONFIRMED。
     */
    private String computeStatus(String sourceType, BigDecimal confidence) {
        if (sourceType == null) {
            return "PENDING_CONFIRM";
        }

        boolean isAiSource = sourceType.equals("DOC_AI")
                || sourceType.equals("CODE_AI")
                || sourceType.equals("AI_INFERENCE")
                || sourceType.equals("AI");

        if (isAiSource) {
            return "PENDING_CONFIRM";
        }

        double conf = confidence != null ? confidence.doubleValue() : 0.5;
        if (conf >= 0.85 && (sourceType.equals("CODE") || sourceType.equals("DB")
                || sourceType.equals("RUNTIME") || sourceType.equals("TEST"))) {
            return "CONFIRMED";
        }

        return "PENDING_CONFIRM";
    }

    private String mergeSourceType(String existingSource, String incomingSource,
                                   BigDecimal existingConfidence, BigDecimal incomingConfidence) {
        if (incomingSource == null || incomingSource.isBlank()) {
            return existingSource;
        }
        if (existingSource == null || existingSource.isBlank()) {
            return incomingSource;
        }
        boolean existingAi = isAiSource(existingSource);
        boolean incomingAi = isAiSource(incomingSource);
        if (!existingAi && incomingAi) {
            return existingSource;
        }
        if (existingAi && !incomingAi) {
            return incomingSource;
        }
        BigDecimal existing = existingConfidence != null ? existingConfidence : BigDecimal.ZERO;
        BigDecimal incoming = incomingConfidence != null ? incomingConfidence : BigDecimal.ZERO;
        return incoming.compareTo(existing) > 0 ? incomingSource : existingSource;
    }

    private boolean isAiSource(String sourceType) {
        return sourceType != null && (sourceType.equals("DOC_AI")
                || sourceType.equals("CODE_AI")
                || sourceType.equals("AI_INFERENCE")
                || sourceType.equals("AI"));
    }

    private KnowledgeClaimDraft buildDraft(Fact fact, String subjectType, String subjectKey,
                                           String predicate, String objectType, String objectKey) {
        BigDecimal confidence = fact.getConfidence() != null
                ? BigDecimal.valueOf(fact.getConfidence())
                : BigDecimal.valueOf(0.5);

        List<String> evidenceIds = new ArrayList<>();
        if (fact.getId() != null) {
            evidenceIds.add(fact.getId());
        }

        return KnowledgeClaimDraft.builder()
                .projectId(fact.getProjectId())
                .versionId(fact.getVersionId())
                .subjectType(subjectType)
                .subjectKey(subjectKey)
                .predicate(predicate)
                .objectType(objectType)
                .objectKey(objectKey)
                .sourceType(fact.getSourceType() != null ? fact.getSourceType() : "AI")
                .extractor("FactBridge")
                .confidence(confidence)
                .evidenceIds(evidenceIds)
                .build();
    }

    /**
     * 合并两个 JSON 数组字符串，去重。
     */
    private String mergeJsonArray(String existingJson, String incomingJson) {
        Set<String> merged = new LinkedHashSet<>(parseJsonArray(existingJson));
        merged.addAll(parseJsonArray(incomingJson));
        return toJson(new ArrayList<>(merged));
    }

    private List<String> parseJsonArray(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty() || "[]".equals(jsonStr)) {
            return new ArrayList<>();
        }
        try {
            @SuppressWarnings("unchecked")
            List<String> list = objectMapper.readValue(jsonStr, List.class);
            return list != null ? list : new ArrayList<>();
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse JSON array: {}", jsonStr, e);
            return new ArrayList<>();
        }
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize to JSON: {}", obj, e);
            return "[]";
        }
    }

    private int normalizedLimit(int limit) {
        if (limit <= 0) {
            return 100;
        }
        return Math.min(limit, 500);
    }
}
