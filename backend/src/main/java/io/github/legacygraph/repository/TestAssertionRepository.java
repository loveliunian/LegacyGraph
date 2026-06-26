package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.TestAssertion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TestAssertionRepository extends BaseMapper<TestAssertion> {
}
