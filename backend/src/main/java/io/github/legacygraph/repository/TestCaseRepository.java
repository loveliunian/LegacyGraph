package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.TestCase;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TestCaseRepository extends BaseMapper<TestCase> {
}
