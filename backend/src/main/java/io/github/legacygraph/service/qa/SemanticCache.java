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
 *
 * <p><b>版本化缓存（Task 9.6）</b>：新增 graphReleaseId 和 aclHash 维度，
 * 同一问题在不同图谱版本/不同 ACL 上下文下有独立的缓存条目，
 * 避免跨版本返回过时答案或泄露 ACL 隔离数据。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCache {

    private final SemanticCacheRepository cacheRepository;
    private final VectorRetrievalService vectorService;

    private static final double SIMILARITY_THRESHOLD = 0.95;
    private static final int CACHE_TTL_HOURS = 24;

    // ==================== 版本化缓存方法（推荐） ====================

    /**
     * 版本化查询缓存 — 返回完整证据 JSON。
     *
     * @param projectId      项目 ID
     * @param question       用户问题
     * @param graphReleaseId 图谱发布 ID（版本化隔离维度，可为 null）
     * @param aclHash        ACL 哈希（版本化隔离维度，可为 null）
     * @return 缓存命中时返回完整缓存条目（含答案和证据 JSON），否则返回 empty
     */
    public Optional<SemanticCacheEntry> getVersioned(String projectId, String question,
                                                      String graphReleaseId, String aclHash) {
        try {
            float[] queryEmbedding = vectorService.computeEmbedding(question);

            Optional<SemanticCacheEntry> cached = vectorService.findSimilarCacheVersioned(
                projectId, queryEmbedding, SIMILARITY_THRESHOLD, graphReleaseId, aclHash
            );

            if (cached.isPresent()) {
                SemanticCacheEntry entry = cached.get();

                if (isExpired(entry)) {
                    log.debug("Versioned cache expired for question: {}", truncate(question, 50));
                    cacheRepository.deleteById(entry.getId());
                    return Optional.empty();
                }

                Integer currentHitCount = entry.getHitCount();
                entry.setHitCount(currentHitCount != null ? currentHitCount + 1 : 1);
                entry.setLastAccessAt(LocalDateTime.now());
                cacheRepository.updateById(entry);

                log.info("Versioned cache hit: question='{}', releaseId={}, aclHash={}, similarity={}",
                    truncate(question, 50), graphReleaseId, aclHash, entry.getSimilarity());

                return Optional.of(entry);
            }

            log.debug("Versioned cache miss for question: {}", truncate(question, 50));
            return Optional.empty();

        } catch (Exception e) {
            log.warn("Versioned cache lookup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 版本化写入缓存。
     *
     * @param projectId      项目 ID
     * @param question       用户问题
     * @param answer         LLM 生成的答案
     * @param evidence       使用的证据（JSON）
     * @param graphReleaseId 图谱发布 ID
     * @param aclHash        ACL 哈希
     * @param intent         意图分类
     * @param confidence     置信度分数
     */
    public void putVersioned(String projectId, String question, String answer, String evidence,
                             String graphReleaseId, String aclHash, String intent, Double confidence) {
        try {
            // 并发写入去重
            LambdaQueryWrapper<SemanticCacheEntry> dedupWrapper = new LambdaQueryWrapper<>();
            dedupWrapper.eq(SemanticCacheEntry::getProjectId, projectId)
                    .eq(SemanticCacheEntry::getQuestion, question);
            if (graphReleaseId != null) {
                dedupWrapper.eq(SemanticCacheEntry::getGraphReleaseId, graphReleaseId);
            } else {
                dedupWrapper.isNull(SemanticCacheEntry::getGraphReleaseId);
            }
            if (aclHash != null) {
                dedupWrapper.eq(SemanticCacheEntry::getAclHash, aclHash);
            } else {
                dedupWrapper.isNull(SemanticCacheEntry::getAclHash);
            }
            dedupWrapper.last("LIMIT 1");
            if (cacheRepository.selectOne(dedupWrapper) != null) {
                log.debug("Versioned cache skipped: duplicate question already cached for projectId={}, releaseId={}",
                    projectId, graphReleaseId);
                return;
            }

            float[] embedding = vectorService.computeEmbedding(question);
            if (embedding == null || embedding.length == 0) {
                log.warn("Versioned cache store skipped: embedding unavailable for question='{}'",
                    truncate(question, 50));
                return;
            }

            String embeddingStr = VectorUtils.floatArrayToVectorLiteral(embedding);

            SemanticCacheEntry entry = new SemanticCacheEntry();
            entry.setProjectId(projectId);
            entry.setQuestion(question);
            entry.setAnswer(answer);
            entry.setEvidence(evidence);
            entry.setQuestionEmbedding(embeddingStr);
            entry.setGraphReleaseId(graphReleaseId);
            entry.setAclHash(aclHash);
            entry.setIntent(intent);
            entry.setConfidence(confidence);
            entry.setHitCount(0);
            entry.setCreatedAt(LocalDateTime.now());
            entry.setLastAccessAt(LocalDateTime.now());

            cacheRepository.insert(entry);

            log.info("Versioned cache stored: question='{}', answerLength={}, releaseId={}, aclHash={}, intent={}, confidence={}",
                truncate(question, 50), answer.length(), graphReleaseId, aclHash, intent, confidence);

        } catch (Exception e) {
            log.warn("Versioned cache store failed: {}", e.getMessage(), e);
        }
    }

    // ==================== 旧方法签名（向后兼容） ====================

    /**
     * 查询缓存 - 返回语义相似的历史答案（旧签名，向后兼容）。
     *
     * @param projectId 项目 ID
     * @param question  用户问题
     * @return 缓存命中时返回答案，否则返回 null
     */
    public String get(String projectId, String question) {
        return getVersioned(projectId, question, null, null)
            .map(SemanticCacheEntry::getAnswer)
            .orElse(null);
    }

    /**
     * 写入缓存 - 保存问答对供后续复用（旧签名，向后兼容）。
     *
     * @param projectId 项目 ID
     * @param question  用户问题
     * @param answer    LLM 生成的答案
     * @param evidence  使用的证据（JSON）
     */
    public void put(String projectId, String question, String answer, String evidence) {
        putVersioned(projectId, question, answer, evidence, null, null, null, null);
    }

    // ==================== 其他方法 ====================

    /**
     * 失效某项目下全部语义缓存（schema 变更后调用，避免返回过时答案）。
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
     * 失效某项目某图谱发布版本下的语义缓存。
     *
     * @param projectId      项目 ID
     * @param graphReleaseId 图谱发布 ID
     */
    public void invalidateByRelease(String projectId, String graphReleaseId) {
        try {
            if (projectId == null || projectId.isBlank() || graphReleaseId == null) return;
            LambdaQueryWrapper<SemanticCacheEntry> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SemanticCacheEntry::getProjectId, projectId)
                   .eq(SemanticCacheEntry::getGraphReleaseId, graphReleaseId);
            int deleted = cacheRepository.delete(wrapper);
            log.info("Invalidated {} semantic cache entries for project {} release {}",
                deleted, projectId, graphReleaseId);
        } catch (Exception e) {
            log.warn("Semantic cache invalidation failed for project {} release {}: {}",
                projectId, graphReleaseId, e.getMessage());
        }
    }

    /**
     * 清除过期缓存
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
