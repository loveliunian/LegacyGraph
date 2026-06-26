package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.DbConnection;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DbConnectionRepository extends BaseMapper<DbConnection> {
}
