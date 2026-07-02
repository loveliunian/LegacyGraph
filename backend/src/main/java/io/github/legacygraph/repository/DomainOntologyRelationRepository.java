package io.github.legacygraph.repository;

import io.github.legacygraph.entity.DomainOntologyRelation;
import org.apache.ibatis.annotations.Mapper;

/**
 * DomainOntologyRelation Repository — 基于 MyBatis-Plus BaseMapper。
 * 提供术语关系的 CRUD 基础能力，复杂查询由 Service 层封装。
 */
@Mapper
public interface DomainOntologyRelationRepository extends LegacyBaseMapper<DomainOntologyRelation> {
}
