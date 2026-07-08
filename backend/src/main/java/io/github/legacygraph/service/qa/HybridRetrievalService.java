package io.github.legacygraph.service.qa;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.DocChunk;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.DocChunkRepository;
import io.github.legacygraph.repository.VectorDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /** 召回专用虚拟线程执行器 */
    private final ExecutorService retrievalExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 多路召回 fan-out/fan-in：向量检索（主查询 + 变体）+ 关键词检索 全部并发执行。
     *
     * <p>原串行耗时 = T_main + ΣT_variants + T_keyword，
     * 优化后 ≈ max(T_main, T_variants..., T_keyword)，
     * 召回阶段延迟预期降低 30-50%。</p>
     */
    public List<VectorDocument> retrieve(String projectId, String versionId,
                                         String query, List<String> queryVariants,
                                         int topK) {
        // 统一解析 versionId，确保所有检索路径使用相同的 versionId
        String effectiveVersionId = resolveVersionId(projectId, versionId);

        // 1. 主查询向量检索（异步）
        CompletableFuture<List<VectorDocument>> mainFuture = CompletableFuture.supplyAsync(
            () -> safeSemanticSearch(projectId, effectiveVersionId, query, topK),
            retrievalExecutor
        );

        // 2. 查询变体向量检索（每个变体异步）
        List<CompletableFuture<List<VectorDocument>>> variantFutures = new ArrayList<>();
        if (queryVariants != null) {
            for (String variant : queryVariants) {
                if (variant != null && !variant.isBlank()) {
                    variantFutures.add(CompletableFuture.supplyAsync(
                        () -> safeSemanticSearch(projectId, effectiveVersionId, variant, topK / 2),
                        retrievalExecutor
                    ));
                }
            }
        }

        // 3. 关键词检索（异步）
        CompletableFuture<List<VectorDocument>> keywordFuture = CompletableFuture.supplyAsync(
            () -> safeKeywordSearch(projectId, effectiveVersionId, query, topK / 2),
            retrievalExecutor
        );

        // 收集所有 future
        List<CompletableFuture<List<VectorDocument>>> allFutures = new ArrayList<>();
        allFutures.add(mainFuture);
        allFutures.addAll(variantFutures);
        allFutures.add(keywordFuture);

        // 等待全部完成（含超时保护）
        CompletableFuture.allOf(allFutures.toArray(CompletableFuture[]::new))
            .orTimeout(5, TimeUnit.SECONDS)
            .exceptionally(ex -> { log.warn("Retrieval timeout or error: {}", ex.getMessage()); return null; })
            .join();

        // 合并结果，保持插入顺序（主查询优先）
        Map<String, VectorDocument> merged = new LinkedHashMap<>();
        for (CompletableFuture<List<VectorDocument>> future : allFutures) {
            try {
                List<VectorDocument> results = future.getNow(Collections.emptyList());
                for (VectorDocument doc : results) {
                    if (doc != null && doc.getId() != null) {
                        merged.putIfAbsent(doc.getId().toString(), doc);
                    }
                }
            } catch (Exception e) {
                // getNow 不抛异常，安全
            }
        }

        log.info("Hybrid retrieval: merged {} documents from {} sources (main+{}variants+keyword)",
            merged.size(), 1 + variantFutures.size());
        return new ArrayList<>(merged.values());
    }

    /** 带异常保护的语义检索 */
    private List<VectorDocument> safeSemanticSearch(String projectId, String versionId,
                                                     String query, int topK) {
        try {
            return vectorRetrievalService.semanticSearch(projectId, versionId, query, topK, null);
        } catch (Exception e) {
            log.warn("Semantic search failed for '{}': {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 带异常保护的关键词检索 */
    private List<VectorDocument> safeKeywordSearch(String projectId, String versionId,
                                                    String query, int topK) {
        try {
            return keywordSearch(projectId, versionId, query, topK);
        } catch (Exception e) {
            log.warn("Keyword search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 关键词检索（基于 PostgreSQL LIKE）
     */
    private List<VectorDocument> keywordSearch(String projectId, String versionId, 
                                               String query, int topK) {
        // 提取关键词（简单分词）
        String[] keywords = query.split("[\\s,，。？！]+");
        
        LambdaQueryWrapper<VectorDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(VectorDocument::getProjectId, projectId);
        
        if (versionId != null && !versionId.isBlank()) {
            wrapper.eq(VectorDocument::getVersionId, versionId);
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
     * 关键：lg_vector_document.version_id 存储为无连字符格式（与 Neo4j 对齐），
     * 传入的标准 UUID（带连字符）需规范化为无连字符，否则检索恒返回 0 条。
     */
    private String resolveVersionId(String projectId, String versionId) {
        if (versionId != null && !versionId.isBlank()) {
            return versionId.replace("-", "");
        }
        try {
            String latestVersionId = vectorDocumentRepository.findLatestVersionId(projectId);
            if (latestVersionId != null) {
                log.debug("Resolved to latest versionId: {} for project: {}", latestVersionId, projectId);
                return latestVersionId.replace("-", "");
            }
        } catch (Exception e) {
            log.warn("Failed to resolve latest versionId for project: {}", projectId, e);
        }
        return versionId != null ? versionId.replace("-", "") : null;
    }
}
