package io.github.legacygraph.repository;

import io.github.legacygraph.entity.ProcessFitness;
import org.apache.ibatis.annotations.Mapper;

/**
 * 流程一致性校验结果 Mapper（H25）。
 */
@Mapper
public interface ProcessFitnessRepository extends LegacyBaseMapper<ProcessFitness> {
}
