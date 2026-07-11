package io.github.legacygraph.repository;

import io.github.legacygraph.entity.ScanCheckpoint;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScanCheckpointRepository extends LegacyBaseMapper<ScanCheckpoint> {
}
