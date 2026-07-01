package io.github.legacygraph.repository;

import io.github.legacygraph.entity.LlmProvider;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface LlmProviderRepository extends LegacyBaseMapper<LlmProvider> {

    /**
     * 按 providerCode 查询。
     * 注意：不能使用 @Select 自定义 SQL——MyBatis-Plus 的 autoResultMap + JacksonTypeHandler
     * 只对 BaseMapper 的 LambdaQueryWrapper 生效，自定义 @Select 不会触发 TypeHandler。
     */
    default LlmProvider findByCode(String providerCode) {
        return selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LlmProvider>()
                .eq(LlmProvider::getProviderCode, providerCode));
    }

    /**
     * 查询全部，按 provider_code 排序。
     */
    default List<LlmProvider> findAll() {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LlmProvider>()
                .orderByAsc(LlmProvider::getProviderCode));
    }
}
