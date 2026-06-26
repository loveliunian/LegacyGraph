package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.GraphEdge;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GraphEdgeRepository extends BaseMapper<GraphEdge> {
}
