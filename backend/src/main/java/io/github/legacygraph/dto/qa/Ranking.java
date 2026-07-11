package io.github.legacygraph.dto.qa;

import io.github.legacygraph.entity.VectorDocument;

import java.util.List;

/**
 * 单路检索结果排名 — RRF 融合输入。
 *
 * <p>每路检索返回一组 {@link VectorDocument}（按相关性排序，排名从 1 开始），
 * 并携带该路权重。{@link io.github.legacygraph.service.qa.ReciprocalRankFusionService}
 * 按 score = weight * 1/(K + rank) 计算加权 RRF 分数。</p>
 *
 * @param source    检索路标识（如 "vector-main" / "vector-variant" / "keyword"）
 * @param documents 该路返回的文档列表（按相关性降序）
 * @param weight    该路权重（默认 1.0）
 */
public record Ranking(String source, List<VectorDocument> documents, double weight) {

    /**
     * 构造默认权重为 1.0 的 Ranking。
     */
    public Ranking(String source, List<VectorDocument> documents) {
        this(source, documents, 1.0);
    }
}
