package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.VectorDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量检索服务 - 使用 Spring AI + pgvector 实现语义相似度搜索
 */
@Slf4j
@Service
public class VectorRetrievalService {

    private final VectorDocumentRepository vectorDocumentRepository;
    private final Neo4jGraphDao neo4jGraphDao;
    private final VectorizationService vectorizationService;

    /** EmbeddingModel 可选：设置 SILICONFLOW_API_KEY 环境变量后可用 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EmbeddingModel embeddingModel;

    /** 语义检索结果缓存（可选） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CacheService cacheService;

    /** 语义检索缓存 TTL */
    private static final java.time.Duration SEARCH_CACHE_TTL = java.time.Duration.ofMinutes(3);

    public VectorRetrievalService(VectorDocumentRepository vectorDocumentRepository,
                               Neo4jGraphDao neo4jGraphDao,
                               VectorizationService vectorizationService) {
        this.vectorDocumentRepository = vectorDocumentRepository;
        this.neo4jGraphDao = neo4jGraphDao;
        this.vectorizationService = vectorizationService;
    }

    private String searchCacheKey(String projectId, String versionId, String query, int topK, String chunkType) {
        String raw = projectId + "|" + versionId + "|" + topK + "|" + chunkType + "|" + query;
        return "vec:search:" + sha256(raw);
    }

    private String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * 批量 upsert 向量（这里简化为单个插入，由调用方处理批量）
     */
    @Transactional
    public void batchUpsertVectors(String projectId, List<VectorDocument> documents) {
        log.info("Batch upserting {} vectors for projectId={}", documents.size(), projectId);
        for (VectorDocument doc : documents) {
            if (doc.getContent() != null && !doc.getContent().isBlank()) {
                Long projectIdLong = doc.getProjectId();
                vectorizationService.embedAndStore(
                    projectIdLong,
                    null,
                    doc.getChunkType(),
                    doc.getSourceUri(),
                    doc.getChunkIndex() != null ? doc.getChunkIndex() : 0,
                    doc.getContent(),
                    doc.getEmbeddingModel() != null ? doc.getEmbeddingModel() : "text-embedding-3-small"
                );
            }
        }
    }

    /**
     * 语义搜索 - 对查询向量化，返回相似度最高的 topK 文档
     */
    public List<VectorDocument> semanticSearch(String projectId, String versionId, String query, int topK, String chunkType) {
        log.info("Semantic search: projectId={}, query={}, topK={}", projectId, query, topK);

        // 缓存优先：相同 (project, version, query, topK, chunkType) 复用
        if (cacheService != null) {
            String cacheKey = searchCacheKey(projectId, versionId, query, topK, chunkType);
            @SuppressWarnings("unchecked")
            List<VectorDocument> cached = cacheService.get(cacheKey, List.class);
            if (cached != null) {
                return cached;
            }
            List<VectorDocument> fresh = doSemanticSearch(projectId, versionId, query, topK, chunkType);
            cacheService.put(cacheKey, fresh, SEARCH_CACHE_TTL);
            return fresh;
        }
        return doSemanticSearch(projectId, versionId, query, topK, chunkType);
    }

    private List<VectorDocument> doSemanticSearch(String projectId, String versionId, String query, int topK, String chunkType) {
        if (embeddingModel == null) {
            log.debug("EmbeddingModel not available (SILICONFLOW_API_KEY not set)");
            return Collections.emptyList();
        }
        try {
            // 对查询进行向量化 - Spring AI 1.0+ API
            float[] embedding = embeddingModel.embed(query);
            List<Double> queryEmbedding = floatArrayToDoubleList(embedding);

            // 使用 pgvector 余弦相似度检索
            return vectorDocumentRepository.findSimilar(
                projectId, versionId, queryEmbedding, topK, chunkType
            );
        } catch (Exception e) {
            log.error("Semantic search failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 查找相似节点 - 根据节点名称/描述的向量表示，查找相似节点
     */
    public List<GraphNode> findSimilarNodes(String projectId, String versionId, String searchText, double similarityThreshold) {
        log.info("Find similar nodes: projectId={}, searchText={}, threshold={}", projectId, searchText, similarityThreshold);

        if (embeddingModel == null) {
            log.debug("EmbeddingModel not available (SILICONFLOW_API_KEY not set)");
            return Collections.emptyList();
        }
        try {
            // 对查询进行向量化
            float[] embedding = embeddingModel.embed(searchText);
            List<Double> queryEmbedding = floatArrayToDoubleList(embedding);

            // 从向量文档中检索相似的语义片段，然后映射到节点
            List<VectorDocument> similarDocs = vectorDocumentRepository.findSimilar(
                projectId, versionId, queryEmbedding, 20, null
            );

            // 从匹配的文档中提取关联的节点
            List<GraphNode> result = new ArrayList<>();
            for (VectorDocument doc : similarDocs) {
                if (doc.getSourceUri() != null) {
                    // sourceUri 格式可能是节点ID或文件路径
                    GraphNode node = neo4jGraphDao.findNodeById(doc.getSourceUri()).orElse(null);
                    if (node != null && !result.contains(node)) {
                        result.add(node);
                    }
                }
            }

            log.info("Found {} similar nodes for '{}'", result.size(), searchText);
            return result;
        } catch (Exception e) {
            log.error("Find similar nodes failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Convert float array to List<Double>
     */
    private List<Double> floatArrayToDoubleList(float[] floats) {
        List<Double> result = new ArrayList<>(floats.length);
        for (float f : floats) {
            result.add((double) f);
        }
        return result;
    }
}
