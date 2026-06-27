package io.github.legacygraph.repository;

import io.github.legacygraph.entity.DbConnection;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DbConnectionRepository extends LegacyBaseMapper<DbConnection> {
}
