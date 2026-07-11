package io.github.legacygraph.service.qa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.qa.AccessContext;
import io.github.legacygraph.entity.VectorDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 检索后置过滤器 — 在多路召回结果合并后，执行 ACL/版本过滤与意图权重调整。
 *
 * <p>作为独立的后置过滤服务，与 {@link HybridRetrievalService} 的召回逻辑解耦：
 * <ul>
 *   <li>{@link #applyAclFilter} 过滤 aclPrincipals 不匹配与 graphReleaseId 不匹配的文档</li>
 *   <li>{@link #applyIntentWeights} 按意图权重矩阵调整结果排序</li>
 * </ul>
 *
 * <p>ACL 判断规则（与 {@link AclFilterService#aclPass} 保持一致）：
 * <ul>
 *   <li>文档 aclPrincipals 为 null 或空 → 通过（无 ACL 限制的公开文档）</li>
 *   <li>上下文 principals 为空且文档有 ACL 限制 → 拒绝</li>
 *   <li>两者均非空 → 取交集，有交集则通过</li>
 * </ul>
 *
 * <p>版本匹配规则（与 {@link AclFilterService#versionMatch} 保持一致）：
 * <ul>
 *   <li>文档 graphReleaseId 为 null 或空 → 通过（无版本约束，向后兼容）</li>
 *   <li>否则需与上下文 graphReleaseId 严格相等</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalPostFilter {

    private final ObjectMapper objectMapper;
    private final RetrievalIntentRouter retrievalIntentRouter;
    private final AclFilterService aclFilterService;

    /** aclPrincipals JSON 解析的类型引用 */
    private static final TypeReference<List<String>> PRINCIPALS_TYPE = new TypeReference<>() {};

    /** distance 缺失时的默认相关性分数（关键词召回无 distance，取中性值） */
    private static final double DEFAULT_RELEVANCE_SCORE = 0.5;

    /**
     * 应用 ACL 过滤（仅 ACL 维度，不含版本过滤）。
     *
     * <p>等价于 {@link #applyAclFilter(List, AccessContext, String)} 传入 {@code null} graphReleaseId，
     * 即跳过版本匹配检查。</p>
     *
     * @param rawResults 召回合并后的原始文档列表（null 安全，返回空列表）
     * @param ctx        访问上下文（null 时视为 PUBLIC，仅保留无 ACL 限制的文档）
     * @return 通过 ACL 校验的文档列表（保持原相对顺序）
     */
    public List<VectorDocument> applyAclFilter(List<VectorDocument> rawResults, AccessContext ctx) {
        return applyAclFilter(rawResults, ctx, null);
    }

    /**
     * 应用 ACL + 版本过滤。
     *
     * <p>过滤规则：
     * <ol>
     *   <li>ACL 过滤：文档 aclPrincipals（JSON 数组字符串）解析后与 ctx.principals() 取交集</li>
     *   <li>版本过滤：graphReleaseId 非空时，文档 graphReleaseId 需与其严格相等
     *       （文档 graphReleaseId 为空时视为无版本约束，允许通过）</li>
     * </ol>
     *
     * @param rawResults     召回合并后的原始文档列表（null 安全，返回空列表）
     * @param ctx            访问上下文（null 时视为 PUBLIC，仅保留无 ACL 限制的文档）
     * @param graphReleaseId 当前请求的图谱发布版本 ID（null/空时跳过版本过滤）
     * @return 通过 ACL 与版本校验的文档列表（保持原相对顺序）
     */
    public List<VectorDocument> applyAclFilter(List<VectorDocument> rawResults,
                                               AccessContext ctx,
                                               String graphReleaseId) {
        if (rawResults == null || rawResults.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ctxPrincipals = (ctx != null) ? ctx.principals() : Collections.emptyList();
        boolean versionFilterEnabled = graphReleaseId != null && !graphReleaseId.isBlank();

        List<VectorDocument> filtered = new ArrayList<>(rawResults.size());
        for (VectorDocument doc : rawResults) {
            if (doc == null) {
                continue;
            }
            // 1. ACL 校验
            List<String> docPrincipals = parsePrincipals(doc.getAclPrincipals());
            if (!aclFilterService.aclPass(docPrincipals, ctxPrincipals)) {
                log.debug("ACL denied: docId={}, docPrincipals={}, ctxPrincipals={}",
                    doc.getId(), docPrincipals, ctxPrincipals);
                continue;
            }
            // 2. 版本匹配校验
            if (versionFilterEnabled && !aclFilterService.versionMatch(doc.getGraphReleaseId(), graphReleaseId)) {
                log.debug("Version mismatch: docId={}, docRelease={}, ctxRelease={}",
                    doc.getId(), doc.getGraphReleaseId(), graphReleaseId);
                continue;
            }
            filtered.add(doc);
        }
        log.info("ACL+version filter: {} → {} documents (ctxPrincipals={}, graphReleaseId={})",
            rawResults.size(), filtered.size(), ctxPrincipals, graphReleaseId);
        return filtered;
    }

    /**
     * 按意图权重调整结果排序。
     *
     * <p>由于 {@link VectorDocument} 不记录召回来源（关键词/向量/图节点/Claim），
     * 本方法根据文档 {@code chunkType} 推断其归属的召回方式，并取对应权重：
     * <ul>
     *   <li>{@code DOC} → 关键词召回（{@link RetrievalIntentRouter#KEY_KEYWORD}）</li>
     *   <li>{@code CODE} → 向量召回（{@link RetrievalIntentRouter#KEY_VECTOR}）</li>
     *   <li>{@code DB} → 图节点召回（{@link RetrievalIntentRouter#KEY_GRAPH}）</li>
     *   <li>{@code UI} → Claim 召回（{@link RetrievalIntentRouter#KEY_CLAIM}）</li>
     *   <li>null/其他 → 项目约定召回（{@link RetrievalIntentRouter#KEY_CONVENTION}）</li>
     * </ul>
     *
     * <p>加权分数 = 基础相关性分数 × 召回方式权重，其中基础相关性分数由
     * {@code distance} 转换得到：{@code score = 1 / (1 + distance)}，
     * distance 缺失（如关键词召回）时取 {@link #DEFAULT_RELEVANCE_SCORE}。
     * 最终按加权分数降序排序，分数相同则保持原顺序（稳定排序）。</p>
     *
     * @param results 已通过 ACL 过滤的文档列表（null 安全，返回空列表）
     * @param intent  查询意图（null 时使用默认意图，权重均匀无影响）
     * @return 按意图权重调整排序后的文档列表（新列表，不修改入参）
     */
    public List<VectorDocument> applyIntentWeights(List<VectorDocument> results,
                                                   RetrievalIntentRouter.QueryIntent intent) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Double> weights = retrievalIntentRouter.getWeights(intent);

        // 带原始索引的包装，保证稳定排序
        List<IndexedDoc> indexed = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            VectorDocument doc = results.get(i);
            double weight = weights.getOrDefault(inferRetrievalKey(doc.getChunkType()), 0.0);
            double score = computeWeightedScore(doc, weight);
            indexed.add(new IndexedDoc(doc, score, i));
        }
        // 稳定排序：先按 score 降序，相同 score 按原索引升序
        indexed.sort(Comparator.comparingDouble(IndexedDoc::score).reversed()
            .thenComparingInt(IndexedDoc::originalIndex));

        List<VectorDocument> ranked = new ArrayList<>(indexed.size());
        for (IndexedDoc id : indexed) {
            ranked.add(id.doc);
        }
        log.info("Intent weights applied: intent={}, weights={}, resultCount={}", intent, weights, ranked.size());
        return ranked;
    }

    /**
     * 解析 aclPrincipals JSON 数组字符串为安全主体列表。
     *
     * <p>解析失败时返回空列表（视为无 ACL 限制，宽松放行，避免误拦截）。</p>
     */
    private List<String> parsePrincipals(String aclPrincipalsJson) {
        if (aclPrincipalsJson == null || aclPrincipalsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<String> parsed = objectMapper.readValue(aclPrincipalsJson, PRINCIPALS_TYPE);
            return (parsed == null) ? Collections.emptyList() : parsed;
        } catch (Exception e) {
            // JSON 解析失败：保守起见视为无 ACL 限制（宽松放行），仅记录 warn
            log.warn("Failed to parse aclPrincipals JSON: raw={}, error={}", aclPrincipalsJson, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 根据 chunkType 推断文档归属的召回方式权重键。
     */
    private String inferRetrievalKey(String chunkType) {
        if (chunkType == null || chunkType.isBlank()) {
            return RetrievalIntentRouter.KEY_CONVENTION;
        }
        return switch (chunkType.toUpperCase()) {
            case "DOC" -> RetrievalIntentRouter.KEY_KEYWORD;
            case "CODE" -> RetrievalIntentRouter.KEY_VECTOR;
            case "DB" -> RetrievalIntentRouter.KEY_GRAPH;
            case "UI" -> RetrievalIntentRouter.KEY_CLAIM;
            default -> RetrievalIntentRouter.KEY_CONVENTION;
        };
    }

    /**
     * 计算文档的加权分数 = 基础相关性分数 × 召回方式权重。
     */
    private double computeWeightedScore(VectorDocument doc, double weight) {
        double relevance = computeRelevanceScore(doc);
        return relevance * weight;
    }

    /**
     * 将 distance（pgvector 距离，越小越相似）转换为相关性分数（越大越相关）。
     *
     * <p>转换公式：{@code score = 1 / (1 + distance)}。
     * distance 为 null 时（如关键词召回无距离值）返回默认中性分数。</p>
     */
    private double computeRelevanceScore(VectorDocument doc) {
        Double distance = doc.getDistance();
        if (distance == null) {
            return DEFAULT_RELEVANCE_SCORE;
        }
        // distance ≥ 0，使用 1/(1+distance) 转换为 0~1 的分数
        return 1.0 / (1.0 + distance);
    }

    /** 带原始索引的文档包装，用于稳定排序 */
    private record IndexedDoc(VectorDocument doc, double score, int originalIndex) {}
}
