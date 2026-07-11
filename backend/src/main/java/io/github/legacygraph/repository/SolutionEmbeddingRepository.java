package io.github.legacygraph.repository;

import io.github.legacygraph.entity.SolutionEmbedding;
import org.apache.ibatis.annotations.Mapper;

/**
 * 方案嵌入索引 Mapper（G-15）。
 * <p>继承 {@link LegacyBaseMapper}，提供方案 embedding 的 CRUD 能力。</p>
 */
@Mapper
public interface SolutionEmbeddingRepository extends LegacyBaseMapper<SolutionEmbedding> {
}
