package io.github.legacygraph.service;

import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.repository.VectorDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量检索服务 - 语义相似度检索、相似节点发现
 *
 * 功能：
 * - 批量向量化并存储文档片段
 * - 语义相似度检索召回相关证据
 * - 根据向量相似度查找可能重复的图谱节点
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorRetrievalService {

    private final EmbeddingModel embeddingModel;
    private final VectorDocumentRepository vectorDocumentRepository;
    private final GraphNodeRepository graphNodeRepository;
    private final VectorizationService vectorizationService;

    /**
     * 批量向量化文档/代码片段并存储
     */
    public void batchUpsertVectors(Long projectId, List<VectorDocument> docs) {
        for (VectorDocument doc : docs) {
            if (doc.getContent() != null && !doc.getContent().isBlank()) {
                vectorizationService.embedAndStore(
                    projectId,
                    doc.getChunkType(),
                    doc.getSourceUri(),
                    doc.getChunkIndex() != null ? doc.getChunkIndex() : 0,
                    doc.getContent(),
                    doc.getEmbeddingModel() != null ? doc.getEmbeddingModel() : "text-embedding-3-small",
                    doc.getEmbeddingDim() != null ? doc.getEmbeddingDim() : 1536
                );
            }
        }
        log.info("Batch upserted {} vectors for project {}", docs.size(), projectId);
    }

    /**
     * 语义相似度检索 - 召回与查询语义相关的文档片段
     * @param projectId 项目ID
     * @param query 查询文本
     * @param topK 返回最大数量
     * @param chunkType 过滤分片类型（可为null）
     * @return 相似度降序排列的文档列表（distance越小越相似）
     */
    public List<VectorDocument> semanticSearch(Long projectId, String query, int topK, String chunkType) {
        // 对查询进行向量化
        float[] embedding = embeddingModel.embed(query);
        List<Double> queryEmbedding = floatArrayToDoubleList(embedding);

        // 使用 pgvector 余弦相似度检索
        return vectorDocumentRepository.findSimilarByEmbedding(
            projectId, queryEmbedding, topK, chunkType
        );
    }

    /**
     * 根据相似度查找可能重复的节点
     * @param projectId 项目ID
     * @param nodeName 节点名称
     * @param threshold 相似度阈值（0.0-1.0），推荐 0.85
     * @return 相似节点列表
     */
    public List<GraphNode> findSimilarNodes(String projectId, String nodeName, double threshold) {
        // 对节点名称向量化
        float[] embedding = embeddingModel.embed(nodeName);
        List<Double> queryEmbedding = floatArrayToDoubleList(embedding);

        // 查找相似节点 - 这里我们假设节点名称已经向量化
        // 实际实现需要节点向量存储，这里简化处理
        List<GraphNode> allNodes = graphNodeRepository.findByProjectId(projectId);
        List<GraphNode> similarNodes = new ArrayList<>();

        // 注意：完整实现应该从向量存储检索，这里是占位实现
        // 实际生产环境需要为每个节点预计算并存储向量

        for (GraphNode node : allNodes) {
            if (node.getNodeName() != null && calculateNameSimilarity(nodeName, node.getNodeName()) >= threshold) {
                similarNodes.add(node);
            }
        }

        // 移除原节点本身
        similarNodes.removeIf(n -> n.getNodeName().equalsIgnoreCase(nodeName));

        log.info("Found {} similar nodes for '{}' in project {} with threshold {}",
            similarNodes.size(), nodeName, projectId, threshold);

        return similarNodes;
    }

    /**
     * 计算名称相似度（辅助方法，补充向量相似度）
     */
    private double calculateNameSimilarity(String name1, String name2) {
        List<String> tokens1 = tokenize(name1);
        List<String> tokens2 = tokenize(name2);

        int intersection = 0;
        for (String t1 : tokens1) {
            for (String t2 : tokens2) {
                if (t1.equalsIgnoreCase(t2)) {
                    intersection++;
                    break;
                }
            }
        }

        int union = tokens1.size() + tokens2.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private List<String> tokenize(String s) {
        List<String> tokens = new ArrayList<>();
        String[] parts = s.split("[_\\s-]+");
        for (String part : parts) {
            splitCamelCase(part, tokens);
        }
        return tokens.stream()
            .filter(t -> t.length() >= 2)
            .map(String::toLowerCase)
            .toList();
    }

    private void splitCamelCase(String s, List<String> tokens) {
        int start = 0;
        for (int i = 1; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) {
                tokens.add(s.substring(start, i));
                start = i;
            }
        }
        if (start < s.length()) {
            tokens.add(s.substring(start));
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
