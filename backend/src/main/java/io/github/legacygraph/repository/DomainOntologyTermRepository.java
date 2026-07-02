package io.github.legacygraph.repository;

import io.github.legacygraph.entity.DomainOntologyTerm;
import org.apache.ibatis.annotations.Mapper;

/**
 * DomainOntologyTerm Repository — 基于 MyBatis-Plus BaseMapper。
 * 提供领域术语的 CRUD 基础能力，复杂查询由 Service 层封装。
 */
@Mapper
public interface DomainOntologyTermRepository extends LegacyBaseMapper<DomainOntologyTerm> {
}
