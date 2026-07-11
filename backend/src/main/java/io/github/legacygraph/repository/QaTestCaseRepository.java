package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.QaTestCase;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * QA 评测测试用例 Repository。
 * <p>
 * 提供标准 CRUD（继承自 {@link LegacyBaseMapper}）以及按状态查询的便捷方法。
 * </p>
 */
@Mapper
public interface QaTestCaseRepository extends LegacyBaseMapper<QaTestCase> {

    /**
     * 按状态查询测试用例（如 SMOKE / GOLDEN）。
     */
    default List<QaTestCase> findByStatus(String status) {
        return selectList(new LambdaQueryWrapper<QaTestCase>()
                .eq(QaTestCase::getStatus, status)
                .orderByAsc(QaTestCase::getCreatedAt));
    }
}
