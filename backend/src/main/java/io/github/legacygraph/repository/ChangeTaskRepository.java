package io.github.legacygraph.repository;

import io.github.legacygraph.entity.ChangeTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChangeTaskRepository extends LegacyBaseMapper<ChangeTask> {
}
