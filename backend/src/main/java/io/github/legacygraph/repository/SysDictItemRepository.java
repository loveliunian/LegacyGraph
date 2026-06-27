package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.SysDictItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysDictItemRepository extends BaseMapper<SysDictItem> {
}
