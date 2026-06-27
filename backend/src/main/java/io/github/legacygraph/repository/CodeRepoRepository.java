package io.github.legacygraph.repository;

import io.github.legacygraph.entity.CodeRepo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CodeRepoRepository extends LegacyBaseMapper<CodeRepo> {
}
