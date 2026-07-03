package io.github.legacygraph.repository;

import io.github.legacygraph.entity.SemanticCacheEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SemanticCacheRepository extends LegacyBaseMapper<SemanticCacheEntry> {
    
    /**
     * 查找相似的语义缓存条目
     */
    @Select({"""
        SELECT *, 1 - (question_embedding <=> #{queryEmbedding,typeHandler=io.github.legacygraph.handler.FloatArrayTypeHandler}) as similarity
        FROM lg_semantic_cache
        WHERE project_id = #{projectId}
          AND question_embedding IS NOT NULL
          AND 1 - (question_embedding <=> #{queryEmbedding,typeHandler=io.github.legacygraph.handler.FloatArrayTypeHandler}) >= #{threshold}
        ORDER BY question_embedding <=> #{queryEmbedding,typeHandler=io.github.legacygraph.handler.FloatArrayTypeHandler}
        LIMIT #{limit}
    """})
    List<SemanticCacheEntry> findSimilar(
        @Param("projectId") String projectId,
        @Param("queryEmbedding") List<Double> queryEmbedding,
        @Param("limit") int limit,
        @Param("threshold") double threshold
    );
}
