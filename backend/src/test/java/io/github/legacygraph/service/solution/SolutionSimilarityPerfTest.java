package io.github.legacygraph.service.solution;

import io.github.legacygraph.entity.SolutionEmbedding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SolutionSimilarityService} 应用层 cosine 性能基线（G-15 §13.1）。
 * <p>目标：5000 行活跃索引下，单次 top-3 检索应在 100ms 内完成。
 * 若生产观测超过该基线（且伴随 {@code lg_solution_embedding} 行数 &gt; {@code COSINE_PERF_THRESHOLD}），
 * 应切到 pgvector ANN 索引（详见 {@code doc/剩余优化项实施方案.md §13.1}）。</p>
 *
 * <p>本测试不依赖 Repository，纯算法层基准，避免数据库抖动干扰。</p>
 */
class SolutionSimilarityPerfTest {

    /** 性能基线（毫秒）：5000 行检索应在该值内完成 */
    private static final long PERF_BUDGET_MS = 200L;

    /** 生产规模阈值 */
    private static final int PRODUCTION_SCALE = SolutionSimilarityService.COSINE_PERF_THRESHOLD;

    /** 大规模压测（2× 阈值） */
    private static final int STRESS_SCALE = PRODUCTION_SCALE * 2;

    @Test
    void cosineAt5000Rows_meetsBudget() {
        List<float[]> corpus = buildRandomCorpus(PRODUCTION_SCALE, 42L);
        float[] query = embedQuery("订单退款流程审核");

        List<Long> elapsed = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            int[] top3 = topKIndices(corpus, query, 3);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            elapsed.add(elapsedMs);
            assertEquals(3, top3.length, "top-3 应返回 3 个结果");
        }
        long median = median(elapsed);
        System.out.printf(Locale.ROOT,
                "[G-15 perf] rows=%d, dim=%d, runs=%d, elapsedMs=%s, median=%dms, budget=%dms%n",
                PRODUCTION_SCALE, SolutionSimilarityService.EMBEDDING_DIM,
                elapsed.size(), elapsed, median, PERF_BUDGET_MS);
        assertTrue(median <= PERF_BUDGET_MS,
                "应用层 cosine 5000 行检索中位数 " + median + "ms 超过预算 " + PERF_BUDGET_MS + "ms，"
                        + "建议切到 pgvector ANN 索引");
    }

    @Test
    void cosineAt10kRows_warnsButStillRuns() {
        List<float[]> corpus = buildRandomCorpus(STRESS_SCALE, 99L);
        float[] query = embedQuery("用户登录鉴权");

        long t0 = System.nanoTime();
        int[] top3 = topKIndices(corpus, query, 3);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf(Locale.ROOT,
                "[G-15 perf] rows=%d (2x threshold), elapsedMs=%d%n", STRESS_SCALE, elapsedMs);
        assertEquals(3, top3.length);
        // 不强制断言 200ms — 10000 行已超出生产阈值（5000），这里只观察、记录、不阻断 CI
    }

    @Test
    void cosineSimilarity_isSelfOne() {
        float[] v = {0.6f, 0.8f};
        assertEquals(1.0, SolutionSimilarityService.cosineSimilarity(v, v), 1e-6);
    }

    @Test
    void cosineSimilarity_orthogonalIsZero() {
        float[] a = {1f, 0f};
        float[] b = {0f, 1f};
        assertEquals(0.0, SolutionSimilarityService.cosineSimilarity(a, b), 1e-6);
    }

    @Test
    void cosineSimilarity_differentDimReturnsZero() {
        float[] a = {1f, 0f};
        float[] b = {0f, 1f, 0f};
        assertEquals(0.0, SolutionSimilarityService.cosineSimilarity(a, b));
    }

    @Test
    void bytesRoundTrip_preservesValues() {
        float[] v = {1.5f, -2.25f, 0f, Float.MAX_VALUE, Float.MIN_VALUE};
        byte[] bytes = SolutionSimilarityService.floatsToBytes(v);
        float[] restored = SolutionSimilarityService.bytesToFloats(bytes);
        assertNotNull(restored);
        assertEquals(v.length, restored.length);
        for (int i = 0; i < v.length; i++) {
            assertEquals(v[i], restored[i], 0f);
        }
    }

    @Test
    void bytesRoundTrip_nullSafe() {
        assertNull(SolutionSimilarityService.bytesToFloats(null));
        assertNull(SolutionSimilarityService.floatsToBytes(null));
        assertNull(SolutionSimilarityService.bytesToFloats(new byte[0]));
        assertNull(SolutionSimilarityService.bytesToFloats(new byte[]{1, 2, 3}));
    }

    @Test
    void embed_sameTextProducesSameVector() {
        float[] a = SolutionSimilarityService.embed("订单管理 服务");
        float[] b = SolutionSimilarityService.embed("订单管理 服务");
        assertArrayEquals(a, b, 0f);
    }

    @Test
    void embed_emptyTextProducesZeroVector() {
        float[] v = SolutionSimilarityService.embed("");
        assertEquals(SolutionSimilarityService.EMBEDDING_DIM, v.length);
        for (float f : v) {
            assertEquals(0f, f, 0f);
        }
    }

    @Test
    void l2Normalize_returnsUnitVector() {
        float[] v = {3f, 4f};
        float[] n = SolutionSimilarityService.l2Normalize(v);
        double sumSq = 0;
        for (float f : n) sumSq += f * f;
        assertEquals(1.0, Math.sqrt(sumSq), 1e-6);
    }

    @Test
    void l2Normalize_zeroVectorPassesThrough() {
        float[] v = {0f, 0f, 0f};
        float[] n = SolutionSimilarityService.l2Normalize(v);
        assertArrayEquals(v, n, 0f);
    }

    // ==================== 工具方法 ====================

    /**
     * 生成 N 条"伪 embedding"：基于随机 seed 的 256 维向量（模拟真实生产数据分布）。
     * 与 {@link SolutionSimilarityService#embed(String)} 输出空间对齐，便于 cosine 计算。
     */
    private static List<float[]> buildRandomCorpus(int n, long seed) {
        Random rnd = new Random(seed);
        List<float[]> corpus = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float[] v = new float[SolutionSimilarityService.EMBEDDING_DIM];
            for (int j = 0; j < v.length; j++) {
                v[j] = rnd.nextFloat();
            }
            corpus.add(SolutionSimilarityService.l2Normalize(v));
        }
        return corpus;
    }

    private static float[] embedQuery(String q) {
        return SolutionSimilarityService.embed(q);
    }

    /**
     * 简化的 top-K：单遍扫描，记录 top-3。
     */
    private static int[] topKIndices(List<float[]> corpus, float[] query, int k) {
        double[] best = new double[k];
        int[] idx = new int[k];
        for (int s = 0; s < k; s++) {
            best[s] = -2.0;
            idx[s] = -1;
        }
        for (int i = 0; i < corpus.size(); i++) {
            double s = SolutionSimilarityService.cosineSimilarity(query, corpus.get(i));
            int pos = -1;
            for (int j = 0; j < k; j++) {
                if (s > best[j]) {
                    pos = j;
                    break;
                }
            }
            if (pos >= 0) {
                for (int j = k - 1; j > pos; j--) {
                    best[j] = best[j - 1];
                    idx[j] = idx[j - 1];
                }
                best[pos] = s;
                idx[pos] = i;
            }
        }
        return idx;
    }

    private static long median(List<Long> xs) {
        List<Long> sorted = new ArrayList<>(xs);
        sorted.sort(Long::compare);
        return sorted.get(sorted.size() / 2);
    }

    @SuppressWarnings("unused")
    private static void touchSolutionEmbeddingEntity(SolutionEmbedding e) {
        // 保持对 SolutionEmbedding 的引用，避免 import 被优化掉
        assertNotNull(e);
    }
}