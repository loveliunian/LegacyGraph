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
        SELECT *, 1 - (question_embedding::vector(1024) <=> #{queryEmbedding}::vector) as similarity
        FROM lg_semantic_cache
        WHERE project_id = #{projectId}
          AND question_embedding IS NOT NULL
          AND 1 - (question_embedding::vector(1024) <=> #{queryEmbedding}::vector) >= #{threshold}
        ORDER BY question_embedding::vector(1024) <=> #{queryEmbedding}::vector
        LIMIT #{limit}
    """})
    List<SemanticCacheEntry> findSimilar(
        @Param("projectId") String projectId,
        @Param("queryEmbedding") String queryEmbedding,
        @Param("limit") int limit,
        @Param("threshold") double threshold
    );
}
