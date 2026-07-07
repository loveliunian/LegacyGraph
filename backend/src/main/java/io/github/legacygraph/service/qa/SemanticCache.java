package io.github.legacygraph.service.qa;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.SemanticCacheEntry;
import io.github.legacygraph.repository.SemanticCacheRepository;
import io.github.legacygraph.util.VectorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 语义缓存服务 - 基于语义相似度的问答缓存
 * <p>
 * 当用户提问时：
 * 1. 计算问题的 embedding
 * 2. 在缓存中查找相似问题（余弦相似度 > 阈值）
 * 3. 如果命中，直接返回缓存答案
 * 4. 如果未命中，调用 LLM 生成答案后写入缓存
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCache {

    private final SemanticCacheRepository cacheRepository;
    private final VectorRetrievalService vectorService;

    private static final double SIMILARITY_THRESHOLD = 0.95;
    private static final int CACHE_TTL_HOURS = 24;

    /**
     * 查询缓存 - 返回语义相似的历史答案
     *
     * @param projectId 项目 ID
     * @param question  用户问题
     * @return 缓存命中时返回答案，否则返回 null
     */
    public String get(String projectId, String question) {
        try {
            // 1. 计算问题 embedding
            float[] queryEmbedding = vectorService.computeEmbedding(question);

            // 2. 向量检索相似缓存
            Optional<SemanticCacheEntry> cached = vectorService.findSimilarCache(
                projectId, queryEmbedding, SIMILARITY_THRESHOLD
            );

            if (cached.isPresent()) {
                SemanticCacheEntry entry = cached.get();
                
                // 3. 检查 TTL
                if (isExpired(entry)) {
                    log.debug("Cache expired for question: {}", question);
                    cacheRepository.deleteById(entry.getId());
                    return null;
                }

                // 4. 命中，增加访问次数
                Integer currentHitCount = entry.getHitCount();
                entry.setHitCount(currentHitCount != null ? currentHitCount + 1 : 1);
                entry.setLastAccessAt(LocalDateTime.now());
                cacheRepository.updateById(entry);

                String answerText = entry.getAnswer();
                int answerLength = answerText != null ? answerText.length() : 0;
                log.info("Semantic cache hit: question='{}', similarity={}, answerLength={}",
                    truncate(question, 50),
                    entry.getSimilarity(),
                    answerLength);

                return entry.getAnswer();
            }

            log.debug("Semantic cache miss for question: {}", truncate(question, 50));
            return null;

        } catch (Exception e) {
            log.warn("Semantic cache lookup failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写入缓存 - 保存问答对供后续复用
     *
     * @param projectId 项目 ID
     * @param question  用户问题
     * @param answer    LLM 生成的答案
     * @param evidence  使用的证据（JSON）
     */
    public void put(String projectId, String question, String answer, String evidence) {
        try {
            // M6 修复：并发写入去重 —— 先按问题文本精确去重，避免并发 cache miss 写入重复条目
            LambdaQueryWrapper<SemanticCacheEntry> dedupWrapper = new LambdaQueryWrapper<>();
            dedupWrapper.eq(SemanticCacheEntry::getProjectId, projectId)
                    .eq(SemanticCacheEntry::getQuestion, question)
                    .last("LIMIT 1");
            if (cacheRepository.selectOne(dedupWrapper) != null) {
                log.debug("Semantic cache skipped: duplicate question already cached for projectId={}", projectId);
                return;
            }

            // 1. 计算问题 embedding
            float[] embedding = vectorService.computeEmbedding(question);
            if (embedding == null || embedding.length == 0) {
                log.warn("Semantic cache store skipped: embedding unavailable for question='{}'",
                    truncate(question, 50));
                return;
            }

            // 2. 转为 pgvector 文本格式 "[0.1,0.2,...]"
            String embeddingStr = VectorUtils.floatArrayToVectorLiteral(embedding);

            // 3. 创建缓存条目
            SemanticCacheEntry entry = new SemanticCacheEntry();
            entry.setProjectId(projectId);
            entry.setQuestion(question);
            entry.setAnswer(answer);
            entry.setEvidence(evidence);
            entry.setQuestionEmbedding(embeddingStr);
            entry.setHitCount(0);
            entry.setCreatedAt(LocalDateTime.now());
            entry.setLastAccessAt(LocalDateTime.now());

            // 4. 保存
            cacheRepository.insert(entry);

            log.info("Semantic cache stored: question='{}', answerLength={}",
                truncate(question, 50), answer.length());

        } catch (Exception e) {
            log.warn("Semantic cache store failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 失效某项目下全部语义缓存（schema 变更后调用，避免返回过时答案）。
     * <p>对齐 doc/项目升级计划/QA变更影响问答打通详细设计.md §4.4.2</p>
     */
    public void invalidateByProject(String projectId) {
        try {
            if (projectId == null || projectId.isBlank()) return;
            LambdaQueryWrapper<SemanticCacheEntry> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SemanticCacheEntry::getProjectId, projectId);
            int deleted = cacheRepository.delete(wrapper);
            log.info("Invalidated {} semantic cache entries for project {}", deleted, projectId);
        } catch (Exception e) {
            log.warn("Semantic cache invalidation failed for project {}: {}", projectId, e.getMessage());
        }
    }

    /**
     * 清除过期缓存
     * L7 修复：添加 @Scheduled 定时触发，每小时执行一次
     */
    @Scheduled(fixedRate = 3600000)
    public void evictExpired() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(CACHE_TTL_HOURS);

            LambdaQueryWrapper<SemanticCacheEntry> wrapper = new LambdaQueryWrapper<>();
            wrapper.lt(SemanticCacheEntry::getLastAccessAt, cutoff);

            int deleted = cacheRepository.delete(wrapper);
            if (deleted > 0) {
                log.info("Evicted {} expired semantic cache entries", deleted);
            }
        } catch (Exception e) {
            log.warn("Semantic cache eviction failed: {}", e.getMessage());
        }
    }

    private boolean isExpired(SemanticCacheEntry entry) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(CACHE_TTL_HOURS);
        return entry.getLastAccessAt().isBefore(cutoff);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
