package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.NodeEvidence;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NodeEvidenceRepository extends BaseMapper<NodeEvidence> {
}
