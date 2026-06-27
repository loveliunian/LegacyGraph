package io.github.legacygraph.repository;

import io.github.legacygraph.entity.MigrationRisk;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MigrationRiskRepository extends LegacyBaseMapper<MigrationRisk> {
}
