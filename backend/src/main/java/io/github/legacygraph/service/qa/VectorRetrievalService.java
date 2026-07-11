package io.github.legacygraph.service.qa;

import io.github.legacygraph.entity.SemanticCacheEntry;
import io.github.legacygraph.repository.SemanticCacheRepository;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.VectorDocumentRepository;
import io.github.legacygraph.service.system.CacheService;
import io.github.legacygraph.util.IdUtil;
import io.github.legacygraph.util.VectorUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * Task 8.3 Feature Flag：GraphRelease + ACL 过滤开关。
     * 关闭时（默认）走原 findSimilar 不加任何过滤，保持向后兼容；
     * 开启时按 graphReleaseId / aclPrincipal 过滤。
     */
    @Value("${legacygraph.qa.graph-release-filter.enabled:false}")
    private boolean graphReleaseFilterEnabled;

    public VectorRetrievalService(VectorDocumentRepository vectorDocumentRepository,
                               Neo4jGraphDao neo4jGraphDao,
                               VectorizationService vectorizationService,
                               SemanticCacheRepository semanticCacheRepository) {
        this.vectorDocumentRepository = vectorDocumentRepository;
        this.neo4jGraphDao = neo4jGraphDao;
        this.vectorizationService = vectorizationService;
        this.semanticCacheRepository = semanticCacheRepository;
    }

    /** 当前向量语料版本（向量重建时递增，用于主动失效旧缓存） */
    private volatile String corpusVersion = "v1";

    /**
     * 更新语料版本并触发缓存失效。
     * 在 batchUpsertVectors 完成后调用，确保新向量不被旧缓存遮蔽。
     */
    public void bumpCorpusVersion(String projectId, String versionId) {
        this.corpusVersion = "v" + System.currentTimeMillis();
        if (cacheService != null) {
            cacheService.evictByPrefix("vec:search:" + projectId + ":" + versionId + ":");
            log.info("Vector corpus version bumped to {}, cache evicted for project={} version={}",
                    corpusVersion, projectId, versionId);
        }
    }

    private String searchCacheKey(String projectId, String versionId, String query, int topK, String chunkType) {
        return searchCacheKey(projectId, versionId, query, topK, chunkType, null, null);
    }

    /**
     * Task 8.3：缓存键扩展过滤维度，避免不同 graphReleaseId/aclPrincipal 的查询共享缓存。
     * 当过滤参数为 null 时退化为原缓存键（向后兼容）。
     */
    private String searchCacheKey(String projectId, String versionId, String query, int topK, String chunkType,
                                   String graphReleaseId, String aclPrincipal) {
        String raw = String.join("|",
            projectId,
            versionId,
            embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "default",
            corpusVersion,
            String.valueOf(topK),
            chunkType != null ? chunkType : "",
            query,
            graphReleaseId != null ? graphReleaseId : "",
            aclPrincipal != null ? aclPrincipal : ""
        );
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
        // 存储一律用 effectiveVersionId（去连字符，与检索/lg_vector_document.version_id 对齐）。
        // 此前用 doc.getVersionId() 会把带连字符的原始值写入，导致检索（resolveVersionId 去连字符后）查不到，
        // SYSTEM_OVERVIEW 向量虽写入但永不被召回。
        for (VectorDocument doc : documents) {
            if (doc.getContent() != null && !doc.getContent().isBlank()) {
                vectorizationService.embedAndStore(
                    doc.getProjectId() != null ? doc.getProjectId() : projectId,
                    effectiveVersionId,
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
        return semanticSearch(projectId, versionId, query, topK, chunkType, null, null);
    }

    /**
     * 语义搜索（带 GraphRelease + ACL 过滤，Task 8.3）。
     * <p>当 {@code legacygraph.qa.graph-release-filter.enabled=false}（默认）时，
     * graphReleaseId / aclPrincipal 参数被忽略，退化为原行为。</p>
     *
     * @param graphReleaseId 图谱发布ID，null/空 时忽略；feature flag 关闭时也忽略
     * @param aclPrincipal   ACL 主体，null/空 时忽略；feature flag 关闭时也忽略
     */
    public List<VectorDocument> semanticSearch(String projectId, String versionId, String query, int topK,
                                              String chunkType, String graphReleaseId, String aclPrincipal) {
        log.debug("Semantic search: projectId={}, query={}, topK={}, graphReleaseFilter={}, graphReleaseId={}, aclPrincipal={}",
            projectId, query, topK, graphReleaseFilterEnabled, graphReleaseId, aclPrincipal);
        String effectiveVersionId = resolveVersionId(projectId, versionId);

        // feature flag 关闭时不参与缓存键计算，保持与原缓存键兼容
        boolean effectiveFilter = graphReleaseFilterEnabled;
        String effectiveGraphReleaseId = effectiveFilter ? graphReleaseId : null;
        String effectiveAclPrincipal = effectiveFilter ? aclPrincipal : null;

        // 缓存优先：相同 (project, version, query, topK, chunkType, filter维度) 复用
        if (cacheService != null) {
            String cacheKey = searchCacheKey(projectId, effectiveVersionId, query, topK, chunkType,
                effectiveGraphReleaseId, effectiveAclPrincipal);
            @SuppressWarnings("unchecked")
            List<VectorDocument> cached = cacheService.get(cacheKey, List.class);
            if (cached != null) {
                return cached;
            }
            List<VectorDocument> fresh = doSemanticSearch(projectId, effectiveVersionId, query, topK, chunkType,
                effectiveGraphReleaseId, effectiveAclPrincipal);
            cacheService.put(cacheKey, fresh, SEARCH_CACHE_TTL);
            return fresh;
        }
        return doSemanticSearch(projectId, effectiveVersionId, query, topK, chunkType,
            effectiveGraphReleaseId, effectiveAclPrincipal);
    }

    private List<VectorDocument> doSemanticSearch(String projectId, String versionId, String query, int topK,
                                                  String chunkType, String graphReleaseId, String aclPrincipal) {
        if (embeddingModel == null) {
            log.debug("EmbeddingModel not available (SILICONFLOW_API_KEY not set)");
            return Collections.emptyList();
        }
        try {
            // 对查询进行向量化 - Spring AI 1.0+ API
            float[] embedding = embeddingModel.embed(query);
            List<Double> queryEmbedding = VectorUtils.floatArrayToDoubleList(embedding);

            // feature flag 开启且任一过滤参数非空时走带过滤查询；否则走原查询保持兼容
            boolean useFilter = graphReleaseFilterEnabled
                && (isNonBlank(graphReleaseId) || isNonBlank(aclPrincipal));
            if (useFilter) {
                return vectorDocumentRepository.findSimilarWithFilters(
                    projectId, versionId, queryEmbedding, topK, chunkType, graphReleaseId, aclPrincipal
                );
            }
            return vectorDocumentRepository.findSimilar(
                projectId, versionId, queryEmbedding, topK, chunkType
            );
        } catch (Exception e) {
            log.error("Semantic search failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 查找相似节点 - 根据节点名称/描述的向量表示，查找相似节点
     */
    public List<GraphNode> findSimilarNodes(String projectId, String versionId, String searchText, double similarityThreshold) {
        log.debug("Find similar nodes: projectId={}, searchText={}, threshold={}", projectId, searchText, similarityThreshold);
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

            log.debug("Found {} similar nodes for '{}'", result.size(), searchText);
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
     * 版本化查找相似的语义缓存条目 — 按 graphReleaseId 和 aclHash 过滤。
     *
     * @param projectId      项目 ID
     * @param queryEmbedding 查询 embedding
     * @param threshold      相似度阈值
     * @param graphReleaseId 图谱发布 ID（null 匹配 NULL 列）
     * @param aclHash        ACL 哈希（null 匹配 NULL 列）
     * @return 匹配的缓存条目，无匹配时返回 empty
     */
    public Optional<SemanticCacheEntry> findSimilarCacheVersioned(String projectId, float[] queryEmbedding,
                                                                    double threshold, String graphReleaseId,
                                                                    String aclHash) {
        if (embeddingModel == null || queryEmbedding == null || queryEmbedding.length == 0) {
            return Optional.empty();
        }
        try {
            String embeddingStr = VectorUtils.floatArrayToVectorLiteral(queryEmbedding);
            List<SemanticCacheEntry> candidates = semanticCacheRepository.findSimilarVersioned(
                projectId, embeddingStr, 1, threshold, graphReleaseId, aclHash
            );
            return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
        } catch (Exception e) {
            log.error("Find similar versioned cache failed: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 解析 versionId：为空时自动查找该项目最新的 version_id。
     * <p>
     * 关键：lg_vector_document.version_id 统一存储为去连字符格式（与检索对齐），
     * 确保 storage 和 retrieval 使用相同的 versionId 格式。
     */
    private String resolveVersionId(String projectId, String versionId) {
        if (versionId != null && !versionId.isBlank()) {
            return IdUtil.normalizeId(versionId);
        }
        try {
            String latest = vectorDocumentRepository.findLatestVersionId(projectId);
            if (latest != null) {
                log.debug("Auto-resolved versionId for project {}: {}", projectId, latest);
                return IdUtil.normalizeId(latest);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve latest versionId for project {}: {}", projectId, e.getMessage());
        }
        return "default";
    }
}
