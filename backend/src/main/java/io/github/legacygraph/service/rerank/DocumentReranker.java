package io.github.legacygraph.service.rerank;

import io.github.legacygraph.entity.VectorDocument;

import java.util.List;

/**
 * 文档重排序接口 — 可插拔实现。
 * <p>
 * 实现示例：
 * <ul>
 *   <li>{@link KeywordReranker} — 轻量关键词/TF 风格，无外部依赖</li>
 *   <li>{@link CrossEncoderReranker} — 调用 bge-reranker / cross-encoder 服务</li>
 * </ul>
 */
public interface DocumentReranker {

    /**
     * 对候选文档按与 query 的相关性重排序，返回 Top-K。
     *
     * @param query      用户查询
     * @param candidates 候选文档（已召回）
     * @param topK       返回数量上限
     * @return 重排序后的文档列表（最多 topK 条）
     */
    List<VectorDocument> rerank(String query, List<VectorDocument> candidates, int topK);

    /**
     * 实现名称，用于日志和监控。
     */
    String name();
}
