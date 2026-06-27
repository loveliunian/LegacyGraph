package io.github.legacygraph.repository;

import io.github.legacygraph.entity.LlmProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LlmProviderRepository extends LegacyBaseMapper<LlmProvider> {

    @Select("SELECT * FROM lg_llm_provider WHERE provider_code = #{providerCode}")
    LlmProvider findByCode(String providerCode);

    @Select("SELECT * FROM lg_llm_provider ORDER BY provider_code")
    List<LlmProvider> findAll();
}
