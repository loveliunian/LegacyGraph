package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.EdgeEvidence;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EdgeEvidenceRepository extends BaseMapper<EdgeEvidence> {
}
