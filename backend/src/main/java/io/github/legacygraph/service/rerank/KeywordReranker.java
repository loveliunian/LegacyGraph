package io.github.legacygraph.service.rerank;

import io.github.legacygraph.entity.VectorDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 轻量级关键词/TF 风格重排序 — 无外部依赖的默认实现。
 */
@Slf4j
@Component
public class KeywordReranker implements DocumentReranker {

    @Override
    public List<VectorDocument> rerank(String query, List<VectorDocument> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        if (candidates.size() <= topK) {
            return candidates;
        }

        List<ScoredDocument> scored = candidates.stream()
            .map(doc -> new ScoredDocument(doc, calculateRelevanceScore(query, doc.getContent())))
            .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
            .limit(topK)
            .collect(Collectors.toList());

        log.debug("[KeywordReranker] Re-ranked {} -> {}", candidates.size(), scored.size());
        return scored.stream().map(ScoredDocument::document).collect(Collectors.toList());
    }

    @Override
    public String name() {
        return "keyword";
    }

    private double calculateRelevanceScore(String query, String content) {
        if (content == null || content.isBlank()) return 0.0;

        String queryLower = query.toLowerCase();
        String contentLower = content.toLowerCase();

        double score = 0.0;
        if (contentLower.contains(queryLower)) score += 10.0;

        String[] terms = queryLower.split("[\\s,，。？！]+");
        int matchCount = 0;
        for (String term : terms) {
            if (term.length() >= 2 && contentLower.contains(term)) {
                matchCount++;
                score += Math.log(1 + countOccurrences(contentLower, term));
            }
        }

        if (terms.length > 0) {
            score += ((double) matchCount / terms.length) * 5.0;
        }

        if (content.length() > 200) {
            String head = content.substring(0, 200).toLowerCase();
            for (String term : terms) {
                if (term.length() >= 2 && head.contains(term)) score += 2.0;
            }
        }
        return score;
    }

    private int countOccurrences(String text, String term) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(term, idx)) != -1) { count++; idx += term.length(); }
        return count;
    }

    private record ScoredDocument(VectorDocument document, double score) {}
}
