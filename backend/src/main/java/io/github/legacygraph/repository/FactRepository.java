package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.Fact;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FactRepository extends BaseMapper<Fact> {
}
