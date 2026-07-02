package io.github.legacygraph.repository;

import io.github.legacygraph.entity.GapTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * GapTask Repository — 基于 MyBatis-Plus BaseMapper。
 * 幂等写入与多条件查询由 GapFinderService 封装，
 * 本接口仅提供 CRUD 基础能力。
 */
@Mapper
public interface GapTaskRepository extends LegacyBaseMapper<GapTask> {
}
