package io.github.legacygraph.repository;

import io.github.legacygraph.entity.ReviewRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReviewRecordRepository extends LegacyBaseMapper<ReviewRecord> {
}
