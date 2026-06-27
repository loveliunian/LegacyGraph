package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.MigrationRisk;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MigrationRiskRepository extends BaseMapper<MigrationRisk> {
}
