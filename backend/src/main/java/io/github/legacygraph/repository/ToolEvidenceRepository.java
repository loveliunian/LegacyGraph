package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.ToolEvidenceEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具证据记录 Repository。
 */
@Mapper
public interface ToolEvidenceRepository extends BaseMapper<ToolEvidenceEntity> {
}
