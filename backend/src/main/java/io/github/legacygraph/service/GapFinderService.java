package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.entity.GapTask;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.repository.GapTaskRepository;
import io.github.legacygraph.repository.KnowledgeClaimRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识缺口发现服务 — 基于确定性规则在 Claim 层扫描 6 类缺口。
 * <p>
 * 缺口类型：
 * <ul>
 *   <li>doc_only_feature — Feature 只有文档来源 Claim，无实现证据</li>
 *   <li>code_only_feature — Feature 有代码实现 Claim，无文档来源</li>
 *   <li>feature_without_entry — Feature 没有任何入口 Claim（Page/API/ScheduledJob）</li>
 *   <li>feature_without_data_effect — Feature 没有数据读写 Claim</li>
 *   <li>business_object_without_table — BusinessObject 没有 MATERIALIZED_AS Table Claim</li>
 *   <li>rule_without_enforcement — BusinessRule 没有 ENFORCED_BY Claim</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GapFinderService {

    private final KnowledgeClaimRepository claimRepository;
    private final GapTaskRepository gapTaskRepository;
    private final ObjectMapper objectMapper;

    // ──────────── 缺口类型常量 ────────────
    private static final String DOC_ONLY_FEATURE = "doc_only_feature";
    private static final String CODE_ONLY_FEATURE = "code_only_feature";
    private static final String FEATURE_WITHOUT_ENTRY = "feature_without_entry";
    private static final String FEATURE_WITHOUT_DATA_EFFECT = "feature_without_data_effect";
    private static final String BUSINESS_OBJECT_WITHOUT_TABLE = "business_object_without_table";
    private static final String RULE_WITHOUT_ENFORCEMENT = "rule_without_enforcement";

    /**
     * 扫描缺口结果 DTO。
     */
    @Data
    @Builder
    public static class GapScanResult {
        private int created;
        private int reopened;
        private int unchanged;
        private Map<String, Integer> byType;
    }

    /**
     * 执行 6 类确定性缺口扫描。
     *
     * @return GapScanResult 含创建/重开/保持计数及按类型分布
     */
    @Transactional
    public GapScanResult scanGaps(String projectId, String versionId) {
        log.info("Scanning gaps: projectId={}, versionId={}", projectId, versionId);

        // 收集该版本所有 Claim，按 subjectType 分组
        List<KnowledgeClaim> allClaims = queryAllClaims(projectId, versionId);
        Map<String, List<KnowledgeClaim>> claimsBySubject = allClaims.stream()
                .collect(Collectors.groupingBy(KnowledgeClaim::getSubjectKey));

        List<GapTask> newGaps = new ArrayList<>();
        List<GapTask> reopenedGaps = new ArrayList<>();
        int unchanged = 0;

        // 找出所有 Feature 类型的 subjectKey
        Set<String> featureKeys = allClaims.stream()
                .filter(c -> "Feature".equals(c.getSubjectType()))
                .map(KnowledgeClaim::getSubjectKey)
                .collect(Collectors.toSet());

        for (String featureKey : featureKeys) {
            List<KnowledgeClaim> featureClaims = claimsBySubject.getOrDefault(featureKey, Collections.emptyList());

            // 1. doc_only_feature
            newGaps.addAll(scanDocOnlyFeature(projectId, versionId, featureKey, featureClaims, claimsBySubject));

            // 2. code_only_feature
            newGaps.addAll(scanCodeOnlyFeature(projectId, versionId, featureKey, featureClaims, claimsBySubject));

            // 3. feature_without_entry
            newGaps.addAll(scanFeatureWithoutEntry(projectId, versionId, featureKey, featureClaims, claimsBySubject));
        }

        // 4. feature_without_data_effect（按 Feature 检查）
        for (String featureKey : featureKeys) {
            List<KnowledgeClaim> featureClaims = claimsBySubject.getOrDefault(featureKey, Collections.emptyList());
            newGaps.addAll(scanFeatureWithoutDataEffect(projectId, versionId, featureKey, featureClaims, claimsBySubject));
        }

        // 5. business_object_without_table
        Set<String> boKeys = allClaims.stream()
                .filter(c -> "BusinessObject".equals(c.getSubjectType()))
                .map(KnowledgeClaim::getSubjectKey)
                .collect(Collectors.toSet());
        for (String boKey : boKeys) {
            List<KnowledgeClaim> boClaims = claimsBySubject.getOrDefault(boKey, Collections.emptyList());
            newGaps.addAll(scanBusinessObjectWithoutTable(projectId, versionId, boKey, boClaims, claimsBySubject));
        }

        // 6. rule_without_enforcement
        Set<String> ruleKeys = allClaims.stream()
                .filter(c -> "BusinessRule".equals(c.getSubjectType()))
                .map(KnowledgeClaim::getSubjectKey)
                .collect(Collectors.toSet());
        for (String ruleKey : ruleKeys) {
            List<KnowledgeClaim> ruleClaims = claimsBySubject.getOrDefault(ruleKey, Collections.emptyList());
            newGaps.addAll(scanRuleWithoutEnforcement(projectId, versionId, ruleKey, ruleClaims, claimsBySubject));
        }

        // 幂等写入
        int created = 0;
        int reopened = 0;
        for (GapTask gap : newGaps) {
            String action = upsertGap(gap);
            switch (action) {
                case "CREATED" -> created++;
                case "REOPENED" -> reopened++;
                case "UNCHANGED" -> unchanged++;
            }
        }

        Map<String, Integer> byType = newGaps.stream()
                .collect(Collectors.groupingBy(GapTask::getGapType, Collectors.summingInt(g -> 1)));

        log.info("Gap scan complete: projectId={}, versionId={}, created={}, reopened={}, unchanged={}",
                projectId, versionId, created, reopened, unchanged);

        return GapScanResult.builder()
                .created(created)
                .reopened(reopened)
                .unchanged(unchanged)
                .byType(byType)
                .build();
    }

    /**
     * 按项目+版本查询 GapTask 列表（支持多条件过滤）。
     */
    public List<GapTask> listGaps(String projectId, String versionId,
                                   String gapType, String status,
                                   String severity, int limit) {
        LambdaQueryWrapper<GapTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GapTask::getProjectId, projectId);
        if (versionId != null && !versionId.isEmpty()) {
            wrapper.eq(GapTask::getVersionId, versionId);
        }
        if (gapType != null && !gapType.isEmpty()) {
            wrapper.eq(GapTask::getGapType, gapType);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(GapTask::getStatus, status);
        }
        if (severity != null && !severity.isEmpty()) {
            wrapper.eq(GapTask::getSeverity, severity);
        }
        wrapper.orderByDesc(GapTask::getPriorityScore);
        wrapper.last("LIMIT " + normalizedLimit(limit));

        return gapTaskRepository.selectList(wrapper);
    }

    /**
     * 按版本统计各类 GapTask 数量。
     */
    public Map<String, Long> countGapsByStatus(String projectId, String versionId) {
        LambdaQueryWrapper<GapTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GapTask::getProjectId, projectId);
        wrapper.eq(GapTask::getVersionId, versionId);
        wrapper.select(GapTask::getStatus);

        List<GapTask> gaps = gapTaskRepository.selectList(wrapper);
        return gaps.stream()
                .collect(Collectors.groupingBy(GapTask::getStatus, Collectors.counting()));
    }

    /**
     * 按版本统计各类 GapTask 数量（按 gapType 分组）。
     */
    public Map<String, Long> countGapsByType(String projectId, String versionId) {
        LambdaQueryWrapper<GapTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GapTask::getProjectId, projectId);
        wrapper.eq(GapTask::getVersionId, versionId);
        wrapper.select(GapTask::getGapType);

        List<GapTask> gaps = gapTaskRepository.selectList(wrapper);
        return gaps.stream()
                .collect(Collectors.groupingBy(GapTask::getGapType, Collectors.counting()));
    }

    /**
     * 查询高严重度缺口数量。
     */
    public long countHighSeverityGaps(String projectId, String versionId) {
        LambdaQueryWrapper<GapTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GapTask::getProjectId, projectId);
        wrapper.eq(GapTask::getVersionId, versionId);
        wrapper.eq(GapTask::getStatus, "OPEN");
        wrapper.in(GapTask::getSeverity, "HIGH", "CRITICAL");

        return gapTaskRepository.selectCount(wrapper);
    }

    /**
     * 将 gap 标记为已解决。
     */
    @Transactional
    public boolean resolveGap(String id) {
        GapTask gap = gapTaskRepository.selectById(id);
        if (gap == null) {
            return false;
        }
        gap.setStatus("RESOLVED");
        gap.setUpdatedAt(LocalDateTime.now());
        gapTaskRepository.updateById(gap);
        return true;
    }

    @Transactional
    public boolean resolveGap(String projectId, String id) {
        LambdaQueryWrapper<GapTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GapTask::getProjectId, projectId);
        wrapper.eq(GapTask::getId, id);
        GapTask gap = gapTaskRepository.selectOne(wrapper);
        if (gap == null) {
            return false;
        }
        gap.setStatus("RESOLVED");
        gap.setUpdatedAt(LocalDateTime.now());
        gapTaskRepository.updateById(gap);
        return true;
    }

    // ──────────── 缺口扫描规则 ────────────

    private List<GapTask> scanDocOnlyFeature(String projectId, String versionId,
                                              String featureKey,
                                              List<KnowledgeClaim> featureClaims,
                                              Map<String, List<KnowledgeClaim>> claimsBySubject) {
        boolean hasDocClaim = featureClaims.stream()
                .anyMatch(c -> containsSource(c, "DOC"));
        boolean hasImplClaim = hasSubjectClaim(featureKey, claimsBySubject,
                "EXPOSED_BY", "IMPLEMENTS", "HANDLED_BY");

        if (hasDocClaim && !hasImplClaim) {
            return List.of(buildGap(projectId, versionId, DOC_ONLY_FEATURE, featureKey,
                    "Feature '" + featureKey + "' 仅有文档定义，无实现入口",
                    "Feature", featureKey, "HIGH", toClaimIds(featureClaims)));
        }
        return Collections.emptyList();
    }

    private List<GapTask> scanCodeOnlyFeature(String projectId, String versionId,
                                               String featureKey,
                                               List<KnowledgeClaim> featureClaims,
                                               Map<String, List<KnowledgeClaim>> claimsBySubject) {
        boolean hasCodeClaim = featureClaims.stream()
                .anyMatch(c -> containsSource(c, "CODE"));
        boolean hasDocClaim = featureClaims.stream()
                .anyMatch(c -> containsSource(c, "DOC"));

        if (hasCodeClaim && !hasDocClaim) {
            return List.of(buildGap(projectId, versionId, CODE_ONLY_FEATURE, featureKey,
                    "Feature '" + featureKey + "' 有代码实现，缺少文档描述",
                    "Feature", featureKey, "MEDIUM", toClaimIds(featureClaims)));
        }
        return Collections.emptyList();
    }

    private List<GapTask> scanFeatureWithoutEntry(String projectId, String versionId,
                                                   String featureKey,
                                                   List<KnowledgeClaim> featureClaims,
                                                   Map<String, List<KnowledgeClaim>> claimsBySubject) {
        boolean hasEntry = hasSubjectClaim(featureKey, claimsBySubject, "EXPOSED_BY");

        if (!hasEntry) {
            return List.of(buildGap(projectId, versionId, FEATURE_WITHOUT_ENTRY, featureKey,
                    "Feature '" + featureKey + "' 缺少触发入口（Page/API/ScheduledJob/MessageConsumer）",
                    "Feature", featureKey, "HIGH", toClaimIds(featureClaims)));
        }
        return Collections.emptyList();
    }

    private List<GapTask> scanFeatureWithoutDataEffect(String projectId, String versionId,
                                                        String featureKey,
                                                        List<KnowledgeClaim> featureClaims,
                                                        Map<String, List<KnowledgeClaim>> claimsBySubject) {
        boolean hasDataEffect = hasSubjectClaim(featureKey, claimsBySubject,
                "READS", "WRITES", "MATERIALIZED_AS");

        if (!hasDataEffect) {
            return List.of(buildGap(projectId, versionId, FEATURE_WITHOUT_DATA_EFFECT, featureKey,
                    "Feature '" + featureKey + "' 缺少数据读写路径",
                    "Feature", featureKey, "MEDIUM", toClaimIds(featureClaims)));
        }
        return Collections.emptyList();
    }

    private List<GapTask> scanBusinessObjectWithoutTable(String projectId, String versionId,
                                                          String boKey,
                                                          List<KnowledgeClaim> boClaims,
                                                          Map<String, List<KnowledgeClaim>> claimsBySubject) {
        boolean hasTable = boClaims.stream()
                .anyMatch(c -> "MATERIALIZED_AS".equals(c.getPredicate()) && "Table".equals(c.getObjectType()));

        if (!hasTable) {
            return List.of(buildGap(projectId, versionId, BUSINESS_OBJECT_WITHOUT_TABLE, boKey,
                    "BusinessObject '" + boKey + "' 没有映射到数据库表",
                    "BusinessObject", boKey, "MEDIUM", toClaimIds(boClaims)));
        }
        return Collections.emptyList();
    }

    private List<GapTask> scanRuleWithoutEnforcement(String projectId, String versionId,
                                                      String ruleKey,
                                                      List<KnowledgeClaim> ruleClaims,
                                                      Map<String, List<KnowledgeClaim>> claimsBySubject) {
        boolean hasEnforcement = hasSubjectClaim(ruleKey, claimsBySubject, "ENFORCED_BY");

        if (!hasEnforcement) {
            return List.of(buildGap(projectId, versionId, RULE_WITHOUT_ENFORCEMENT, ruleKey,
                    "BusinessRule '" + ruleKey + "' 没有找到代码实现",
                    "BusinessRule", ruleKey, "MEDIUM", toClaimIds(ruleClaims)));
        }
        return Collections.emptyList();
    }

    // ──────────── 幂等写入 ────────────

    /**
     * 幂等写入 GapTask。
     *
     * @return "CREATED" / "REOPENED" / "UNCHANGED"
     */
    private String upsertGap(GapTask gap) {
        LambdaQueryWrapper<GapTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GapTask::getProjectId, gap.getProjectId());
        wrapper.eq(GapTask::getVersionId, gap.getVersionId());
        wrapper.eq(GapTask::getGapType, gap.getGapType());
        wrapper.eq(GapTask::getGapKey, gap.getGapKey());

        GapTask existing = gapTaskRepository.selectOne(wrapper);

        if (existing == null) {
            gapTaskRepository.insert(gap);
            return "CREATED";
        }

        if ("RESOLVED".equals(existing.getStatus()) || "WONT_FIX".equals(existing.getStatus())) {
            // 之前已关闭，但缺口仍存在 → 重新打开
            existing.setStatus("REOPENED");
            existing.setDescription(gap.getDescription());
            existing.setRelatedClaimIds(gap.getRelatedClaimIds());
            existing.setRelatedNodeIds(gap.getRelatedNodeIds());
            existing.setEvidenceIds(gap.getEvidenceIds());
            existing.setUpdatedAt(LocalDateTime.now());
            gapTaskRepository.updateById(existing);
            return "REOPENED";
        }

        // OPEN / REOPENED / IN_PROGRESS — 更新描述和关联信息
        existing.setDescription(gap.getDescription());
        existing.setRelatedClaimIds(gap.getRelatedClaimIds());
        existing.setRelatedNodeIds(gap.getRelatedNodeIds());
        existing.setEvidenceIds(gap.getEvidenceIds());
        existing.setUpdatedAt(LocalDateTime.now());
        gapTaskRepository.updateById(existing);
        return "UNCHANGED";
    }

    // ──────────── Helper ────────────

    private List<KnowledgeClaim> queryAllClaims(String projectId, String versionId) {
        LambdaQueryWrapper<KnowledgeClaim> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeClaim::getProjectId, projectId);
        wrapper.eq(KnowledgeClaim::getVersionId, versionId);
        return claimRepository.selectList(wrapper);
    }

    /**
     * 检查以 featureKey 为 subjectKey 的 Claim 中是否有指定谓词。
     * 同时检查以 featureKey 为 objectKey 的 Claim（如 ApiEndpoint(mapping.apiKey) EXPOSED_BY Feature(featureKey)）。
     */
    private boolean hasSubjectClaim(String featureKey,
                                    Map<String, List<KnowledgeClaim>> claimsBySubject,
                                    String... predicates) {
        // 以 featureKey 为 subjectKey
        List<KnowledgeClaim> asSubject = claimsBySubject.getOrDefault(featureKey, Collections.emptyList());
        boolean fromSubject = asSubject.stream()
                .anyMatch(c -> Set.of(predicates).contains(c.getPredicate()));

        if (fromSubject) return true;

        // 以 featureKey 为 objectKey（反向边）
        for (Map.Entry<String, List<KnowledgeClaim>> entry : claimsBySubject.entrySet()) {
            boolean found = entry.getValue().stream()
                    .anyMatch(c -> Set.of(predicates).contains(c.getPredicate())
                            && featureKey.equals(c.getObjectKey()));
            if (found) return true;
        }

        return false;
    }

    private GapTask buildGap(String projectId, String versionId, String gapType, String gapKey,
                              String title, String subjectType, String subjectKey, String severity,
                              List<String> relatedClaimIds) {
        GapTask gap = new GapTask();
        gap.setId(UUID.randomUUID().toString());
        gap.setProjectId(projectId);
        gap.setVersionId(versionId);
        gap.setGapType(gapType);
        gap.setGapKey(gapKey);
        gap.setTitle(title);
        gap.setDescription(title);
        gap.setSeverity(severity);
        gap.setStatus("OPEN");
        gap.setSubjectType(subjectType);
        gap.setSubjectKey(subjectKey);
        gap.setRelatedClaimIds(toJson(relatedClaimIds));
        gap.setRelatedNodeIds("[]");
        gap.setEvidenceIds("[]");
        gap.setPriorityScore(BigDecimal.valueOf(0.5));
        gap.setCreatedAt(LocalDateTime.now());
        gap.setUpdatedAt(LocalDateTime.now());
        return gap;
    }

    private List<String> toClaimIds(List<KnowledgeClaim> claims) {
        return claims.stream().map(KnowledgeClaim::getId).collect(Collectors.toList());
    }

    private String toJson(Object obj) {
        if (obj == null) return "[]";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize to JSON", e);
            return "[]";
        }
    }

    private boolean containsSource(KnowledgeClaim claim, String token) {
        return claim != null && claim.getSourceType() != null && claim.getSourceType().contains(token);
    }

    private int normalizedLimit(int limit) {
        if (limit <= 0) {
            return 100;
        }
        return Math.min(limit, 500);
    }
}
