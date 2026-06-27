package io.github.legacygraph.repository;

import io.github.legacygraph.entity.Project;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectRepository extends LegacyBaseMapper<Project> {
}
