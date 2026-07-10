package io.github.legacygraph.repository;

import io.github.legacygraph.entity.Requirement;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RequirementRepository extends LegacyBaseMapper<Requirement> {
}
