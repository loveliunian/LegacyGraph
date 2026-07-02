package io.github.legacygraph.repository;

import io.github.legacygraph.entity.KnowledgeClaim;
import org.apache.ibatis.annotations.Mapper;

/**
 * KnowledgeClaim Repository — 基于 MyBatis-Plus BaseMapper。
 * 幂等写入与多条件查询由 KnowledgeClaimService 封装，
 * 本接口仅提供 CRUD 基础能力。
 */
@Mapper
public interface KnowledgeClaimRepository extends LegacyBaseMapper<KnowledgeClaim> {
}
