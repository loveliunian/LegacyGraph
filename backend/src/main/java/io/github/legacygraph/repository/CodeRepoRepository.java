package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.CodeRepo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CodeRepoRepository extends BaseMapper<CodeRepo> {
}
