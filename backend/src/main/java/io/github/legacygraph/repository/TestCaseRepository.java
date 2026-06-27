package io.github.legacygraph.repository;

import io.github.legacygraph.entity.TestCase;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TestCaseRepository extends LegacyBaseMapper<TestCase> {
}
