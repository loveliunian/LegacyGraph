package io.github.legacygraph.service.solution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.dto.solution.SimilarSolution;
import io.github.legacygraph.entity.Solution;
import io.github.legacygraph.entity.SolutionEmbedding;
import io.github.legacygraph.entity.SolutionStep;
import io.github.legacygraph.repository.SolutionEmbeddingRepository;
import io.github.legacygraph.repository.SolutionRepository;
import io.github.legacygraph.repository.SolutionStepRepository;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 方案相似度检索服务（G-15）。
 * <p>为 APPROVED 方案建立索引，并基于关键词重合度检索同项目下的相似历史方案。
 * 预留 embedding 向量字段，后续可升级为余弦相似度。</p>
 */
@Slf4j
@Service
public class SolutionSimilarityService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final String SOLUTION_STATUS_APPROVED = "APPROVED";

    /** 返回的相似方案数量上限 */
    private static final int TOP_N = 3;

    /** 关键词最小长度（过滤单字符噪声） */
    private static final int MIN_TOKEN_LENGTH = 2;

    /** 中文/英文停用词，用于过滤无意义的高频词 */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been",
            "and", "or", "not", "no", "to", "of", "in", "for", "on",
            "with", "by", "at", "from", "as", "it", "this", "that",
            "的", "了", "和", "与", "在", "为", "是", "对", "从", "中",
            "进行", "通过", "使用", "需要", "可以", "一个", "如果"
    );

    /**
     * 应用层 embedding 向量维度（G-15 实施决策）。
     * <p>维度越高 cosine 计算量越大（O(d) per comparison）；当前 256 维在 1 万行规模下
     * 单次 top-K 检索可在 &lt;50ms 完成（详见 {@code SolutionSimilarityPerfTest}）。
     * 若未来接入真实 LLM embedding（如 1536/3072 维），需重新评估。</p>
     */
    static final int EMBEDDING_DIM = 256;

    /**
     * 当 {@code lg_solution_embedding} 活跃行数超过该阈值时，应用层 cosine 性能拐点。
     * <p>超过该阈值的项目建议后续切换到 pgvector ANN 索引（{@code §13.1}）。</p>
     */
    public static final int COSINE_PERF_THRESHOLD = 5_000;

    private final SolutionEmbeddingRepository embeddingRepository;
    private final SolutionRepository solutionRepository;
    private final SolutionStepRepository stepRepository;

    public SolutionSimilarityService(SolutionEmbeddingRepository embeddingRepository,
                                      SolutionRepository solutionRepository,
                                      SolutionStepRepository stepRepository) {
        this.embeddingRepository = embeddingRepository;
        this.solutionRepository = solutionRepository;
        this.stepRepository = stepRepository;
    }

    // ==================== 索引管理 ====================

    /**
     * 为 APPROVED 方案建立嵌入索引。
     * <p>如果方案已存在索引则更新文本，否则新建索引。
     * 仅 APPROVED 状态的方案才会被索引。</p>
     *
     * @param solutionId 方案 ID
     */
    @Transactional
    public void indexSolution(String solutionId) {
        Solution solution = solutionRepository.selectById(solutionId);
        if (solution == null) {
            throw new IllegalArgumentException("方案不存在: " + solutionId);
        }
        if (!SOLUTION_STATUS_APPROVED.equals(solution.getStatus())) {
            log.info("跳过索引：方案 {} 状态为 {}，仅 APPROVED 方案建立索引", solutionId, solution.getStatus());
            return;
        }

        List<SolutionStep> steps = loadSteps(solutionId);
        String embeddingText = buildEmbeddingText(solution, steps);
        // 应用层 embedding：将分词后的 token 哈希到 256 维 float 向量
        // （生产可替换为 LLM 生成的真实 embedding，调用接口不变）
        float[] vector = embed(embeddingText);
        byte[] vectorBytes = floatsToBytes(vector);

        // 查找已有索引
        LambdaQueryWrapper<SolutionEmbedding> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SolutionEmbedding::getSolutionId, solutionId);
        SolutionEmbedding existing = embeddingRepository.selectOne(wrapper);

        if (existing != null) {
            existing.setEmbeddingText(embeddingText);
            existing.setEmbedding(vectorBytes);
            existing.setStatus(STATUS_ACTIVE);
            embeddingRepository.updateById(existing);
            log.info("方案索引已更新: solutionId={}, textLength={}, dim={}",
                    solutionId, embeddingText.length(), vector.length);
        } else {
            SolutionEmbedding embedding = new SolutionEmbedding();
            embedding.setId(IdUtil.fastUUID());
            embedding.setSolutionId(solutionId);
            embedding.setProjectId(solution.getProjectId());
            embedding.setEmbeddingText(embeddingText);
            embedding.setEmbedding(vectorBytes);
            embedding.setUsefulCount(0);
            embedding.setStatus(STATUS_ACTIVE);
            embedding.setCreatedAt(LocalDateTime.now());
            embeddingRepository.insert(embedding);
            log.info("方案索引已创建: solutionId={}, textLength={}, dim={}",
                    solutionId, embeddingText.length(), vector.length);
        }
    }

    // ==================== 相似检索 ====================

    /**
     * 搜索同项目下的相似历史方案。
     * <p>基于关键词重合度做简单文本相似度匹配，返回 top 3。
     * 查询文本由目标（goal）与需求条目文本（itemText）拼接后分词。</p>
     *
     * @param projectId 项目 ID
     * @param goal      当前方案目标（可为 null）
     * @param itemText  需求条目文本（可为 null）
     * @return 相似方案列表（按相似度降序），最多 3 条
     */
    public List<SimilarSolution> searchSimilar(String projectId, String goal, String itemText) {
        // 1. 查询同项目的 ACTIVE 方案索引
        LambdaQueryWrapper<SolutionEmbedding> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SolutionEmbedding::getProjectId, projectId)
                .eq(SolutionEmbedding::getStatus, STATUS_ACTIVE);
        List<SolutionEmbedding> candidates = embeddingRepository.selectList(wrapper);
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }

        // 应用层 cosine 性能告警（G-15 实施决策 §13.1）：
        // 单项目活跃行数超过 COSINE_PERF_THRESHOLD 时建议切到 pgvector ANN
        if (candidates.size() > COSINE_PERF_THRESHOLD) {
            log.warn("lg_solution_embedding 行数 {} 超过应用层 cosine 性能阈值 {}, "
                            + "建议后续切换到 pgvector ANN 索引以避免检索延迟线性增长",
                    candidates.size(), COSINE_PERF_THRESHOLD);
        }

        // 2. 构建查询关键词集合 + 查询向量
        String queryText = joinNonBlank(" ", goal, itemText);
        Set<String> queryTokens = tokenize(queryText);
        if (queryTokens.isEmpty()) {
            return new ArrayList<>();
        }
        float[] queryVector = embed(queryText);

        // 3. 计算每个候选方案的相似度并排序
        // 优先用 embedding cosine；若候选缺失向量则退化为 Jaccard（保持向后兼容）
        List<SimilarSolution> results = new ArrayList<>();
        for (SolutionEmbedding emb : candidates) {
            double score;
            float[] candidateVector = bytesToFloats(emb.getEmbedding());
            if (candidateVector != null) {
                score = cosineSimilarity(queryVector, candidateVector);
            } else {
                Set<String> candidateTokens = tokenize(emb.getEmbeddingText());
                score = computeSimilarity(queryTokens, candidateTokens);
            }
            if (score <= 0.0) {
                continue;
            }
            results.add(buildSimilarSolution(emb, score));
        }

        results.sort(Comparator.comparingDouble(SimilarSolution::getSimilarityScore).reversed());
        if (results.size() > TOP_N) {
            results = results.subList(0, TOP_N);
        }
        return results;
    }

    // ==================== 参考价值计数 ====================

    /**
     * 增加方案的参考价值计数（被引用为有用时调用）。
     *
     * @param solutionId 方案 ID
     */
    @Transactional
    public void incrementUsefulCount(String solutionId) {
        LambdaQueryWrapper<SolutionEmbedding> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SolutionEmbedding::getSolutionId, solutionId);
        SolutionEmbedding embedding = embeddingRepository.selectOne(wrapper);
        if (embedding == null) {
            log.warn("增加参考价值计数失败：方案 {} 无嵌入索引", solutionId);
            return;
        }
        int newCount = (embedding.getUsefulCount() != null ? embedding.getUsefulCount() : 0) + 1;
        embedding.setUsefulCount(newCount);
        embeddingRepository.updateById(embedding);
        log.info("方案参考价值计数已增加: solutionId={}, usefulCount={}", solutionId, newCount);
    }

    // ==================== 内部方法 ====================

    /**
     * 加载方案的步骤列表（按 stepIndex 排序）。
     */
    private List<SolutionStep> loadSteps(String solutionId) {
        LambdaQueryWrapper<SolutionStep> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SolutionStep::getSolutionId, solutionId)
                .orderByAsc(SolutionStep::getStepIndex);
        return stepRepository.selectList(wrapper);
    }

    /**
     * 构建嵌入文本：方案摘要 + 步骤标题与描述。
     */
    private String buildEmbeddingText(Solution solution, List<SolutionStep> steps) {
        StringBuilder sb = new StringBuilder();
        if (solution.getSummary() != null) {
            sb.append(solution.getSummary());
        }
        for (SolutionStep step : steps) {
            if (step.getTitle() != null) {
                sb.append(" ").append(step.getTitle());
            }
            if (step.getDescription() != null) {
                sb.append(" ").append(step.getDescription());
            }
            if (step.getFilePath() != null) {
                sb.append(" ").append(step.getFilePath());
            }
        }
        return sb.toString().trim();
    }

    /**
     * 构建 SimilarSolution DTO：加载方案与步骤详情。
     */
    private SimilarSolution buildSimilarSolution(SolutionEmbedding emb, double score) {
        Solution solution = solutionRepository.selectById(emb.getSolutionId());
        List<SolutionStep> steps = solution != null ? loadSteps(emb.getSolutionId()) : new ArrayList<>();

        List<String> keySteps = new ArrayList<>();
        for (SolutionStep step : steps) {
            if (step.getTitle() != null && !step.getTitle().isBlank()) {
                keySteps.add(step.getTitle());
            }
        }

        return SimilarSolution.builder()
                .solutionId(emb.getSolutionId())
                .projectId(emb.getProjectId())
                .summary(solution != null ? solution.getSummary() : null)
                .goal(extractGoal(emb.getEmbeddingText()))
                .keySteps(keySteps)
                .similarityScore(score)
                .usefulCount(emb.getUsefulCount() != null ? emb.getUsefulCount() : 0)
                .build();
    }

    /**
     * 从嵌入文本中提取目标描述（取前 100 个字符作为 goal 展示）。
     */
    private String extractGoal(String embeddingText) {
        if (embeddingText == null || embeddingText.isBlank()) {
            return null;
        }
        return embeddingText.length() > 100
                ? embeddingText.substring(0, 100)
                : embeddingText;
    }

    /**
     * 文本分词：按非字母数字字符拆分，过滤停用词与短词。
     * 支持中英文混合文本。
     */
    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return new HashSet<>();
        }
        // 转小写后按非字母数字（含中文为单字）拆分
        String lower = text.toLowerCase();
        String[] rawTokens = lower.split("[^a-z0-9\\u4e00-\\u9fa5]+");
        Set<String> tokens = new HashSet<>();
        for (String token : rawTokens) {
            if (token.length() < MIN_TOKEN_LENGTH) {
                continue;
            }
            if (STOP_WORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    /**
     * 计算两个关键词集合的 Jaccard 相似度。
     * <p>score = |交集| / |并集|，范围 0.0 ~ 1.0。</p>
     */
    private double computeSimilarity(Set<String> queryTokens, Set<String> candidateTokens) {
        if (candidateTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(queryTokens);
        intersection.retainAll(candidateTokens);
        Set<String> union = new HashSet<>(queryTokens);
        union.addAll(candidateTokens);
        return (double) intersection.size() / union.size();
    }

    /**
     * 拼接非空字符串。
     */
    private String joinNonBlank(String delimiter, String... parts) {
        List<String> nonBlank = new ArrayList<>();
        for (String p : Arrays.asList(parts)) {
            if (p != null && !p.isBlank()) {
                nonBlank.add(p);
            }
        }
        return String.join(delimiter, nonBlank);
    }

    // ==================== 应用层 embedding（G-15 §13.1）====================

    /**
     * 将文本编码为 {@value #EMBEDDING_DIM} 维 float 向量（应用层）。
     * <p>当前实现：基于 token 哈希桶 + L2 归一化。可替换为 LLM 生成的真实 embedding
     * （保持调用接口不变即可）。</p>
     *
     * @param text 源文本
     * @return 单位向量（已 L2 归一化，便于直接做 cosine）
     */
    static float[] embed(String text) {
        float[] vec = new float[EMBEDDING_DIM];
        if (text == null || text.isBlank()) {
            return vec;
        }
        Set<String> tokens = tokenize(text);
        for (String token : tokens) {
            int bucket = Math.floorMod(token.hashCode(), EMBEDDING_DIM);
            vec[bucket] += 1.0f;
        }
        return l2Normalize(vec);
    }

    /**
     * L2 归一化向量（cosine = 点积 / (||a||·||b||)，归一化后 cos = dot）。
     *
     * @param vec 输入向量（会复制后处理，原数组不变）
     * @return 单位向量；若范数为 0 返回原样（避免除 0）
     */
    static float[] l2Normalize(float[] vec) {
        if (vec == null || vec.length == 0) {
            return vec;
        }
        double sumSq = 0.0;
        for (float v : vec) {
            sumSq += (double) v * v;
        }
        if (sumSq == 0.0) {
            return vec;
        }
        float norm = (float) Math.sqrt(sumSq);
        float[] out = new float[vec.length];
        for (int i = 0; i < vec.length; i++) {
            out[i] = vec[i] / norm;
        }
        return out;
    }

    /**
     * 计算两个单位向量的 cosine 相似度（点积 = cos θ）。
     *
     * @param a 已归一化向量
     * @param b 已归一化向量
     * @return 范围 -1.0 ~ 1.0；维度不一致或任一为 null 返回 0.0
     */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        // 数值漂移保护：clip 到 [-1, 1]
        if (dot > 1.0) return 1.0;
        if (dot < -1.0) return -1.0;
        return dot;
    }

    /**
     * float[] → byte[]（大端 IEEE-754）。
     */
    static byte[] floatsToBytes(float[] vec) {
        if (vec == null) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4);
        FloatBuffer fb = buf.asFloatBuffer();
        fb.put(vec);
        return buf.array();
    }

    /**
     * byte[] → float[]（反向 {@link #floatsToBytes}）。
     */
    static float[] bytesToFloats(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % 4 != 0) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        FloatBuffer fb = buf.asFloatBuffer();
        float[] out = new float[fb.remaining()];
        fb.get(out);
        return out;
    }
}
