package io.github.legacygraph.repository;

import io.github.legacygraph.entity.SemanticCacheEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SemanticCacheRepository extends LegacyBaseMapper<SemanticCacheEntry> {

    /**
     * 查找相似的语义缓存条目。
     * 将 embedding 字符串 CAST 为 vector 类型进行余弦相似度计算。
     */
    @Select({"""
        SELECT *, 1 - (question_embedding::vector <=> #{queryEmbedding}::vector) as similarity
        FROM lg_semantic_cache
        WHERE project_id = #{projectId}
          AND question_embedding IS NOT NULL
          AND 1 - (question_embedding::vector <=> #{queryEmbedding}::vector) >= #{threshold}
        ORDER BY question_embedding::vector <=> #{queryEmbedding}::vector
        LIMIT #{limit}
    """})
    List<SemanticCacheEntry> findSimilar(
        @Param("projectId") String projectId,
        @Param("queryEmbedding") String queryEmbedding,
        @Param("limit") int limit,
        @Param("threshold") double threshold
    );

    /**
     * 版本化查找相似的语义缓存条目 — 按 graphReleaseId 和 aclHash 过滤。
     * <p>graphReleaseId 为 null 时匹配该列为 NULL 的条目；aclHash 同理。</p>
     */
    @Select({"""
        <script>
        SELECT *, 1 - (question_embedding::vector &lt;=&gt; #{queryEmbedding}::vector) as similarity
        FROM lg_semantic_cache
        WHERE project_id = #{projectId}
          AND question_embedding IS NOT NULL
          AND 1 - (question_embedding::vector &lt;=&gt; #{queryEmbedding}::vector) &gt;= #{threshold}
          <if test="graphReleaseId != null">
            AND graph_release_id = #{graphReleaseId}
          </if>
          <if test="graphReleaseId == null">
            AND graph_release_id IS NULL
          </if>
          <if test="aclHash != null">
            AND acl_hash = #{aclHash}
          </if>
          <if test="aclHash == null">
            AND acl_hash IS NULL
          </if>
        ORDER BY question_embedding::vector &lt;=&gt; #{queryEmbedding}::vector
        LIMIT #{limit}
        </script>
    """})
    List<SemanticCacheEntry> findSimilarVersioned(
        @Param("projectId") String projectId,
        @Param("queryEmbedding") String queryEmbedding,
        @Param("limit") int limit,
        @Param("threshold") double threshold,
        @Param("graphReleaseId") String graphReleaseId,
        @Param("aclHash") String aclHash
    );
}
