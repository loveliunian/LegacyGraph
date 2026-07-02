package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.SourceAssetSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 资产快照 Repository。
 */
@Mapper
public interface SourceAssetSnapshotRepository extends BaseMapper<SourceAssetSnapshot> {
}
