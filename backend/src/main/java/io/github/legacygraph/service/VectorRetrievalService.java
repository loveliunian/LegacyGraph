package io.github.legacygraph.service;

import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.GraphNodeRepository;
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

    private final EmbeddingModel embeddingModel;
    private final VectorDocumentRepository vectorDocumentRepository;
    private final GraphNodeRepository graphNodeRepository;
    private final VectorizationService vectorizationService;

    public VectorRetrievalService(EmbeddingModel embeddingModel,
                               VectorDocumentRepository vectorDocumentRepository,
                               GraphNodeRepository graphNodeRepository,
                               VectorizationService vectorizationService) {
        this.embeddingModel = embeddingModel;
        this.vectorDocumentRepository = vectorDocumentRepository;
        this.graphNodeRepository = graphNodeRepository;
        this.vectorizationService = vectorizationService;
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
                    GraphNode node = graphNodeRepository.selectById(doc.getSourceUri());
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
