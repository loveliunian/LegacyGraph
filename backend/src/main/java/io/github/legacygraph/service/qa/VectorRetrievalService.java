package io.github.legacygraph.service.qa;

import io.github.legacygraph.entity.SemanticCacheEntry;
import io.github.legacygraph.repository.SemanticCacheRepository;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.VectorDocumentRepository;
import io.github.legacygraph.service.system.CacheService;
import io.github.legacygraph.util.VectorUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;


/**
 * 向量检索服务 - 使用 Spring AI + pgvector 实现语义相似度搜索
 */
@Slf4j
@Service
public class VectorRetrievalService {

    private final VectorDocumentRepository vectorDocumentRepository;
    private final Neo4jGraphDao neo4jGraphDao;
    private final VectorizationService vectorizationService;
    private final SemanticCacheRepository semanticCacheRepository;

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
                               VectorizationService vectorizationService,
                               SemanticCacheRepository semanticCacheRepository) {
        this.vectorDocumentRepository = vectorDocumentRepository;
        this.neo4jGraphDao = neo4jGraphDao;
        this.vectorizationService = vectorizationService;
        this.semanticCacheRepository = semanticCacheRepository;
    }

    private String searchCacheKey(String projectId, String versionId, String query, int topK, String chunkType) {
        String raw = projectId + "|" + versionId + "|" + topK + "|" + chunkType + "|" + query;
        return "vec:search:" + sha256(raw);
    }

    /**
     * L4 修复：SHA-256 在所有 JDK 上均为强制可用算法（JCA 规范要求），不存在 NoSuchAlgorithmException。
     * 移除 hashCode 降级路径 —— 其 32 位碰撞率会导致缓存键冲突，引发语义错乱。
     */
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
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 是 JCA 强制实现算法，此分支在合规 JVM 上不可达
            throw new IllegalStateException("SHA-256 algorithm unavailable — JVM does not comply with JCA spec", e);
        }
    }

    /**
     * 批量 upsert 向量（这里简化为单个插入，由调用方处理批量）
     */
    @Transactional
    public void batchUpsertVectors(String projectId, String versionId, List<VectorDocument> documents) {
        String effectiveVersionId = resolveVersionId(projectId, versionId);
        log.info("Batch upserting {} vectors for projectId={}, versionId={}", documents.size(), projectId, effectiveVersionId);
        for (VectorDocument doc : documents) {
            if (doc.getContent() != null && !doc.getContent().isBlank()) {
                vectorizationService.embedAndStore(
                    doc.getProjectId() != null ? doc.getProjectId() : projectId,
                    doc.getVersionId() != null ? doc.getVersionId() : effectiveVersionId,
                    doc.getChunkType(),
                    doc.getSourceUri(),
                    doc.getChunkIndex() != null ? doc.getChunkIndex() : 0,
                    doc.getContent(),
                    doc.getEmbeddingModel() != null ? doc.getEmbeddingModel() : "bge-m3"
                );
            }
        }
    }

    /**
     * 语义搜索 - 对查询向量化，返回相似度最高的 topK 文档
     */
    public List<VectorDocument> semanticSearch(String projectId, String versionId, String query, int topK, String chunkType) {
        log.info("Semantic search: projectId={}, query={}, topK={}", projectId, query, topK);
        String effectiveVersionId = resolveVersionId(projectId, versionId);

        // 缓存优先：相同 (project, version, query, topK, chunkType) 复用
        if (cacheService != null) {
            String cacheKey = searchCacheKey(projectId, effectiveVersionId, query, topK, chunkType);
            @SuppressWarnings("unchecked")
            List<VectorDocument> cached = cacheService.get(cacheKey, List.class);
            if (cached != null) {
                return cached;
            }
            List<VectorDocument> fresh = doSemanticSearch(projectId, effectiveVersionId, query, topK, chunkType);
            cacheService.put(cacheKey, fresh, SEARCH_CACHE_TTL);
            return fresh;
        }
        return doSemanticSearch(projectId, effectiveVersionId, query, topK, chunkType);
    }

    private List<VectorDocument> doSemanticSearch(String projectId, String versionId, String query, int topK, String chunkType) {
        if (embeddingModel == null) {
            log.debug("EmbeddingModel not available (SILICONFLOW_API_KEY not set)");
            return Collections.emptyList();
        }
        try {
            // 对查询进行向量化 - Spring AI 1.0+ API
            float[] embedding = embeddingModel.embed(query);
            List<Double> queryEmbedding = VectorUtils.floatArrayToDoubleList(embedding);

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
        String effectiveVersionId = resolveVersionId(projectId, versionId);

        if (embeddingModel == null) {
            log.debug("EmbeddingModel not available (SILICONFLOW_API_KEY not set)");
            return Collections.emptyList();
        }
        try {
            // 对查询进行向量化
            float[] embedding = embeddingModel.embed(searchText);
            List<Double> queryEmbedding = VectorUtils.floatArrayToDoubleList(embedding);

            // 从向量文档中检索相似的语义片段，然后映射到节点
            List<VectorDocument> similarDocs = vectorDocumentRepository.findSimilar(
                projectId, effectiveVersionId, queryEmbedding, 20, null
            );

            // 从匹配的文档中提取关联的节点：收集所有 sourceUri，批量查询 Neo4j
            List<String> nodeIds = similarDocs.stream()
                    .map(VectorDocument::getSourceUri)
                    .filter(uri -> uri != null && !uri.isBlank())
                    .distinct()
                    .toList();
            Map<String, GraphNode> nodeMap = new HashMap<>();
            if (!nodeIds.isEmpty()) {
                List<GraphNode> nodes = neo4jGraphDao.findNodesByIds(nodeIds);
                for (GraphNode n : nodes) {
                    nodeMap.put(n.getId(), n);
                }
            }
            List<GraphNode> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (VectorDocument doc : similarDocs) {
                if (doc.getSourceUri() != null) {
                    GraphNode node = nodeMap.get(doc.getSourceUri());
                    if (node != null && seen.add(node.getId())) {
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
    /**
     * 计算文本的 embedding 向量。
     * L5 修复：失败/不可用时明确返回 null（而非空数组），调用方按 Optional/Optional.empty 语义处理。
     * 返回 null 比 new float[0] 语义更清晰：避免下游误判为"有效但空"的向量。
     */
    public float[] computeEmbedding(String text) {
        if (embeddingModel == null) {
            log.debug("EmbeddingModel not available (SILICONFLOW_API_KEY not set)");
            return null;
        }
        try {
            return embeddingModel.embed(text);
        } catch (Exception e) {
            log.error("Failed to compute embedding: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 查找相似的语义缓存条目
     */
    public Optional<SemanticCacheEntry> findSimilarCache(String projectId, float[] queryEmbedding, double threshold) {
        if (embeddingModel == null || queryEmbedding == null || queryEmbedding.length == 0) {
            return Optional.empty();
        }
        try {
            String embeddingStr = VectorUtils.floatArrayToVectorLiteral(queryEmbedding);
            // 从语义缓存表中查找相似条目
            List<SemanticCacheEntry> candidates = semanticCacheRepository.findSimilar(
                projectId, embeddingStr, 1, threshold
            );
            return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
        } catch (Exception e) {
            log.error("Find similar cache failed: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 解析 versionId：为空时自动查找该项目最新的 version_id。
     * <p>
     * 关键：lg_vector_document.version_id 统一存储为无连字符格式（与 Neo4j 对齐），
     * 而 QA 检索入口传入的是标准 UUID（带连字符）。此处统一规范化为无连字符，
     * 否则向量/关键词检索因格式不匹配恒返回 0 条（与项目 GraphQueryService 等一致）。
     */
    private String resolveVersionId(String projectId, String versionId) {
        if (versionId != null && !versionId.isBlank()) return versionId.replace("-", "");
        try {
            String latest = vectorDocumentRepository.findLatestVersionId(projectId);
            if (latest != null) {
                log.debug("Auto-resolved versionId for project {}: {}", projectId, latest);
                // findLatestVersionId 返回的已是 DB 无连字符格式，仍规范化以防万一
                return latest.replace("-", "");
            }
        } catch (Exception e) {
            log.warn("Failed to resolve latest versionId for project {}: {}", projectId, e.getMessage());
        }
        return "default";
    }
}
