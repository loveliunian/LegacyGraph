package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.ScanVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScanVersionRepository extends BaseMapper<ScanVersion> {
}
