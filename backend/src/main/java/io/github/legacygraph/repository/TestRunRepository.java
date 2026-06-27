package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.TestRun;
import org.apache.ibatis.annotations.Mapper;

/**
 * 测试运行Repository
 */
@Mapper
public interface TestRunRepository extends BaseMapper<TestRun> {
}
