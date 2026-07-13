package io.github.legacygraph.service.similarity;

import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 语义相似度计算器（graph-merge-optimization-plan.md 改进③）。
 * <p>
 * 使用 Spring AI {@link EmbeddingModel} 计算两个图谱节点的语义相似度（余弦相似度），
 * 填补原 5 维评分中缺失的"语义相似度"维度。
 * </p>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>节点文本表示：name + displayName + description + aliasNames，最大化语义信号</li>
 *   <li>EmbeddingModel 可选：未配置 SILICONFLOW_API_KEY 时降级返回 0.0，不阻塞合并流程</li>
 *   <li>结果缓存：按 (nodeType, name1, name2) 缓存，避免重复 embedding 调用</li>
 *   <li>批量友好的单次调用：每次只 embed 2 个文本（节点对比粒度）</li>
 * </ul>
 */
@Slf4j
@Service
public class SemanticSimilarityCalculator {

    /** EmbeddingModel 可选：设置 SILICONFLOW_API_KEY 环境变量后可用 */
    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    /** 语义相似度缓存：key = "nodeType|name1|name2"（排序后），value = 相似度 */
    private final ConcurrentHashMap<String, Double> similarityCache = new ConcurrentHashMap<>();

    /** 缓存上限：超过后清空（简单淘汰策略，避免内存膨胀） */
    private static final int CACHE_MAX_SIZE = 5000;

    /**
     * 计算两个节点的语义相似度（0-1）。
     * <p>
     * 文本表示：name + displayName + description + aliasNames。
     * 当 EmbeddingModel 不可用时返回 0.0（降级，不阻塞合并流程）。
     * </p>
     *
     * @param nodeA 节点 A
     * @param nodeB 节点 B
     * @return 余弦相似度 [0, 1]，不可用时返回 0.0
     */
    public double compute(GraphNode nodeA, GraphNode nodeB) {
        if (nodeA == null || nodeB == null) {
            return 0.0;
        }
        if (embeddingModel == null) {
            log.debug("EmbeddingModel not available, semantic score degraded to 0.0");
            return 0.0;
        }

        String nameA = nodeA.getNodeName() != null ? nodeA.getNodeName() : "";
        String nameB = nodeB.getNodeName() != null ? nodeB.getNodeName() : "";
        String nodeType = nodeA.getNodeType() != null ? nodeA.getNodeType() : "default";

        // 缓存命中检查
        String cacheKey = cacheKey(nodeType, nameA, nameB);
        Double cached = similarityCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 构建节点文本表示
        String textA = buildNodeText(nodeA);
        String textB = buildNodeText(nodeB);
        if (textA.isBlank() || textB.isBlank()) {
            return 0.0;
        }

        try {
            float[] embeddingA = embeddingModel.embed(textA);
            float[] embeddingB = embeddingModel.embed(textB);
            double similarity = cosineSimilarity(embeddingA, embeddingB);

            // 归一化到 [0, 1]（余弦相似度范围为 [-1, 1]）
            double normalized = (similarity + 1.0) / 2.0;

            // 写入缓存
            putCache(cacheKey, normalized);
            return normalized;
        } catch (Exception e) {
            log.warn("Embedding computation failed for ({}, {}): {}", nameA, nameB, e.getMessage());
            return 0.0;
        }
    }

    /**
     * 构建节点的文本表示（用于 embedding）。
     * <p>组合 name + displayName + description + aliasNames，最大化语义信号。</p>
     */
    public String buildNodeText(GraphNode node) {
        StringBuilder sb = new StringBuilder();
        if (node.getNodeName() != null && !node.getNodeName().isBlank()) {
            sb.append(node.getNodeName());
        }
        if (node.getDisplayName() != null && !node.getDisplayName().isBlank()
                && !node.getDisplayName().equals(node.getNodeName())) {
            sb.append(" ").append(node.getDisplayName());
        }
        if (node.getDescription() != null && !node.getDescription().isBlank()) {
            sb.append(" ").append(node.getDescription());
        }
        if (node.getAliasNames() != null && !node.getAliasNames().isBlank()) {
            // aliasNames 是 JSON 数组字符串，直接附加（embedding 能处理）
            sb.append(" ").append(node.getAliasNames());
        }
        return sb.toString().trim();
    }

    /**
     * 计算两个向量的余弦相似度。
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 余弦相似度 [-1, 1]，维度不匹配或零向量时返回 0.0
     */
    public double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 生成缓存 key（name 排序确保对称性） */
    private String cacheKey(String nodeType, String nameA, String nameB) {
        return nameA.compareTo(nameB) <= 0
                ? nodeType + "|" + nameA + "|" + nameB
                : nodeType + "|" + nameB + "|" + nameA;
    }

    /** 写入缓存（带简单淘汰） */
    private void putCache(String key, double value) {
        if (similarityCache.size() >= CACHE_MAX_SIZE) {
            log.info("Semantic similarity cache full ({}), clearing", CACHE_MAX_SIZE);
            similarityCache.clear();
        }
        similarityCache.put(key, value);
    }

    /**
     * 获取缓存大小（供监控/测试用）。
     */
    public int getCacheSize() {
        return similarityCache.size();
    }
}
