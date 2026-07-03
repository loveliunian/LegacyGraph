package io.github.legacygraph.service.qa;

import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.service.rerank.DocumentReranker;
import io.github.legacygraph.service.rerank.KeywordReranker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Re-ranking 服务 — 委托给可插拔的 {@link DocumentReranker} 实现。
 * <p>
 * 默认使用 {@link KeywordReranker}（轻量关键词/TF 风格）。
 * 配置 {@code qa.reranker.type=cross-encoder} 并设置 {@code qa.reranker.endpoint}
 * 可切换为 cross-encoder 模型。
 * </p>
 */
@Slf4j
@Service
public class ReRankingService {

    private final DocumentReranker reranker;

    public ReRankingService(List<DocumentReranker> rerankers) {
        // 优先选择 cross-encoder（如果启用），否则使用 keyword
        this.reranker = rerankers.stream()
            .filter(r -> !"keyword".equals(r.name()))
            .findFirst()
            .orElseGet(() -> rerankers.stream()
                .filter(r -> "keyword".equals(r.name()))
                .findFirst()
                .orElse(new KeywordReranker()));
        log.info("ReRankingService 初始化: 使用 {} reranker", this.reranker.name());
    }

    /**
     * 对检索结果进行重排序
     */
    public List<VectorDocument> reRank(String query, List<VectorDocument> candidates, int topK) {
        return reranker.rerank(query, candidates, topK);
    }
}
