package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.ReviewRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReviewRecordRepository extends BaseMapper<ReviewRecord> {
}
