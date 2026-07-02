package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.GraphWriteIntentEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 图谱写入意图 Repository。
 */
@Mapper
public interface GraphWriteIntentRepository extends BaseMapper<GraphWriteIntentEntity> {
}
