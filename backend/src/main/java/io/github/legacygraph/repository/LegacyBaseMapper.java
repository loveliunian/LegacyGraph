package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import org.apache.ibatis.logging.LogFactory;

import java.io.Serializable;
import java.util.List;

public interface LegacyBaseMapper<T> extends BaseMapper<T> {

    default T getById(Serializable id) {
        return selectById(id);
    }

    default boolean save(T entity) {
        return insert(entity) > 0;
    }

    default LambdaQueryChainWrapper<T> lambdaQuery() {
        return new LambdaQueryChainWrapper<>(this);
    }

    /**
     * 批量插入实体列表，使用 MyBatis 批量模式提升性能。
     */
    default boolean insertBatch(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return true;
        }
        Class<?> entityClass = entities.get(0).getClass();
        return SqlHelper.executeBatch(
            entityClass,
            LogFactory.getLog(entityClass),
            sqlSession -> {
                for (T entity : entities) {
                    insert(entity);
                }
            }
        );
    }

    /**
     * 批量更新实体列表，使用 MyBatis 批量模式提升性能。
     */
    default boolean updateBatch(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return true;
        }
        Class<?> entityClass = entities.get(0).getClass();
        return SqlHelper.executeBatch(
            entityClass,
            LogFactory.getLog(entityClass),
            sqlSession -> {
                for (T entity : entities) {
                    updateById(entity);
                }
            }
        );
    }
}
