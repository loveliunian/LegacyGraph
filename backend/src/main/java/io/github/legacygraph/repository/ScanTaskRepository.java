package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.ScanTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScanTaskRepository extends BaseMapper<ScanTask> {
}
