package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.GraphNode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GraphNodeRepository extends BaseMapper<GraphNode> {
}
