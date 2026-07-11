package io.github.legacygraph.repository;

import io.github.legacygraph.entity.SourceSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 源快照 Mapper — 基于不可变 SourceSnapshot 父表的数据访问（G-02）。
 */
@Mapper
public interface SourceSnapshotRepository extends LegacyBaseMapper<SourceSnapshotEntity> {
}
