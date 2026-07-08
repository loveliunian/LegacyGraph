package io.github.legacygraph.repository;

import io.github.legacygraph.entity.TerminologyMapping;
import org.apache.ibatis.annotations.Mapper;

/**
 * 术语映射仓储。
 */
@Mapper
public interface TerminologyMappingRepository extends LegacyBaseMapper<TerminologyMapping> {
}
