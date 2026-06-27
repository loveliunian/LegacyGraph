package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.SysDict;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysDictRepository extends BaseMapper<SysDict> {
}
