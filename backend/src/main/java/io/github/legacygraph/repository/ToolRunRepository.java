package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.ToolRunEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具运行记录 Repository。
 */
@Mapper
public interface ToolRunRepository extends BaseMapper<ToolRunEntity> {
}
