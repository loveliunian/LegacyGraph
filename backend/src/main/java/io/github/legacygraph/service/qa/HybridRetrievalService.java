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

    /**
     * 多路召回：向量检索 + 关键词检索
     */
    public List<VectorDocument> retrieve(String projectId, String versionId, 
                                         String query, List<String> queryVariants,
                                         int topK) {
        // 统一解析 versionId，确保所有检索路径使用相同的 versionId
        String effectiveVersionId = resolveVersionId(projectId, versionId);
        Map<String, VectorDocument> merged = new LinkedHashMap<>();

        // 1. 向量检索（主查询）
        try {
            List<VectorDocument> vectorResults = vectorRetrievalService.semanticSearch(
                projectId, effectiveVersionId, query, topK, null
            );
            for (VectorDocument doc : vectorResults) {
                merged.putIfAbsent(doc.getId().toString(), doc);
            }
        } catch (Exception e) {
            log.warn("Vector retrieval failed: {}", e.getMessage());
        }

        // 2. 向量检索（查询变体）
        if (queryVariants != null) {
            for (String variant : queryVariants) {
                try {
                    List<VectorDocument> variantResults = vectorRetrievalService.semanticSearch(
                        projectId, effectiveVersionId, variant, topK / 2, null
                    );
                    for (VectorDocument doc : variantResults) {
                        merged.putIfAbsent(doc.getId().toString(), doc);
                    }
                } catch (Exception e) {
                    log.warn("Variant retrieval failed: {}", e.getMessage());
                }
            }
        }

        // 3. 关键词检索（补充向量检索遗漏）
        try {
            List<VectorDocument> keywordResults = keywordSearch(projectId, effectiveVersionId, query, topK / 2);
            for (VectorDocument doc : keywordResults) {
                merged.putIfAbsent(doc.getId().toString(), doc);
            }
        } catch (Exception e) {
            log.warn("Keyword retrieval failed: {}", e.getMessage());
        }

        log.info("Hybrid retrieval: merged {} documents from multiple sources", merged.size());
        return new ArrayList<>(merged.values());
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
