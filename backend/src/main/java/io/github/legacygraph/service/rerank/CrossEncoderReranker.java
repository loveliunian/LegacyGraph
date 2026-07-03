package io.github.legacygraph.service.rerank;

import io.github.legacygraph.entity.VectorDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Cross-encoder 重排序 — 调用外部 reranker 服务（如 bge-reranker-large）。
 * <p>
 * 启用条件：配置 {@code qa.reranker.type=cross-encoder} 并设置 {@code qa.reranker.endpoint}。
 * </p>
 * <p>
 * 期望的 API 格式：
 * <pre>
 * POST {endpoint}
 * {
 *   "query": "...",
 *   "documents": ["doc1", "doc2", ...]
 * }
 * Response:
 * {
 *   "scores": [0.95, 0.12, ...]
 * }
 * </pre>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "qa.reranker.type", havingValue = "cross-encoder")
public class CrossEncoderReranker implements DocumentReranker {

    private final String endpoint;
    private final RestTemplate restTemplate;

    public CrossEncoderReranker(
            @Value("${qa.reranker.endpoint:http://localhost:8081/rerank}") String endpoint) {
        this.endpoint = endpoint;
        this.restTemplate = new RestTemplate();
        log.info("[CrossEncoderReranker] 初始化: endpoint={}", endpoint);
    }

    @Override
    public List<VectorDocument> rerank(String query, List<VectorDocument> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        if (candidates.size() <= topK) return candidates;

        try {
            List<String> texts = candidates.stream()
                .map(VectorDocument::getContent)
                .collect(Collectors.toList());

            Map<String, Object> request = new HashMap<>();
            request.put("query", query);
            request.put("documents", texts);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(endpoint, request, Map.class);

            if (response == null || !response.containsKey("scores")) {
                log.warn("[CrossEncoderReranker] 无效响应，降级为 keyword");
                return fallback(query, candidates, topK);
            }

            @SuppressWarnings("unchecked")
            List<Number> scores = (List<Number>) response.get("scores");

            List<IndexedDoc> indexed = new ArrayList<>();
            for (int i = 0; i < Math.min(candidates.size(), scores.size()); i++) {
                indexed.add(new IndexedDoc(candidates.get(i), scores.get(i).doubleValue()));
            }

            return indexed.stream()
                .sorted(Comparator.comparingDouble(IndexedDoc::score).reversed())
                .limit(topK)
                .map(IndexedDoc::doc)
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("[CrossEncoderReranker] 调用失败，降级为 keyword: {}", e.getMessage());
            return fallback(query, candidates, topK);
        }
    }

    @Override
    public String name() {
        return "cross-encoder";
    }

    private List<VectorDocument> fallback(String query, List<VectorDocument> candidates, int topK) {
        return new KeywordReranker().rerank(query, candidates, topK);
    }

    private record IndexedDoc(VectorDocument doc, double score) {}
}
