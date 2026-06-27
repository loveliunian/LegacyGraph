package io.github.legacygraph.repository;

import io.github.legacygraph.entity.TestResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TestResultRepository extends LegacyBaseMapper<TestResult> {

    @Select("""
            SELECT test_case_id
            FROM lg_test_result
            WHERE execution_id = #{executionId}
              AND result_status IN ('FAILED', 'ERROR')
            """)
    List<String> findFailedCaseIds(@Param("executionId") String executionId);

    @Select("""
            SELECT *
            FROM lg_test_result
            WHERE execution_id = #{executionId}
            ORDER BY executed_at DESC
            """)
    List<TestResult> findByExecutionId(@Param("executionId") String executionId);

    @Update("""
            UPDATE lg_test_result
            SET result_status = #{status}
            WHERE execution_id = #{executionId}
            """)
    int updateStatusByExecutionId(@Param("executionId") String executionId,
                                  @Param("status") String status);
}
