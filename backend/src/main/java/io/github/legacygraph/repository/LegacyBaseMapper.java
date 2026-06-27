package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;

import java.io.Serializable;

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
}
