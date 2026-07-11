package io.github.legacygraph.service.qa;

import io.github.legacygraph.dto.qa.Ranking;
import io.github.legacygraph.entity.VectorDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion（倒数排名融合）服务。
 *
 * <p>多路检索结果按排名融合：对每个文档，按其在每一路的排名计算加权分数
 * {@code score = weight * 1/(K + rank)}（rank 从 1 开始），累加后按总分降序取 topK。</p>
 *
 * <p>K=60 是业界经验值（源自 Cormack et al., 2009），对大多数检索场景表现稳健。</p>
 */
@Slf4j
@Service
public class ReciprocalRankFusionService {

    /** RRF 常量 K（平滑常数，防止高排名文档分数过大） */
    public static final int K = 60;

    /**
     * 融合多路检索结果。
     *
     * @param rankings 各路检索结果（每路文档按相关性降序排列）
     * @param topK     最终返回的文档数
     * @return 按 RRF 分数降序排列的 topK 文档
     */
    public List<VectorDocument> fuse(List<Ranking> rankings, int topK) {
        if (rankings == null || rankings.isEmpty()) {
            return List.of();
        }
        if (topK <= 0) {
            return List.of();
        }

        // 文档键 → 累计分数（用 id 去重；id 为空时退化为 contentSha256，再退化为对象 identity）
        Map<String, FusionEntry> scoreBoard = new HashMap<>();

        for (Ranking ranking : rankings) {
            if (ranking == null || ranking.documents() == null || ranking.documents().isEmpty()) {
                continue;
            }
            double weight = ranking.weight();
            int rank = 1;
            for (VectorDocument doc : ranking.documents()) {
                if (doc == null) {
                    continue;
                }
                String key = docKey(doc);
                double contribution = weight * (1.0 / (K + rank));
                FusionEntry entry = scoreBoard.computeIfAbsent(key, k -> new FusionEntry(doc));
                entry.score += contribution;
                // 保留分数最高的那一路首次出现的文档实例
                rank++;
            }
        }

        List<FusionEntry> entries = new ArrayList<>(scoreBoard.values());
        entries.sort(Comparator.comparingDouble((FusionEntry e) -> e.score).reversed());

        int limit = Math.min(topK, entries.size());
        List<VectorDocument> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            result.add(entries.get(i).document);
        }

        log.debug("RRF fused {} documents from {} rankings, topK={}",
            result.size(), rankings.size(), topK);
        return result;
    }

    /**
     * 文档去重键：优先用 id；id 为空时用 contentSha256；再为空则用对象 identity（无法跨路去重）。
     */
    private String docKey(VectorDocument doc) {
        if (doc.getId() != null) {
            return "id:" + doc.getId();
        }
        if (doc.getContentSha256() != null && !doc.getContentSha256().isBlank()) {
            return "sha:" + doc.getContentSha256();
        }
        return "obj:" + System.identityHashCode(doc);
    }

    /** 融合条目：文档 + 累计分数 */
    private static final class FusionEntry {
        final VectorDocument document;
        double score;

        FusionEntry(VectorDocument document) {
            this.document = document;
            this.score = 0.0;
        }
    }
}
