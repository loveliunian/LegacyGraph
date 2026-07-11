package io.github.legacygraph.service.qa;

import io.github.legacygraph.dto.qa.Ranking;
import io.github.legacygraph.entity.VectorDocument;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReciprocalRankFusionService 单元测试 — 验证加权 RRF 融合算法。
 *
 * <p>核心公式：score = Σ weight * 1/(K + rank)，rank 从 1 开始，K=60。</p>
 */
class ReciprocalRankFusionServiceTest {

    private final ReciprocalRankFusionService service = new ReciprocalRankFusionService();

    private VectorDocument doc(long id) {
        VectorDocument d = new VectorDocument();
        d.setId(id);
        return d;
    }

    @Test
    void fuse_emptyRankings_returnsEmpty() {
        assertTrue(service.fuse(List.of(), 5).isEmpty());
        assertTrue(service.fuse(null, 5).isEmpty());
    }

    @Test
    void fuse_nonPositiveTopK_returnsEmpty() {
        Ranking r = new Ranking("s", List.of(doc(1)), 1.0);
        assertTrue(service.fuse(List.of(r), 0).isEmpty());
    }

    @Test
    void fuse_singleRanking_preservesOrder() {
        // 单路时，RRF 分数 = 1/(K+rank) 严格递减，结果顺序应与输入一致
        VectorDocument d1 = doc(1), d2 = doc(2), d3 = doc(3);
        Ranking r = new Ranking("vector-main", List.of(d1, d2, d3), 1.0);

        List<VectorDocument> result = service.fuse(List.of(r), 10);

        assertEquals(3, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals(2L, result.get(1).getId());
        assertEquals(3L, result.get(2).getId());
    }

    @Test
    void fuse_topK_limitsResultSize() {
        VectorDocument d1 = doc(1), d2 = doc(2), d3 = doc(3);
        Ranking r = new Ranking("s", List.of(d1, d2, d3), 1.0);

        List<VectorDocument> result = service.fuse(List.of(r), 2);

        assertEquals(2, result.size());
    }

    @Test
    void fuse_twoRankings_docAppearingInBothRanksHigher() {
        // doc A 只在路1排第1 → score = 1/(60+1) = 1/61
        // doc B 在路1排第2且路2排第1 → score = 1/62 + 1/61
        // B 分数更高，应排在 A 前面
        VectorDocument a = doc(1), b = doc(2);
        Ranking r1 = new Ranking("vector-main", List.of(a, b), 1.0);
        Ranking r2 = new Ranking("keyword", List.of(b), 1.0);

        List<VectorDocument> result = service.fuse(List.of(r1, r2), 5);

        assertEquals(2, result.size());
        assertEquals(2L, result.get(0).getId(), "跨路出现的 B 应排在仅单路出现的 A 之前");
        assertEquals(1L, result.get(1).getId());
    }

    @Test
    void fuse_weightedRanking_higherWeightDominates() {
        // 两路返回不同文档，但路1权重远高于路2
        // 路1：[A, B] 权重 10.0
        // 路2：[C, D] 权重 1.0
        // A 分数 = 10/61, C 分数 = 1/61 → A 应排第一
        VectorDocument a = doc(1), b = doc(2), c = doc(3), d = doc(4);
        Ranking r1 = new Ranking("vector-main", List.of(a, b), 10.0);
        Ranking r2 = new Ranking("keyword", List.of(c, d), 1.0);

        List<VectorDocument> result = service.fuse(List.of(r1, r2), 4);

        assertEquals(4, result.size());
        assertEquals(1L, result.get(0).getId(), "高权重路第一名应排首位");
        assertEquals(3L, result.get(2).getId(), "低权重路第一名应排第三");
    }

    @Test
    void fuse_deduplicatesById() {
        // 同一文档在两路出现，结果中只出现一次
        VectorDocument shared = doc(1);
        Ranking r1 = new Ranking("vector-main", List.of(shared), 1.0);
        Ranking r2 = new Ranking("keyword", List.of(shared), 1.0);

        List<VectorDocument> result = service.fuse(List.of(r1, r2), 5);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void fuse_rankStartsFromOne() {
        // rank=1 时贡献 = 1/(K+1) = 1/61；rank=2 时 1/62
        // 验证 K 常量生效
        assertEquals(60, ReciprocalRankFusionService.K);
    }

    @Test
    void fuse_skipsNullDocuments() {
        VectorDocument a = doc(1);
        // List.of 不允许 null，用 Arrays.asList 构造含 null 的列表
        Ranking r = new Ranking("s", Arrays.asList(a, null, doc(2)), 1.0);

        List<VectorDocument> result = service.fuse(List.of(r), 5);

        assertEquals(2, result.size());
        // null 被跳过，rank 仍按有效文档计数推进
        assertEquals(1L, result.get(0).getId());
        assertEquals(2L, result.get(1).getId());
    }

    @Test
    void fuse_skipsNullAndEmptyRankings() {
        Ranking valid = new Ranking("s", List.of(doc(1)), 1.0);
        Ranking empty = new Ranking("empty", List.of(), 1.0);
        Ranking nullDocs = new Ranking("null", null, 1.0);

        // List.of 不允许 null 元素，用 Arrays.asList 构造含 null Ranking 的列表
        List<VectorDocument> result = service.fuse(Arrays.asList(null, empty, nullDocs, valid), 5);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void fuse_fallsBackToSha256WhenIdIsNull() {
        // id 为空时用 contentSha256 去重
        VectorDocument a = new VectorDocument();
        a.setContentSha256("sha-A");
        VectorDocument b = new VectorDocument();
        b.setContentSha256("sha-B");

        Ranking r1 = new Ranking("s1", List.of(a, b), 1.0);
        Ranking r2 = new Ranking("s2", List.of(a), 1.0);

        List<VectorDocument> result = service.fuse(List.of(r1, r2), 5);

        assertEquals(2, result.size());
        // a 跨路出现，分数更高，应排首位
        assertEquals("sha-A", result.get(0).getContentSha256());
    }

    @Test
    void fuse_multipleRankings_aggregatesAllContributions() {
        // 三路检索，每个文档的分数应正确累加
        VectorDocument a = doc(1), b = doc(2), c = doc(3);
        // 路1: [A, B, C] 权重1
        // 路2: [B, A] 权重1
        // 路3: [C] 权重1
        // A: 1/61 + 1/62
        // B: 1/62 + 1/61
        // C: 1/63 + 1/61
        // 排序：C(1/61+1/63) vs A(1/61+1/62) vs B(1/62+1/61)
        // B = A（同分），C 的第二项 1/63 < 1/62，所以 C 最低
        // 实际：A=B=1/61+1/62≈0.03252, C=1/61+1/63≈0.03227
        Ranking r1 = new Ranking("s1", List.of(a, b, c), 1.0);
        Ranking r2 = new Ranking("s2", List.of(b, a), 1.0);
        Ranking r3 = new Ranking("s3", List.of(c), 1.0);

        List<VectorDocument> result = service.fuse(List.of(r1, r2, r3), 3);

        assertEquals(3, result.size());
        // C 分数最低，应排末位
        assertEquals(3L, result.get(2).getId(), "C 分数最低应排末位");
    }
}
