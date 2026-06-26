package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.Evidence;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EvidenceRepository extends BaseMapper<Evidence> {
}
