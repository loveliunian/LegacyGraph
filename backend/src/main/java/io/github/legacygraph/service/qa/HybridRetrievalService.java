package io.github.legacygraph.service.qa;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.dto.qa.Ranking;
import io.github.legacygraph.entity.DocChunk;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.DocChunkRepository;
import io.github.legacygraph.repository.VectorDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 混合检索服务 - 向量检索 + 关键词检索
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    private final VectorDocumentRepository vectorDocumentRepository;
    private final DocChunkRepository docChunkRepository;
    private final VectorRetrievalService vectorRetrievalService;
    private final ReciprocalRankFusionService reciprocalRankFusionService;

    /**
     * Task 8.4 Feature Flag：RRF 混合融合开关。
     * 关闭时（默认）走原 LinkedHashMap 去重逻辑（保持插入顺序，主查询优先）；
     * 开启时收集各路结果为 Ranking 列表，调用 ReciprocalRankFusionService.fuse。
     */
    @Value("${legacygraph.qa.rrf-enabled:false}")
    private boolean rrfEnabled;

    /** 召回专用虚拟线程执行器 */
    private final ExecutorService retrievalExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 多路召回 fan-out/fan-in：向量检索（主查询 + 变体）+ 关键词检索 全部并发执行。
     *
     * <p>原串行耗时 = T_main + ΣT_variants + T_keyword，
     * 优化后 ≈ max(T_main, T_variants..., T_keyword)，
     * 召回阶段延迟预期降低 30-50%。</p>
     *
     * <p>不带 ACL/Release 过滤，向后兼容入口。等价于
     * {@code retrieve(projectId, versionId, query, queryVariants, topK, null, null)}。</p>
     */
    public List<VectorDocument> retrieve(String projectId, String versionId,
                                         String query, List<String> queryVariants,
                                         int topK) {
        return retrieve(projectId, versionId, query, queryVariants, topK, null, null);
    }

    /**
     * 多路召回 fan-out/fan-in（带 ACL + GraphRelease 过滤）。
     *
     * <p>向量查询：将 graphReleaseId 和 aclPrincipal 透传给
     * {@link VectorRetrievalService#semanticSearch(String, String, String, int, String, String, String)}，
     * 受 {@code legacygraph.qa.graph-release-filter.enabled} feature flag 控制（关闭时退化为不过滤）。</p>
     *
     * <p>关键词查询：graphReleaseId 非空时按 {@code graph_release_id} 精确过滤；
     * principals 非空时仅返回 ACL 为空或包含任一 principal 的文档。</p>
     *
     * @param graphReleaseId 图谱发布 ID，null/空 时忽略（向量查询受 feature flag 控制）
     * @param principals     当前用户的安全主体列表，null/空 时忽略（退化为不过滤）
     */
    public List<VectorDocument> retrieve(String projectId, String versionId,
                                         String query, List<String> queryVariants,
                                         int topK, String graphReleaseId,
                                         List<String> principals) {
        // 统一解析 versionId，确保所有检索路径使用相同的 versionId
        String effectiveVersionId = resolveVersionId(projectId, versionId);

        // 1. 主查询向量检索（异步）
        CompletableFuture<List<VectorDocument>> mainFuture = CompletableFuture.supplyAsync(
            () -> safeSemanticSearch(projectId, effectiveVersionId, query, topK, graphReleaseId, principals),
            retrievalExecutor
        );

        // 2. 查询变体向量检索（每个变体异步）
        List<CompletableFuture<List<VectorDocument>>> variantFutures = new ArrayList<>();
        if (queryVariants != null) {
            for (String variant : queryVariants) {
                if (variant != null && !variant.isBlank()) {
                    variantFutures.add(CompletableFuture.supplyAsync(
                        () -> safeSemanticSearch(projectId, effectiveVersionId, variant, topK / 2, graphReleaseId, principals),
                        retrievalExecutor
                    ));
                }
            }
        }

        // 3. 关键词检索（异步）
        CompletableFuture<List<VectorDocument>> keywordFuture = CompletableFuture.supplyAsync(
            () -> safeKeywordSearch(projectId, effectiveVersionId, query, topK / 2, graphReleaseId, principals),
            retrievalExecutor
        );

        // 收集所有 future（每路带 source 标识与权重，用于 RRF 融合）
        List<RetrievalFuture> retrievalFutures = new ArrayList<>();
        List<CompletableFuture<List<VectorDocument>>> allFutures = new ArrayList<>();

        RetrievalFuture mainRetrieval = new RetrievalFuture(mainFuture, "vector-main", 1.0);
        retrievalFutures.add(mainRetrieval);
        allFutures.add(mainFuture);

        int vIdx = 0;
        for (CompletableFuture<List<VectorDocument>> vf : variantFutures) {
            retrievalFutures.add(new RetrievalFuture(vf, "vector-variant-" + (vIdx++), 0.8));
            allFutures.add(vf);
        }

        RetrievalFuture keywordRetrieval = new RetrievalFuture(keywordFuture, "keyword", 0.7);
        retrievalFutures.add(keywordRetrieval);
        allFutures.add(keywordFuture);

        // 等待全部完成（含超时保护）
        CompletableFuture.allOf(allFutures.toArray(CompletableFuture[]::new))
            .orTimeout(5, TimeUnit.SECONDS)
            .exceptionally(ex -> { log.warn("Retrieval timeout or error: {}", ex.getMessage()); return null; })
            .join();

        // fan-in：RRF 开启走加权倒数排名融合；关闭走原 LinkedHashMap 去重（保持插入顺序）
        List<VectorDocument> merged;
        if (rrfEnabled) {
            List<Ranking> rankings = new ArrayList<>(retrievalFutures.size());
            for (RetrievalFuture rf : retrievalFutures) {
                try {
                    List<VectorDocument> docs = rf.future.getNow(Collections.emptyList());
                    if (docs != null && !docs.isEmpty()) {
                        rankings.add(new Ranking(rf.source, docs, rf.weight));
                    }
                } catch (Exception e) {
                    // getNow 不抛异常，安全
                }
            }
            merged = reciprocalRankFusionService.fuse(rankings, topK);
            log.info("Hybrid retrieval (RRF): fused {} documents from {} rankings (main+{}variants+keyword)",
                merged.size(), rankings.size(), variantFutures.size());
        } else {
            Map<String, VectorDocument> dedup = new LinkedHashMap<>();
            for (RetrievalFuture rf : retrievalFutures) {
                try {
                    List<VectorDocument> results = rf.future.getNow(Collections.emptyList());
                    for (VectorDocument doc : results) {
                        if (doc != null && doc.getId() != null) {
                            dedup.putIfAbsent(doc.getId().toString(), doc);
                        }
                    }
                } catch (Exception e) {
                    // getNow 不抛异常，安全
                }
            }
            merged = new ArrayList<>(dedup.values());
            log.info("Hybrid retrieval (dedup): merged {} documents from {} sources (main+{}variants+keyword)",
                merged.size(), 1 + variantFutures.size(), variantFutures.size());
        }
        return merged;
    }

    /** 检索 future 与其 source 标识、RRF 权重 */
    private record RetrievalFuture(
        CompletableFuture<List<VectorDocument>> future,
        String source,
        double weight
    ) {}

    /** 带异常保护的语义检索（带 ACL + GraphRelease 过滤） */
    private List<VectorDocument> safeSemanticSearch(String projectId, String versionId,
                                                     String query, int topK,
                                                     String graphReleaseId, List<String> principals) {
        try {
            // principals 取首个非空主体做粗过滤（SQL LIKE 匹配），精确 ACL 校验由 EvidenceVerifier 完成
            String aclPrincipal = (principals != null && !principals.isEmpty())
                ? principals.stream().filter(p -> p != null && !p.isBlank()).findFirst().orElse(null)
                : null;
            return vectorRetrievalService.semanticSearch(
                projectId, versionId, query, topK, null, graphReleaseId, aclPrincipal);
        } catch (Exception e) {
            log.warn("Semantic search failed for '{}': {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 带异常保护的关键词检索（带 ACL + GraphRelease 过滤） */
    private List<VectorDocument> safeKeywordSearch(String projectId, String versionId,
                                                    String query, int topK,
                                                    String graphReleaseId, List<String> principals) {
        try {
            return keywordSearch(projectId, versionId, query, topK, graphReleaseId, principals);
        } catch (Exception e) {
            log.warn("Keyword search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 关键词检索（基于 PostgreSQL LIKE，带 ACL + GraphRelease 过滤）。
     *
     * <p>graphReleaseId 非空时按 {@code graph_release_id} 精确过滤；
     * principals 非空时仅返回 ACL 为空（无限制）或包含任一 principal 的文档。</p>
     */
    private List<VectorDocument> keywordSearch(String projectId, String versionId,
                                               String query, int topK,
                                               String graphReleaseId, List<String> principals) {
        // 提取关键词（简单分词）
        String[] keywords = query.split("[\\s,，。？！]+");

        LambdaQueryWrapper<VectorDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(VectorDocument::getProjectId, projectId);

        if (versionId != null && !versionId.isBlank()) {
            wrapper.eq(VectorDocument::getVersionId, versionId);
        }

        // GraphRelease 过滤：非空时仅查该 release 的节点
        if (graphReleaseId != null && !graphReleaseId.isBlank()) {
            wrapper.eq(VectorDocument::getGraphReleaseId, graphReleaseId);
        }

        // ACL 过滤：无 ACL 限制（NULL 或空）或包含 principals 中任一主体的文档
        if (principals != null && !principals.isEmpty()) {
            List<String> nonBlankPrincipals = principals.stream()
                .filter(p -> p != null && !p.isBlank())
                .toList();
            if (!nonBlankPrincipals.isEmpty()) {
                wrapper.and(w -> {
                    w.isNull(VectorDocument::getAclPrincipals)
                     .or().eq(VectorDocument::getAclPrincipals, "");
                    for (String p : nonBlankPrincipals) {
                        w.or().like(VectorDocument::getAclPrincipals, p);
                    }
                });
            }
        }

        // 添加关键词 OR 条件
        wrapper.and(w -> {
            for (int i = 0; i < keywords.length && i < 5; i++) {
                String keyword = keywords[i].trim();
                if (keyword.length() >= 2) {
                    if (i == 0) {
                        w.like(VectorDocument::getContent, keyword);
                    } else {
                        w.or().like(VectorDocument::getContent, keyword);
                    }
                }
            }
        });

        wrapper.last("LIMIT " + topK);

        return vectorDocumentRepository.selectList(wrapper);
    }

    /**
     * 解析 versionId：如果为空则查找项目最新版本。
     * <p>
     * 关键：lg_vector_document.version_id 存储为标准 UUID 格式（与 lg_scan_version 对齐），
     * 保持原始格式不变，确保与外键约束一致。
     */
    private String resolveVersionId(String projectId, String versionId) {
        if (versionId != null && !versionId.isBlank()) {
            return versionId;
        }
        try {
            String latestVersionId = vectorDocumentRepository.findLatestVersionId(projectId);
            if (latestVersionId != null) {
                log.debug("Resolved to latest versionId: {} for project: {}", latestVersionId, projectId);
                return latestVersionId;
            }
        } catch (Exception e) {
            log.warn("Failed to resolve latest versionId for project: {}", projectId, e);
        }
        return versionId;
    }
}
