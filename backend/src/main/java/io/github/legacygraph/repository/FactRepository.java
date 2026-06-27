package io.github.legacygraph.repository;

import io.github.legacygraph.entity.Fact;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FactRepository extends LegacyBaseMapper<Fact> {
}
