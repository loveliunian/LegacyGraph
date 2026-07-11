package io.github.legacygraph.repository;

import io.github.legacygraph.entity.Solution;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SolutionRepository extends LegacyBaseMapper<Solution> {
}
