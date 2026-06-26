package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.Project;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectRepository extends BaseMapper<Project> {
}
